package stellarium.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.world.ring.RingworldDisplayLightField;
import stellarium.world.ring.RingworldLightFrame;
import stellarium.world.ring.RingworldLighting;

/** Client-only: vanilla removes getCombinedLight on a dedicated server. */
@Mixin(World.class)
public abstract class MixinWorldRingCombinedLight {
    @Inject(method = "getCombinedLight(Lnet/minecraft/util/math/BlockPos;I)I",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void stellarium$shadePackedSky(BlockPos pos, int minimumBlockLight,
                                         CallbackInfoReturnable<Integer> callback) {
        World world = (World) (Object) this;
        if (RingworldRenderSnapshots.isScopeActive()) {
            RingworldDisplayLightField displayField = RingworldRenderSnapshots.currentDisplayLightFieldFor(world);
            if (displayField != null) {
                callback.setReturnValue(displayField.shadedPackedLight(callback.getReturnValue(),
                        pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
            }
            // An active but unusable nested/empty/mismatched render scope fails
            // closed instead of falling through to a long-lived world frame.
            return;
        }
        // Only non-render callers retain the world-frame fallback.
        RingworldLightFrame frame = RingworldLighting.frame(world);
        if (frame != null) {
            callback.setReturnValue(frame.shadedPackedLight(callback.getReturnValue(),
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }
    }
}
