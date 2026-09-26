package stellarium.mixin.actinium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.actinium.ActiniumCurvatureState;

/** A flat frustum cannot reject bent geometry. Preserve native culling outside the admitted frame. */
@Mixin(targets = "org.embeddedt.embeddium.impl.render.viewport.Viewport", remap = false)
public abstract class MixinActiniumViewport {
    // Viewports are handed to the async graph worker. Capture the scope on their
    // constructing render thread instead of looking up a render ThreadLocal there.
    @Unique private boolean stellarium$curved;

    @Inject(method = "<init>(Lorg/embeddedt/embeddium/impl/render/viewport/frustum/Frustum;Lorg/embeddedt/embeddium/impl/shadow/joml/Vector3d;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void stellarium$captureGeometry(CallbackInfo callback) {
        stellarium$curved = ActiniumCurvatureState.active();
    }

    @Inject(method = {"isBoxVisible(DDDDDD)Z", "isBoxVisible(IIIFFF)Z"},
            at = @At("HEAD"), cancellable = true, require = 2, allow = 2)
    private void stellarium$keepCurvedBoxes(CallbackInfoReturnable<Boolean> callback) {
        if (stellarium$curved) callback.setReturnValue(true);
    }

    @Inject(method = "intersectCameraRelativeBox(FFFFFF)I", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1)
    private void stellarium$keepCurvedOctreeBranches(CallbackInfoReturnable<Integer> callback) {
        // JOML FrustumIntersection.INTERSECT: descend conservatively, never mark a flat box OUTSIDE.
        if (stellarium$curved) callback.setReturnValue(-1);
    }
}
