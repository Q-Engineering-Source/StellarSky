package stellarium.world.ring.terrain;

import java.util.Objects;
import java.util.Random;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraft.world.gen.NoiseGeneratorPerlin;

/** The first vanilla density column only; no World, chunk, biome provider or shared scratch is touched here. */
public final class OverworldColumnSampler {
    private OverworldColumnSampler() {}

    /** Replays the vanilla Overworld constructor's noise allocation order with a private Random. */
    public record Noises(NoiseGeneratorOctaves minimum, NoiseGeneratorOctaves maximum,
                         NoiseGeneratorOctaves main, NoiseGeneratorOctaves depth) {
        public Noises {
            Objects.requireNonNull(minimum); Objects.requireNonNull(maximum);
            Objects.requireNonNull(main); Objects.requireNonNull(depth);
        }
        public static Noises fromSeed(long seed) {
            var random = new Random(seed);
            var minimum = new NoiseGeneratorOctaves(random, 16);
            var maximum = new NoiseGeneratorOctaves(random, 16);
            var main = new NoiseGeneratorOctaves(random, 8);
            new NoiseGeneratorPerlin(random, 4);
            new NoiseGeneratorOctaves(random, 10);
            var depth = new NoiseGeneratorOctaves(random, 16);
            return new Noises(minimum, maximum, main, depth);
        }
    }

    /** A value copy of exactly the generation settings read by ChunkGeneratorOverworld.generateHeightmap. */
    public record Settings(float depthNoiseScaleX, float depthNoiseScaleZ, float depthNoiseScaleExponent,
                           float coordinateScale, float heightScale, float mainNoiseScaleX,
                           float mainNoiseScaleY, float mainNoiseScaleZ, float biomeDepthOffset,
                           float biomeDepthWeight, float biomeScaleOffset, float biomeScaleWeight,
                           float baseSize, float stretchY, float lowerLimitScale, float upperLimitScale) {
        public static Settings capture(ChunkGeneratorSettings source) {
            Objects.requireNonNull(source);
            return new Settings(source.depthNoiseScaleX, source.depthNoiseScaleZ,
                    source.depthNoiseScaleExponent, source.coordinateScale, source.heightScale,
                    source.mainNoiseScaleX, source.mainNoiseScaleY, source.mainNoiseScaleZ,
                    source.biomeDepthOffSet, source.biomeDepthWeight, source.biomeScaleOffset,
                    source.biomeScaleWeight, source.baseSize, source.stretchY,
                    source.lowerLimitScale, source.upperLimitScale);
        }
    }

    /** The 5x5 biome scalar neighborhood used by the first density column, frozen on the server owner. */
    public static final class Biomes {
        private final float[] base, variation;
        public Biomes(float[] base, float[] variation) {
            if (base.length != 25 || variation.length != 25) throw new IllegalArgumentException("Invalid biome neighborhood");
            this.base = base.clone(); this.variation = variation.clone();
            for (int i = 0; i < 25; i++)
                if (!Float.isFinite(this.base[i]) || !Float.isFinite(this.variation[i]))
                    throw new IllegalArgumentException("Nonfinite biome scalar");
        }
        public static Biomes fromVanillaWindow(Biome[] window) {
            if (window == null || window.length < 100) throw new IllegalArgumentException("Invalid vanilla biome window");
            var base = new float[25]; var variation = new float[25];
            for (int z = 0; z < 5; z++) for (int x = 0; x < 5; x++) {
                var biome = Objects.requireNonNull(window[x + z * 10]);
                base[x + z * 5] = biome.getBaseHeight();
                variation[x + z * 5] = biome.getHeightVariation();
            }
            return new Biomes(base, variation);
        }
    }

    public static final class Context {
        private final Noises noises;
        private final Settings settings;
        private final float[] weights;
        private final int seaLevel;
        private final boolean amplified;
        public Context(Noises noises, Settings settings, float[] weights, int seaLevel, boolean amplified) {
            this.noises = Objects.requireNonNull(noises);
            this.settings = Objects.requireNonNull(settings);
            if (weights.length != 25 || seaLevel < 0 || seaLevel > 256)
                throw new IllegalArgumentException("Invalid column context");
            this.weights = weights.clone(); this.seaLevel = seaLevel; this.amplified = amplified;
            for (float weight : this.weights) if (!Float.isFinite(weight))
                throw new IllegalArgumentException("Nonfinite biome weight");
        }

