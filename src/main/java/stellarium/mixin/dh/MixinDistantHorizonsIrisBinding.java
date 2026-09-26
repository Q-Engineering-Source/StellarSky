package stellarium.mixin.dh;

import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsIrisBinding;

/** DH 3.3 registers an accessor that Actinium 0.0.8 already bound during construction. */
@Mixin(targets = "com.seibel.distanthorizons.cleanroom.CleanroomMain", remap = false)
public abstract class MixinDistantHorizonsIrisBinding {
    @Shadow public static IIrisAccessor IRIS_ACCESSOR;

    @Inject(method = "initializeModCompat()V", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    protected void stellarium$reuseIrisBinding(CallbackInfo callback) {
        IIrisAccessor existing = DistantHorizonsIrisBinding.reuse(ModAccessorInjector.INSTANCE);
        if (existing != null) {
            IRIS_ACCESSOR = existing;
            callback.cancel();
        }
    }
}
