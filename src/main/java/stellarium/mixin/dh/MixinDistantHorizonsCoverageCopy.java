package stellarium.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverageBridge;

/** Copy methods may return early for missing targets; their RETURN alone is not composition. */
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.postProcessing.copy.GlDhCopyShader", remap = false)
public abstract class MixinDistantHorizonsCoverageCopy {
    @WrapOperation(method = {"renderToFrameBuffer()V", "renderToMcTexture()V"},
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/common/render/openGl/postProcessing/GlScreenQuad;render()V"),
            require = 2, allow = 2)
    private void stellarium$observeCopyDraw(GlScreenQuad quad, Operation<Void> original) {
        original.call(quad);
        DistantHorizonsFrameCoverageBridge.copiedQuad();
    }
}