        /** Must be called only by the worker owning {@code noises}. */
        public SeedTerrainTile.Column sample(int chunkX, int chunkZ, Biomes biomes) {
            Objects.requireNonNull(biomes);
            int x = Math.multiplyExact(chunkX, 4), z = Math.multiplyExact(chunkZ, 4);
            var depth = noises.depth.generateNoiseOctaves(null, x, z, 1, 1,
                    settings.depthNoiseScaleX, settings.depthNoiseScaleZ, settings.depthNoiseScaleExponent);
            float coordinate = settings.coordinateScale, height = settings.heightScale;
            var main = noises.main.generateNoiseOctaves(null, x, 0, z, 1, 33, 1,
                    (double)(coordinate / settings.mainNoiseScaleX),
                    (double)(height / settings.mainNoiseScaleY),
                    (double)(coordinate / settings.mainNoiseScaleZ));
            var minimum = noises.minimum.generateNoiseOctaves(null, x, 0, z, 1, 33, 1,
                    coordinate, height, coordinate);
            var maximum = noises.maximum.generateNoiseOctaves(null, x, 0, z, 1, 33, 1,
                    coordinate, height, coordinate);

            float weightedScale = 0, weightedDepth = 0, totalWeight = 0;
            float centerBase = biomes.base[12];
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                int index = dx + 2 + (dz + 2) * 5;
                float base = settings.biomeDepthOffset + biomes.base[index] * settings.biomeDepthWeight;
                float scale = settings.biomeScaleOffset + biomes.variation[index] * settings.biomeScaleWeight;
                if (amplified && base > 0) { base = 1F + base * 2F; scale = 1F + scale * 4F; }
                float weight = weights[index] / (base + 2F);
                if (biomes.base[index] > centerBase) weight /= 2F;
                weightedScale += scale * weight;
                weightedDepth += base * weight;
                totalWeight += weight;
            }
            weightedScale /= totalWeight; weightedDepth /= totalWeight;
            weightedScale = weightedScale * 0.9F + 0.1F;
            weightedDepth = (weightedDepth * 4F - 1F) / 8F;

            double depthShift = depth[0] / 8000D;
            if (depthShift < 0D) depthShift = -depthShift * 0.3D;
            depthShift = depthShift * 3D - 2D;
            if (depthShift < 0D) {
                depthShift /= 2D;
                if (depthShift < -1D) depthShift = -1D;
                depthShift /= 1.4D;
                depthShift /= 2D;
            } else {
                if (depthShift > 1D) depthShift = 1D;
                depthShift /= 8D;
            }
            double offset = weightedDepth + depthShift * 0.2D;
            double scale = weightedScale;
            offset = offset * settings.baseSize / 8D;
            double baseHeight = settings.baseSize + offset * 4D;
            var density = new double[33];
            for (int y = 0; y < 33; y++) {
                double vertical = (y - baseHeight) * settings.stretchY * 128D / 256D / scale;
                if (vertical < 0D) vertical *= 4D;
                double low = minimum[y] / settings.lowerLimitScale;
                double high = maximum[y] / settings.upperLimitScale;
                double blend = (main[y] / 10D + 1D) / 2D;
                double value = MathHelper.clampedLerp(low, high, blend) - vertical;
                if (y > 29) {
                    double top = (double)((y - 29) / 3F);
                    value = value * (1D - top) + -10D * top;
                }
                density[y] = value;
            }
            var surface = surface(density, seaLevel);
            return new SeedTerrainTile.Column(surface.land() ? SeedTerrainTile.Kind.LAND : SeedTerrainTile.Kind.OCEAN,
                    surface.baseGroundTop(), surface.visibleTop());
        }
    }

    static OverworldDensityLattice.Surface surface(double[] density, int seaLevel) {
        if (density.length != 33 || seaLevel < 0 || seaLevel > 256) throw new IllegalArgumentException("Invalid column");
        for (int y = 255; y >= 0; y--) {
            int index = y / 8;
            double value = density[index] + (density[index + 1] - density[index]) * ((y % 8) / 8D);
            if (value > 0D) {
                int ground = y + 1;
                return new OverworldDensityLattice.Surface(ground, Math.max(ground, seaLevel), ground >= seaLevel);
            }
        }
        return new OverworldDensityLattice.Surface(0, seaLevel, 0 >= seaLevel);
    }
}
