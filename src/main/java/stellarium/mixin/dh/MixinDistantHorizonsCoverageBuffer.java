package stellarium.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsCoverageBuffer;
import stellarium.client.ring.dh.DistantHorizonsCoverageTransfer;

@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer", remap = false)
public abstract class MixinDistantHorizonsCoverageBuffer implements DistantHorizonsCoverageBuffer {
    @Unique private final DistantHorizonsCoverageTransfer.Attachment stellarium$coverage = new DistantHorizonsCoverageTransfer.Attachment();
    @Override public DistantHorizonsCoverageTransfer.Attachment stellarium$coverageAttachment() { return stellarium$coverage; }

    @Inject(method = "close()V", at = @At("HEAD"), require = 1, allow = 1)
    private void stellarium$invalidateBeforeBufferRelease(CallbackInfo ci) { stellarium$coverage.close(); }
}
