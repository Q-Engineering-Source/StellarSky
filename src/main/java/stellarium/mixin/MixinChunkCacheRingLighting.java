package stellarium.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.world.ring.RingworldLightFrame;
import stellarium.world.ring.RingworldLighting;

@Mixin(ChunkCache.class)
public abstract class MixinChunkCacheRingLighting {
    @Unique
    private RingworldLightFrame stellarium$ringworldFrame;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void stellarium$captureLightFrame(World world, BlockPos from, BlockPos to,
                                             int padding, CallbackInfo callback) {
        // Workers use this immutable snapshot, not mutable scene/config/clock state.
        stellarium$ringworldFrame = RingworldLighting.frame(world);
    }

    @Inject(method = "getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void stellarium$shadePackedSky(BlockPos pos, int minimumBlockLight,
                                         CallbackInfoReturnable<Integer> callback) {
        if (stellarium$ringworldFrame != null) {
            callback.setReturnValue(stellarium$ringworldFrame.shadedPackedLight(callback.getReturnValue(),
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
    }
}
