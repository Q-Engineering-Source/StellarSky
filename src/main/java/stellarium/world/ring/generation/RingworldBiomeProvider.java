package stellarium.world.ring.generation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import stellarium.world.ring.RingworldStripBounds;
import stellarium.world.ring.terrain.VanillaBiomeFingerprint;

/** Finite strip view; generation-layer coordinates have four-block spacing. */
public final class RingworldBiomeProvider extends BiomeProvider {
    private final BiomeProvider delegate;
    private final SpaceBiome space;

    public RingworldBiomeProvider(BiomeProvider delegate, SpaceBiome space) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.space = Objects.requireNonNull(space, "space");
    }

    SpaceBiome spaceBiome() { return space; }

    public byte[] previewFingerprint() {
        return VanillaBiomeFingerprint.ring(delegate,RingworldStripBounds.BOARD_MIN_Z,
                RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE,Biome.getIdForBiome(space));
    }

    @Override public Biome getBiome(BlockPos pos) {
        return RingworldStripBounds.insideBoard(pos.getZ()) ? delegate.getBiome(pos) : space;
    }

    @Override public Biome getBiome(BlockPos pos, Biome fallback) {
        return RingworldStripBounds.insideBoard(pos.getZ()) ? delegate.getBiome(pos, fallback) : space;
    }

    @Override public Biome[] getBiomesForGeneration(Biome[] reuse, int x, int z, int width, int height) {
        return sample(reuse, x, z, width, height, true, false);
    }

    @Override public Biome[] getBiomes(Biome[] reuse, int x, int z, int width, int height) {
        return getBiomes(reuse, x, z, width, height, true);
    }

    @Override public Biome[] getBiomes(Biome[] reuse, int x, int z, int width, int height, boolean cache) {
        return sample(reuse, x, z, width, height, false, cache);
    }

    private Biome[] sample(Biome[] reuse, int x, int z, int width, int height, boolean generation, boolean cache) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative biome query size");
        int count = Math.multiplyExact(width, height);
        int scale = generation ? 4 : 1;
        int first = (int) Math.clamp((long) RingworldStripBounds.BOARD_MIN_Z / scale - z, 0, height);
        int end = (int) Math.clamp((long) RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE / scale - z, 0, height);
        if (count == 0) return reuse == null ? new Biome[0] : reuse;
        Math.addExact(x, width - 1);
        Math.addExact(z, height - 1);
        if (first == 0 && end == height) {
            return generation ? delegate.getBiomesForGeneration(reuse, x, z, width, height)
                    : delegate.getBiomes(reuse, x, z, width, height, cache);
        }
        Biome[] result = reuse != null && reuse.length >= count ? reuse : new Biome[count];
        Arrays.fill(result, 0, count, space);
        if (first < end) {
            Biome[] interior = generation ? delegate.getBiomesForGeneration(null, x, z + first, width, end - first)
                    : delegate.getBiomes(null, x, z + first, width, end - first, cache);
            System.arraycopy(interior, 0, result, first * width, (end - first) * width);
        }
        return result;
    }

    /** First-release empty generation requires Space boundaries aligned to complete chunks. */
    public boolean isSpaceChunk(int chunkX, int chunkZ) {
        var biomes = getBiomes(null, Math.multiplyExact(chunkX, 16), Math.multiplyExact(chunkZ, 16), 16, 16);
        int spaces = 0;
        for (int i = 0; i < 256; i++) if (SpaceBiome.suppressesNaturalGeneration(biomes[i])) spaces++;
        if (spaces != 0 && spaces != 256) {
            throw new IllegalStateException("Mixed Space chunk is outside the supported generation contract");
        }
        return spaces == 256;
    }

    @Override public boolean areBiomesViable(int x, int z, int radius, List<Biome> allowed) {
        var area = searchArea(x, z, radius);
        if (area.inside()) return delegate.areBiomesViable(x, z, radius, allowed);
        for (var biome : getBiomesForGeneration(null, area.x, area.z, area.width, area.height)) {
            if (!allowed.contains(biome)) return false;
        }
        return true;
    }

    @Override public BlockPos findBiomePosition(int x, int z, int radius, List<Biome> allowed, Random random) {
        var area = searchArea(x, z, radius);
        if (area.inside()) return delegate.findBiomePosition(x, z, radius, allowed, random);
        var biomes = getBiomesForGeneration(null, area.x, area.z, area.width, area.height);
        BlockPos found = null;
        int replacements = 0;
        for (int i = 0; i < area.width * area.height; i++) {
            // Match this version's vanilla selection cadence for boundary-crossing searches.
            if (allowed.contains(biomes[i]) && (found == null || random.nextInt(replacements + 1) == 0)) {
                found = new BlockPos((area.x + i % area.width) * 4, 0, (area.z + i / area.width) * 4);
                replacements++;
            }
        }
        return found;
    }

    private static SearchArea searchArea(int x, int z, int radius) {
        if (radius < 0) throw new IllegalArgumentException("Negative biome search radius");
        int minX = Math.subtractExact(x, radius) >> 2;
        int minZ = Math.subtractExact(z, radius) >> 2;
        int maxX = Math.addExact(x, radius) >> 2;
        int maxZ = Math.addExact(z, radius) >> 2;
        return new SearchArea(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }

    private record SearchArea(int x, int z, int width, int height) {
        boolean inside() {
            return z >= RingworldStripBounds.BOARD_MIN_Z / 4
                    && (long) z + height <= RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE / 4;
        }
    }

    @Override public List<Biome> getBiomesToSpawnIn() { return delegate.getBiomesToSpawnIn(); }
    @Override public float getTemperatureAtHeight(float temperature, int height) {
        return delegate.getTemperatureAtHeight(temperature, height);
    }
    @Override public void cleanupCache() { delegate.cleanupCache(); }
    @Override public boolean isFixedBiome() { return false; }
    @Override public Biome getFixedBiome() { return null; }
}
