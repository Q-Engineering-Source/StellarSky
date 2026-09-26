package stellarium.mixin.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.client.ring.dh.DistantHorizonsCurvatureState;

/**
 * DH's stock fog inverts a flat matrix from its depth texture.  That reconstruction is invalid
 * after the native vertex curve. It is therefore skipped only if the same frozen render scope
 * will run StellarSky's spatial-air replacement. DH's configured fog remains active in legacy,
 * low-power, water/lava/blindness and ordinary flat passes.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.LodRenderer", remap = false)
public abstract class MixinDistantHorizonsFog {
    @Redirect(method = "renderTerrain(Lcom/seibel/distanthorizons/core/render/RenderParams;"
                    + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhFogRenderer;"
                            + "render(Lcom/seibel/distanthorizons/core/render/RenderParams;"
                            + "Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiFogRenderParam;)V"),
            require = 2, allow = 2)
    private void stellarium$skipFlatFog(IDhFogRenderer fogRenderer, RenderParams params,
                                        DhApiFogRenderParam fogParams) {
        if (!DistantHorizonsCurvatureState.replacesNativeFog()) {
            fogRenderer.render(params, fogParams);
        }
    }
}
