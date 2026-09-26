package stellarium.mixin.actinium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import stellarium.client.ring.actinium.ActiniumCurvatureState;

@Mixin(targets = "com.dhj.actinium.render.terrain.VintageRenderSectionManager$ChunkRenderer", remap = false)
public abstract class MixinActiniumTerrainFaces {
    @Inject(method = "useBlockFaceCulling()Z", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1)
    private void stellarium$disableFlatFaceMask(CallbackInfoReturnable<Boolean> callback) {
        if (ActiniumCurvatureState.active()) callback.setReturnValue(false);
    }

    @Inject(method = "begin(Lorg/embeddedt/embeddium/impl/render/chunk/terrain/TerrainRenderPass;)V",
            at = @At("HEAD"), require = 1, allow = 1)
    private void stellarium$rejectUnsupportedOverride(TerrainRenderPass pass, CallbackInfo callback) {
        var provider = ShaderProviderHolder.getProvider();
        if (ActiniumCurvatureState.active() && (ShaderProviderHolder.isShadowPass()
                || (provider != null && provider.isShadersEnabled()))) {
            throw new IllegalStateException("StellarSky curvature requires Actinium's native terrain program; shaderpack/shadow overrides are not admitted");
        }
    }
}
