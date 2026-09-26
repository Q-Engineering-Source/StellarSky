package stellarium.world.ring.generation;

import static org.junit.Assert.*;
import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.structure.StructureStart;
import org.junit.BeforeClass;
import org.junit.Test;

public class RingworldStructureLookupTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }
    private final RingworldBiomeProvider biomes = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), new SpaceBiome());

    @Test public void rejectedStartNeverComputesLayout() {
        for (int z : new int[] {-513, -508, 507, 1296}) {
            assertFalse(RingworldStructureLookup.allows(biomes, 0, z, null,
                    () -> { throw new AssertionError("Rejected start sampled"); },
                    () -> { throw new AssertionError("Rejected start built"); }));
        }
    }

    @Test public void existingIndexSurvivesOutsideNewGenerationArea() {
        var existing = new StructureStart() {};
        assertTrue(RingworldStructureLookup.allows(biomes, 0, 1296, existing,
                () -> { throw new AssertionError("Existing start resampled"); },
                () -> { throw new AssertionError("Existing start rebuilt"); }));
        var invalid = new StructureStart() { @Override public boolean isSizeableStructure() { return false; } };
        assertFalse(RingworldStructureLookup.allows(biomes, 0, 0, invalid, () -> true, () -> existing));
    }

    @Test public void noSpawnOrInvalidComponentsCannotBeReturned() {
        assertFalse(RingworldStructureLookup.allows(biomes, 0, 0, null, () -> false,
                () -> { throw new AssertionError("Noncandidate layout"); }));
        assertFalse(RingworldStructureLookup.allows(biomes, 0, 0, null, () -> true, () -> new StructureStart() {}));
    }

    @Test public void nearestSearchContinuesPastRejectedCandidateAndCanExhaust() {
        var candidates = new ChunkPos[] {new ChunkPos(-46, 1296), new ChunkPos(3, 300), new ChunkPos(4, 500)};
        var origin = new BlockPos(0, 64, 20744);
        assertEquals(new BlockPos(72, 32, 8008), RingworldStructureLookup.nearest(candidates, origin,
                p -> new RingworldGenerationPolicy().allowsStructureStart(p.z)));
        assertNull(RingworldStructureLookup.nearest(candidates, origin, p -> false));
    }
}
