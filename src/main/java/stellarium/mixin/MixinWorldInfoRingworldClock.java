package stellarium.mixin;

import net.minecraft.world.storage.WorldInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.world.ring.RingworldWorldTimeMutationAccess;

@Mixin(WorldInfo.class)
public abstract class MixinWorldInfoRingworldClock implements RingworldWorldTimeMutationAccess {
    @Unique private long stellarium$worldTimeMutationRevision;
    @Unique private boolean stellarium$worldTimeMutationRevisionSaturated;

    @Inject(method = "setWorldTime(J)V", at = @At("HEAD"), require = 1)
    private void stellarium$observeWorldTimeMutation(long time, CallbackInfo callback) {
        if (stellarium$worldTimeMutationRevision == Long.MAX_VALUE) {
            stellarium$worldTimeMutationRevisionSaturated = true;
            return;
        }
        stellarium$worldTimeMutationRevision++;
    }

    @Override public long stellarium$getWorldTimeMutationRevision() { return stellarium$worldTimeMutationRevision; }
    @Override public boolean stellarium$isWorldTimeMutationRevisionSaturated() { return stellarium$worldTimeMutationRevisionSaturated; }
}
