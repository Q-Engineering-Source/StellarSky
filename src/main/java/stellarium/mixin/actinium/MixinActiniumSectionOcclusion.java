package stellarium.mixin.actinium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.client.ring.actinium.ActiniumCurvatureState;

@Mixin(targets = "com.dhj.actinium.render.terrain.VintageRenderSectionManager", remap = false)
public abstract class MixinActiniumSectionOcclusion {
    @Inject(method = "useFogOcclusion()Z", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1)
    private void stellarium$disableFlatFogDistance(CallbackInfoReturnable<Boolean> callback) {
        if (ActiniumCurvatureState.active()) callback.setReturnValue(false);
    }

    @Inject(method = "shouldUseOcclusionCulling(Lorg/embeddedt/embeddium/impl/render/viewport/Viewport;Z)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void stellarium$disableFlatGraphOcclusion(CallbackInfoReturnable<Boolean> callback) {
        if (ActiniumCurvatureState.active()) callback.setReturnValue(false);
    }
}
