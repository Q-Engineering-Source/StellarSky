package stellarium.client.ring.dh;

import com.dhj.actinium.compat.dh.ActiniumDHIrisAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModAccessor;
import org.junit.Test;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.mixin.dh.MixinDistantHorizonsIrisBinding;
import static org.junit.Assert.*;

public class DistantHorizonsIrisBindingTest {
    @Test public void duplicateRegistrationReproducesAndExistingBindingIsUpgradedOnce() {
        var injector = new ModAccessorInjector(IModAccessor.class);
        var actinium = new ActiniumDHIrisAccessor();
        injector.bind(IIrisAccessor.class, actinium);
        assertThrows(IllegalStateException.class,
                () -> injector.bind(IIrisAccessor.class, new ActiniumDHIrisAccessor()));
        IIrisAccessor shared = DistantHorizonsIrisBinding.reuse(injector);
        assertNotNull(shared);
        assertSame(shared, injector.get(IIrisAccessor.class));
        assertSame(shared, DistantHorizonsIrisBinding.reuse(injector));
        assertEquals(1, injector.getAll(IIrisAccessor.class).size());
        assertEquals("ActiniumShaders", shared.getModName());
    }

    @Test public void absentBindingLeavesOriginalDhInitializationInCharge() {
        assertNull(DistantHorizonsIrisBinding.reuse(new ModAccessorInjector(IModAccessor.class)));
    }

    @Test public void newQueriesMatchDh330DepthContract() {
        var accessor = new DistantHorizonsIrisBinding.CompatibleAccessor();
        assertFalse(accessor.isReverseZDuringShaders());
        assertEquals(-1, accessor.getFramebufferDepthTextureId(null));
        assertEquals(-1, accessor.getFramebufferDepthTextureId(new Object()));
        assertEquals(71, accessor.getFramebufferDepthTextureId(new IRenderTargetExt() {
            public int iris$getDepthBufferVersion() { return 0; }
            public int iris$getColorBufferVersion() { return 0; }
            public int iris$getDepthTextureId() { return 71; }
        }));
    }

    @Test public void actualInjectionHandlerPublishesSharedReferenceAndCancelsDuplicatePath() {
        var injector = ModAccessorInjector.INSTANCE;
        injector.clear();
        try {
            var harness = new Handler();
            var empty = new CallbackInfo("initializeModCompat", true);
            harness.invoke(empty);
            assertFalse(empty.isCancelled());
            injector.bind(IIrisAccessor.class, new ActiniumDHIrisAccessor());
            var bound = new CallbackInfo("initializeModCompat", true);
            harness.invoke(bound);
            assertTrue(bound.isCancelled());
            assertSame(injector.get(IIrisAccessor.class), Handler.IRIS_ACCESSOR);
        } finally {
            injector.clear();
            Handler.IRIS_ACCESSOR = null;
        }
    }

    private static final class Handler extends MixinDistantHorizonsIrisBinding {
        void invoke(CallbackInfo callback) { stellarium$reuseIrisBinding(callback); }
    }
}
