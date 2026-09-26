package stellarium.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenStronghold;
import net.minecraft.world.gen.structure.MapGenStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.world.ring.generation.RingworldBiomeProvider;
import stellarium.world.ring.generation.RingworldStructureLookup;

@Mixin(MapGenStronghold.class)
public abstract class MixinStrongholdRingLocate extends MapGenStructure {
    @Shadow private boolean ranBiomeCheck;
    @Shadow private ChunkPos[] structureCoords;
    @Shadow protected abstract void generatePositions();

    @Inject(method = "getNearestStructurePos", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void stellarium$locateAllowedStronghold(World lookupWorld, BlockPos pos, boolean unexplored,
                                                   CallbackInfoReturnable<BlockPos> result) {
        if (!(lookupWorld.getBiomeProvider() instanceof RingworldBiomeProvider)) return;
        world = lookupWorld;
        if (!ranBiomeCheck) {
            generatePositions();
            ranBiomeCheck = true;
        }
        result.setReturnValue(RingworldStructureLookup.nearest(structureCoords, pos, candidate ->
                (!unexplored || !lookupWorld.isChunkGeneratedAt(candidate.x, candidate.z))
                && ((RingworldStructureLookup) this).stellarium$allowsLocate(lookupWorld, candidate.x, candidate.z)));
    }
}
