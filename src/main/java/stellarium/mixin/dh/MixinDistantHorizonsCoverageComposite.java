package stellarium.mixin.dh;

import com.seibel.distanthorizons.core.render.RenderParams;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverageBridge;

@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer", remap = false)
public abstract class MixinDistantHorizonsCoverageComposite {
    @WrapMethod(method = "copyToMcTexture(Lcom/seibel/distanthorizons/core/render/RenderParams;)V",
            remap = false, require = 1, allow = 1)
    private void stellarium$afterMainComposite(RenderParams params, Operation<Void> original) {
        DistantHorizonsFrameCoverageBridge.copyToMain(params, () -> original.call(params));
    }
}
