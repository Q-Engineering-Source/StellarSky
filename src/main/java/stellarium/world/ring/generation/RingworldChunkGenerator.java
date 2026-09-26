package stellarium.world.ring.generation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import stellarium.world.ring.terrain.OverworldDensitySource;
import stellarium.world.ring.terrain.OverworldColumnSampler;
import stellarium.world.ring.terrain.OverworldColumnSource;
import stellarium.world.ring.terrain.OverworldFingerprintSource;
import stellarium.world.ring.terrain.SeedTerrainChunk;
import stellarium.world.ring.terrain.SeedTerrainSource;

/** Omits base density/population for complete Space chunks; disk chunk loading stays in the provider. */
public final class RingworldChunkGenerator implements IChunkGenerator, SeedTerrainSource, OverworldFingerprintSource {
    private final World world;
    private final IChunkGenerator delegate;
    private final RingworldBiomeProvider biomes;
    private final int spaceId;

    public static IChunkGenerator wrap(World world, IChunkGenerator delegate, RingworldBiomeProvider biomes,
                                        boolean enabled) {
        Objects.requireNonNull(delegate, "delegate");
        return enabled ? new RingworldChunkGenerator(world, delegate, biomes) : delegate;
    }

    private RingworldChunkGenerator(World world, IChunkGenerator delegate, RingworldBiomeProvider biomes) {
        this.world = Objects.requireNonNull(world, "world");
        this.delegate = delegate;
        this.biomes = Objects.requireNonNull(biomes, "biomes");
        this.spaceId = Biome.getIdForBiome(biomes.spaceBiome());
        if (spaceId < 0 || spaceId >= 255) {
            throw new IllegalStateException("Space needs a registered biome ID supported by the chunk biome format");
        }
    }

    @Override public Chunk generateChunk(int x, int z) {
        if (!biomes.isSpaceChunk(x, z)) {
            try (var scope = openScope()) { return delegate.generateChunk(x, z); }
        }
        var chunk = new Chunk(world, x, z);
        Arrays.fill(chunk.getBiomeArray(), (byte) spaceId);
        chunk.generateSkylightMap();
        // No natural population remains. The normal Chunk stays writable by players and machines.
        chunk.setTerrainPopulated(true);
        chunk.setLightPopulated(true);
        return chunk;
    }

    @Override public void populate(int x, int z) {
        if (!biomes.isSpaceChunk(x, z)) {
            try (var scope = openScope()) { delegate.populate(x, z); }
        }
    }

    @Override public boolean generateStructures(Chunk chunk, int x, int z) {
        if (biomes.isSpaceChunk(x, z)) return false;
        try (var scope = openScope()) { return delegate.generateStructures(chunk, x, z); }
    }

    @Override public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, BlockPos pos) {
        Biome biome = biomes.getBiome(pos);
        return SpaceBiome.suppressesNaturalGeneration(biome) ? biome.getSpawnableList(type)
                : delegate.getPossibleCreatures(type, pos);
    }

    @Override public void recreateStructures(Chunk chunk, int x, int z) {
        // Preserve loaded structure indexes. A3 must reject NEW starts in MapGenStructure itself.
        try (var scope = openScope()) { delegate.recreateStructures(chunk, x, z); }
    }

    @Override public BlockPos getNearestStructurePos(World world, String type, BlockPos pos, boolean unexplored) {
        try (var scope = openScope()) { return delegate.getNearestStructurePos(world, type, pos, unexplored); }
    }

    @Override public boolean isInsideStructure(World world, String type, BlockPos pos) {
        return delegate.isInsideStructure(world, type, pos);
    }

    public RingworldGenerationScope openScope() { return RingworldGenerationScope.open(world, biomes); }

    @Override public SeedTerrainChunk sample(int x,int z) {
        if(biomes.isSpaceChunk(x,z)) return new SeedTerrainChunk.Empty(x,z);
        if(!(delegate instanceof OverworldDensitySource density)) {
            throw new UnsupportedOperationException("Generator does not provide admitted seed density");
        }
        return new SeedTerrainChunk.Density(density.stellarium$sampleDensity(x,z));
    }

    /** Server-owner snapshot only. The returned values contain no World or biome-provider reference. */
    public OverworldColumnSampler.Context createPreviewColumnContext() {
        if (!(delegate instanceof OverworldColumnSource columns))
            throw new UnsupportedOperationException("Generator does not provide admitted column sampling");
        return columns.stellarium$createColumnContext();
    }

    /** Null denotes a complete Space chunk; no density work is submitted for it. */
    public OverworldColumnSampler.Biomes capturePreviewColumnBiomes(int chunkX,int chunkZ) {
        if (biomes.isSpaceChunk(chunkX,chunkZ)) return null;
        if (!(delegate instanceof OverworldColumnSource columns))
            throw new UnsupportedOperationException("Generator does not provide admitted column sampling");
        return columns.stellarium$captureColumnBiomes(chunkX,chunkZ);
    }

    @Override public byte[] stellarium$capturePreviewFingerprint(byte[] admittedPipelineRevision) {
        if(!(delegate instanceof OverworldFingerprintSource source))
            throw new UnsupportedOperationException("Generator does not provide admitted preview state");
        return source.stellarium$capturePreviewFingerprint(admittedPipelineRevision);
    }
}
