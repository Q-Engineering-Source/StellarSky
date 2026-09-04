package stellarium.mixin;

import net.minecraft.block.BlockDaylightDetector;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.world.ring.RingworldLighting;

@Mixin(BlockDaylightDetector.class)
public abstract class MixinDaylightDetectorRingLighting {
    // The ring helper supplies zenith angle; retain vanilla normal/inverted power updates.
    @Redirect(
            method = "updatePower(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getSkylightSubtracted()I"),
            require = 1)
    private int stellarium$readLocalSubtraction(World world, World targetWorld, BlockPos pos) {
        return RingworldLighting.skySubtraction(world, pos, world.getSkylightSubtracted());
    }
}
