package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeProviderSingle;
import org.junit.BeforeClass;
import org.junit.Test;

public class RingworldGenerationScopeTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void onlyNaturalScopeRejectsSpaceWritesAndExceptionRestoresPlacement() {
        var world = new RingworldChunkGeneratorTest.FixtureWorld();
        var biomes = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), new SpaceBiome());
        var outside = new BlockPos(0, 32, 8192);
        assertFalse(RingworldGenerationScope.suppresses(world, outside));
        var failure = new IllegalStateException("fixture generation failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> {
            try (var scope = RingworldGenerationScope.open(world, biomes)) {
                assertTrue(RingworldGenerationScope.suppresses(world, outside));
                assertFalse(RingworldGenerationScope.suppresses(world, new BlockPos(0, 32, 8191)));
                throw failure;
            }
        }));
        assertFalse(RingworldGenerationScope.suppresses(world, outside));
    }

    @Test public void nestedWorldsPreserveTheirOwnNaturalScope() {
        var first = new RingworldChunkGeneratorTest.FixtureWorld();
        var second = new RingworldChunkGeneratorTest.FixtureWorld();
        var biomes = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), new SpaceBiome());
        var outside = new BlockPos(0, 0, -8193);
        try (var outer = RingworldGenerationScope.open(first, biomes)) {
            assertFalse(RingworldGenerationScope.suppresses(second, outside));
            try (var inner = RingworldGenerationScope.open(second, biomes)) {
                assertTrue(RingworldGenerationScope.suppresses(first, outside));
                assertTrue(RingworldGenerationScope.suppresses(second, outside));
            }
            assertFalse(RingworldGenerationScope.suppresses(second, outside));
            assertTrue(RingworldGenerationScope.suppresses(first, outside));
        }
        assertFalse(RingworldGenerationScope.suppresses(first, outside));
    }

    @Test public void outOfOrderCloseFailsWithoutLosingOuterContext() {
        var world = new RingworldChunkGeneratorTest.FixtureWorld();
        var biomes = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), new SpaceBiome());
        try (var outer = RingworldGenerationScope.open(world, biomes)) {
            try (var inner = RingworldGenerationScope.open(world, biomes)) {
                assertThrows(IllegalStateException.class, outer::close);
                assertTrue(RingworldGenerationScope.suppresses(world, new BlockPos(0, 0, 8192)));
            }
        }
        assertFalse(RingworldGenerationScope.suppresses(world, new BlockPos(0, 0, 8192)));
    }
}
