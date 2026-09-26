package stellarium.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsCoverageTransfer;
import stellarium.client.ring.dh.DistantHorizonsCoverageTree;

@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree", remap = false)
public abstract class MixinDistantHorizonsCoverageTree implements DistantHorizonsCoverageTree {
    @Unique private final DistantHorizonsCoverageTransfer.Generation stellarium$coverage = new DistantHorizonsCoverageTransfer.Generation();
    @Override public DistantHorizonsCoverageTransfer.Generation stellarium$coverageGeneration() { return stellarium$coverage; }

    @Inject(method = "close()V", at = @At("HEAD"), require = 1, allow = 1)
    private void stellarium$invalidateBeforeAsyncCleanup(CallbackInfo ci) { stellarium$coverage.close(); }

    @Inject(method = "clearRenderDataCache()V", at = @At("HEAD"), require = 1, allow = 1)
    private void stellarium$invalidateBeforeReload(CallbackInfo ci) { stellarium$coverage.refresh(); }
}
