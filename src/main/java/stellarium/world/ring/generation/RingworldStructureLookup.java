package stellarium.world.ring.generation;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureStart;

/** Narrow bridge implemented on MapGenStructure; no structure index or chunk writes. */
public interface RingworldStructureLookup {
    boolean stellarium$allowsLocate(World world, int chunkX, int chunkZ);

    static boolean allows(RingworldBiomeProvider biomes, int x, int z, StructureStart existing,
                          BooleanSupplier canSpawn, Supplier<StructureStart> layout) {
        if (existing != null) return existing.isSizeableStructure();
        return RingworldStructureAdmission.allowsStart(biomes, x, z) && canSpawn.getAsBoolean()
                && RingworldStructureAdmission.allowsNewStructure(biomes, layout.get());
    }

    static BlockPos nearest(ChunkPos[] candidates, BlockPos origin, Predicate<ChunkPos> allowed) {
        BlockPos best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var candidate : candidates) {
            var pos = new BlockPos((candidate.x << 4) + 8, 32, (candidate.z << 4) + 8);
            double nextDistance = pos.distanceSq(origin);
            // Expensive component layout is needed only for a candidate that could win.
            if (nextDistance < distance && allowed.test(candidate)) {
                best = pos;
                distance = nextDistance;
            }
        }
        return best;
    }
}
