package stellarium.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also stops compatibility paths that call vanilla clouds despite the forced-off option. */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalDisableVanillaClouds {
    @Inject(method = {"renderClouds(FIDDD)V", "renderCloudsFancy(FIDDD)V"},
            at = @At("HEAD"), cancellable = true, require = 2, allow = 2)
    private void stellarium$disableVanillaClouds(float partialTicks, int pass, double x, double y, double z,
                                                CallbackInfo callback) {
        callback.cancel();
    }
}
