package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import java.util.List;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.junit.BeforeClass;
import org.junit.Test;
import stellarium.world.ring.terrain.SeedTerrainSource;
import stellarium.world.ring.terrain.SeedTerrainTile;
import stellarium.world.ring.terrain.OverworldDensitySource;
import stellarium.world.ring.terrain.OverworldDensityLattice;

public class RingworldChunkGeneratorTest {
    private static SpaceBiome space;
    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        space = new SpaceBiome();
        ForgeRegistries.BIOMES.register(space);
    }

    @Test public void exteriorIsAnOrdinaryEmptyWritableChunkWithoutDelegateGeneration() {
        var world = new FixtureWorld();
        var delegate = new CountingGenerator(world);
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), space);
        var generator = RingworldChunkGenerator.wrap(world, delegate, provider, true);
        for (int z : new int[] {-514, -513, 512, 513}) {
            Chunk chunk = generator.generateChunk(0, z);
            assertEquals(Chunk.class, chunk.getClass());
            assertTrue(chunk.isTerrainPopulated());
            for (byte biome : chunk.getBiomeArray()) assertEquals(Biome.getIdForBiome(space), biome & 255);
            assertTrue(chunk.isEmptyBetween(0, 255));
            var pos = new BlockPos(0, 32, z * 16);
            chunk.setBlockState(pos, Blocks.STONE.getDefaultState());
            assertSame(Blocks.STONE, chunk.getBlockState(pos).getBlock());
            generator.populate(0, z);
            assertFalse(generator.generateStructures(chunk, 0, z));
        }
        assertEquals(0, delegate.generated);
        assertEquals(0, delegate.populated);
        assertEquals(0, delegate.structures);
    }

    @Test public void interiorDelegatesAndDisabledModeReturnsOriginalGenerator() {
        var world = new FixtureWorld();
        var delegate = new CountingGenerator(world);
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), space);
        assertSame(delegate, RingworldChunkGenerator.wrap(world, delegate, provider, false));
        var generator = RingworldChunkGenerator.wrap(world, delegate, provider, true);
        for (int z : new int[] {-512, 511}) {
            assertSame(delegate.result, generator.generateChunk(0, z));
            generator.populate(0, z);
            assertTrue(generator.generateStructures(delegate.result, 0, z));
        }
        assertEquals(2, delegate.generated);
        assertEquals(2, delegate.populated);
        assertEquals(2, delegate.structures);
    }

    @Test public void existingStructureReconstructionIsNotErasedByEmptyGenerationPolicy() {
        var world = new FixtureWorld();
        var delegate = new CountingGenerator(world);
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), space);
        var generator = RingworldChunkGenerator.wrap(world, delegate, provider, true);
        generator.recreateStructures(delegate.result, 0, 512);
        assertEquals(1, delegate.recreated);
        assertTrue(generator.getPossibleCreatures(EnumCreatureType.MONSTER, new BlockPos(0, 32, 8192)).isEmpty());
    }

    @Test public void spaceBiomeSuppressesDensityEvenAtInteriorCoordinates() {
        var world = new FixtureWorld();
        var delegate = new CountingGenerator(world);
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(space), space);
        var generator = RingworldChunkGenerator.wrap(world, delegate, provider, true);
        assertTrue(generator.generateChunk(0, 0).isEmptyBetween(0, 255));
        generator.populate(0, 0);
        assertEquals(0, delegate.generated);
        assertEquals(0, delegate.populated);
    }

    @Test public void previewUsesSpaceBiomeAndNeverDelegatesExteriorDensity() {
        var world = new FixtureWorld();
        var delegate = new CountingGenerator(world);
        var provider = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS),space);
        var source = (SeedTerrainSource)RingworldChunkGenerator.wrap(world,delegate,provider,true);
        for(int z:new int[]{-513,512}) assertEquals(SeedTerrainTile.Kind.EMPTY,source.sample(0,z).column(0,0).kind());
        assertEquals(0,delegate.sampled);
        source.sample(0,-512); source.sample(0,511);
        assertEquals(2,delegate.sampled); assertEquals(0,delegate.generated);
        var interiorSpace = (SeedTerrainSource)RingworldChunkGenerator.wrap(world,delegate,
                new RingworldBiomeProvider(new BiomeProviderSingle(space),space),true);
        assertEquals(SeedTerrainTile.Kind.EMPTY,interiorSpace.sample(0,0).column(0,0).kind());
        assertEquals(2,delegate.sampled);
    }

    private static final class CountingGenerator implements IChunkGenerator, OverworldDensitySource {
        private final Chunk result;
        private int generated, populated, structures, recreated, sampled;
        CountingGenerator(World world) { result = new Chunk(world, 0, 0); }
        public Chunk generateChunk(int x, int z) { generated++; return result; }
        public void populate(int x, int z) { populated++; }
        public boolean generateStructures(Chunk chunk, int x, int z) { structures++; return true; }
        public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) { return List.of(); }
        public BlockPos getNearestStructurePos(World world, String type, BlockPos pos, boolean unexplored) { return null; }
        public void recreateStructures(Chunk chunk, int x, int z) { recreated++; }
        public boolean isInsideStructure(World world, String type, BlockPos pos) { return false; }
        public OverworldDensityLattice stellarium$sampleDensity(int x,int z) {
            sampled++; return new OverworldDensityLattice(x,z,63,new double[825]);
        }
    }

    static final class FixtureWorld extends World {
        FixtureWorld() {
            super(null, new WorldInfo(new WorldSettings(1, GameType.SURVIVAL, true, false, WorldType.DEFAULT),
                    "space-fixture"), new FixtureProvider(), new Profiler(), false);
        }
        @Override protected IChunkProvider createChunkProvider() { throw new UnsupportedOperationException("No chunk provider"); }
        @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return false; }
        @Override public long getSeed() { return getWorldInfo().getSeed(); }
        @Override public BiomeProvider getBiomeProvider() { return new BiomeProviderSingle(Biomes.PLAINS); }
    }

    private static final class FixtureProvider extends WorldProvider {
        @Override public DimensionType getDimensionType() { return DimensionType.OVERWORLD; }
    }
}
