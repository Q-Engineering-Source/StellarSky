package stellarium.mixin;

import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.MapGenMineshaft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.world.ring.generation.RingworldStructureLookup;

@Mixin(MapGenMineshaft.class)
public abstract class MixinMineshaftRingLocate {
    @Redirect(method = "getNearestStructurePos", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/structure/MapGenMineshaft;canSpawnStructureAtCoords(II)Z"),
            require = 1, allow = 1)
    private boolean stellarium$admitMineshaftCandidate(MapGenMineshaft generator, int x, int z,
            World world, BlockPos origin, boolean unexplored) {
        return ((RingworldStructureLookup) generator).stellarium$allowsLocate(world, x, z);
    }
}
