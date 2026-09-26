package stellarium.mixin.dh;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.client.ring.dh.DistantHorizonsCurvatureState;

/** A flat DH frustum can reject geometry that the curved vertex stage would bring into view. */
@Mixin(targets = "com.seibel.distanthorizons.core.render.RenderBufferHandler", remap = false)
public abstract class MixinDistantHorizonsRenderBufferCulling {
    @Redirect(method = "buildRenderList(Lcom/seibel/distanthorizons/core/render/RenderParams;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/seibel/distanthorizons/api/interfaces/override/rendering/IDhApiCullingFrustum;intersects(IIII)Z"),
            require = 1, allow = 1)
    private boolean stellarium$keepCurvedLod(IDhApiCullingFrustum frustum,
                                             int sectionX, int sectionZ, int sectionY, int detailLevel) {
        return DistantHorizonsCurvatureState.currentFrame() != null
                || frustum.intersects(sectionX, sectionZ, sectionY, detailLevel);
    }
}
