package stellarium.client.ring.dh;

import com.dhj.actinium.compat.dh.ActiniumDHIrisAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import net.coderbot.iris.rendertarget.IRenderTargetExt;

/** Only called by the DH 3.3 client initializer, before its duplicate binding. */
public final class DistantHorizonsIrisBinding {
    private DistantHorizonsIrisBinding() {}

    public static IIrisAccessor reuse(ModAccessorInjector injector) {
        IIrisAccessor existing = injector.get(IIrisAccessor.class);
        if (existing == null || existing instanceof CompatibleAccessor) return existing;
        if (existing.getClass() != ActiniumDHIrisAccessor.class) {
            throw new IllegalStateException("Unexpected DH Iris accessor owner: " + existing.getClass().getName());
        }
        var compatible = new CompatibleAccessor();
        injector.replaceBinding(IIrisAccessor.class, compatible);
        return compatible;
    }

    /** Actinium 0.0.8 owns shader state; DH 3.3 adds these two queries. */
    public static final class CompatibleAccessor extends ActiniumDHIrisAccessor {
        // No @Override: the default 3.2 compile contract does not yet declare these methods.
        public boolean isReverseZDuringShaders() { return false; }

        public int getFramebufferDepthTextureId(Object framebuffer) {
            return framebuffer instanceof IRenderTargetExt target ? target.iris$getDepthTextureId() : -1;
        }
    }
}
