package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProviderSingle;
import org.junit.BeforeClass;
import org.junit.Test;

public class RingworldBiomeProviderTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void pointAndBlockRowsRespectBothHalfOpenEdges() {
        var space = new SpaceBiome();
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), space);
        assertSame(space, provider.getBiome(new BlockPos(0, 0, -8193)));
        assertSame(Biomes.PLAINS, provider.getBiome(new BlockPos(0, 0, -8192)));
        assertSame(Biomes.PLAINS, provider.getBiome(new BlockPos(0, 0, 8191)));
        assertSame(space, provider.getBiome(new BlockPos(0, 0, 8192)));
        assertArrayEquals(new Biome[] {Biomes.PLAINS, Biomes.PLAINS, space, space},
                provider.getBiomes(null, 0, 8191, 2, 2));
        assertArrayEquals(new Biome[] {space, Biomes.PLAINS}, provider.getBiomes(null, 0, -8193, 1, 2, false));
    }

    @Test public void generationQueriesUseFourBlockCells() {
        var space = new SpaceBiome();
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), space);
        assertArrayEquals(new Biome[] {Biomes.PLAINS, space},
                provider.getBiomesForGeneration(null, 0, 2047, 1, 2));
        assertArrayEquals(new Biome[] {space, Biomes.PLAINS},
                provider.getBiomesForGeneration(null, 0, -2049, 1, 2));
    }

    @Test public void fullyExteriorQueryNeverCallsDelegateAndReusesBuffer() {
        var space = new SpaceBiome();
        var delegate = new BiomeProviderSingle(Biomes.PLAINS) {
            @Override public Biome[] getBiomes(Biome[] result, int x, int z, int w, int h, boolean cache) {
                throw new AssertionError("No exterior biome sampling");
            }
            @Override public Biome[] getBiomesForGeneration(Biome[] result, int x, int z, int w, int h) {
                throw new AssertionError("No exterior generation sampling");
            }
        };
        var provider = new RingworldBiomeProvider(delegate, space);
        Biome[] reuse = {Biomes.PLAINS, Biomes.PLAINS, Biomes.PLAINS};
        assertSame(reuse, provider.getBiomes(reuse, 0, 8192, 1, 2));
        assertArrayEquals(new Biome[] {space, space, Biomes.PLAINS}, reuse);
        assertSame(space, provider.getBiomesForGeneration(null, 0, -3000, 1, 1)[0]);
    }

    @Test public void biomeSearchAndViabilityCannotReturnExteriorPlains() {
        var space = new SpaceBiome();
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), space);
        assertFalse(provider.areBiomesViable(0, 8192, 16, List.of(Biomes.PLAINS)));
        assertTrue(provider.areBiomesViable(0, 8192, 16, List.of(Biomes.PLAINS, space)));
        assertNull(provider.findBiomePosition(0, 9000, 32, List.of(Biomes.PLAINS), new Random(1)));
        var found = provider.findBiomePosition(0, 8192, 16, List.of(Biomes.PLAINS), new Random(1));
        assertNotNull(found);
        assertTrue(found.getZ() < 8192);
        assertFalse(provider.isFixedBiome());
        assertNull(provider.getFixedBiome());
    }

    @Test public void spaceHasNoNaturalSpawnOrTerrainDecoration() {
        var space = new SpaceBiome();
        assertFalse(space.canRain());
        for (var type : EnumCreatureType.values()) {
            assertTrue(space.getSpawnableList(type).isEmpty());
        }
        space.decorate(null, new Random(0), BlockPos.ORIGIN);
        assertTrue(SpaceBiome.suppressesNaturalGeneration(space));
        assertFalse(SpaceBiome.suppressesNaturalGeneration(Biomes.PLAINS));
    }

    @Test public void unsupportedMixedSpaceChunkFailsInsteadOfLeavingBaseStone() {
        var space = new SpaceBiome();
        var mixed = new BiomeProviderSingle(Biomes.PLAINS) {
            @Override public Biome[] getBiomes(Biome[] reuse, int x, int z, int w, int h, boolean cache) {
                Biome[] result = new Biome[w * h];
                Arrays.fill(result, Biomes.PLAINS);
                result[0] = space;
                return result;
            }
        };
        var provider = new RingworldBiomeProvider(mixed, space);
        assertThrows(IllegalStateException.class, () -> provider.isSpaceChunk(0, 0));
    }
}
