package stellarium.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.world.ring.RingworldLightFrame;
import stellarium.world.ring.RingworldLighting;

/** Client-only: vanilla removes getCombinedLight on a dedicated server. */
@Mixin(World.class)
public abstract class MixinWorldRingCombinedLight {
    @Inject(method = "getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void stellarium$shadePackedSky(BlockPos pos, int minimumBlockLight,
                                         CallbackInfoReturnable<Integer> callback) {
        RingworldLightFrame frame = RingworldLighting.frame((World) (Object) this);
        if (frame != null) {
            callback.setReturnValue(frame.shadedPackedLight(callback.getReturnValue(),
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
    }
}
