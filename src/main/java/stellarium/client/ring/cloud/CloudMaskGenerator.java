package stellarium.client.ring.cloud;

import java.util.Arrays;

/** Deterministic finite cloud field with explicit XZ coverage before 3D density shaping. */
public final class CloudMaskGenerator {
    public static final int DEFAULT_WIDTH = 64;
    public static final int DEFAULT_DEPTH = 64;
    public static final int DEFAULT_LAYERS = 8;
    private static final int OCTAVES = 3;
    private static final double PERSISTENCE = 0.5D;
    private static final int MACRO_LATTICE_PERIOD = 4;
    private static final int SHAPE_LATTICE_PERIOD = 4;

    private CloudMaskGenerator() {
    }

    public static CloudMask defaultMask() {
        return generate(CloudFieldSettings.DEFAULT);
    }

    /** Generates the fixed first-release 64 XZ by 8 layer cloud tile. */
    public static CloudMask generate(CloudFieldSettings settings) {
        return generate(1L, DEFAULT_WIDTH, DEFAULT_DEPTH, DEFAULT_LAYERS, settings, OCTAVES, PERSISTENCE);
    }

    /**
     * Legacy shape-signature compatibility. The retained scalar threshold now
     * maps monotonically to macro coverage. Octaves and persistence are passed
     * through to the actual fBm, while the settings overload remains fixed at
     * the first-release three octaves and .5 persistence.
     */
    public static CloudMask generate(long generation, int width, int depth, int layers, long seed,
                                     int octaves, double persistence, double threshold) {
        if (octaves < 1 || octaves > 4 || !Double.isFinite(persistence) || persistence <= 0.0D || persistence > 1.0D
                || !Double.isFinite(threshold) || threshold <= 0.0D || threshold >= 1.0D) {
            throw new IllegalArgumentException("Invalid legacy cloud field tuning");
        }
        return generate(generation, width, depth, layers,
                new CloudFieldSettings(seed, 1.0D - threshold, false, 0.0D), octaves, persistence);
    }

    static boolean[] footprint(CloudFieldSettings settings) {
        return footprint(DEFAULT_WIDTH, DEFAULT_DEPTH, settings);
    }

    private static CloudMask generate(long generation, int width, int depth, int layers, CloudFieldSettings settings,
                                      int octaves, double persistence) {
        validateDimensions(width, depth, layers, octaves);
        if (settings == null) {
            throw new NullPointerException("settings");
        }
        boolean[] footprint = footprint(width, depth, settings);
        int[] argb = new int[Math.multiplyExact(Math.multiplyExact(width, depth), layers)];
        for (int layer = 0; layer < layers; layer++) {
            double height = StrictMath.sin(Math.PI * (layer + 0.5D) / layers);
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    if (!footprint[z * width + x]) {
                        continue;
                    }
                    // The clamp is a density calibration, not a replacement
                    // mask: the actual 3D gradient fBm shifts each column's
                    // top/bottom threshold. A bell-only formula would make all
                    // selected columns the same six-layer extrusion.
                    double shape = Math.max(-0.40D, Math.min(0.40D,
                            2.50D * fBm(settings.seed(), x, layer, z, width, depth, layers, octaves, persistence)));
                    // Threshold spans [.35, .95]. The bell's two middle layers
                    // remain a coherent core (height about .981), while the
                    // fBm field creates varied L1/L2/L5/L6 cloud thickness.
                    double density = height - (0.65D + 0.75D * shape);
                    if (settings.worleyEnabled()) {
                        double cellular = WorleyNoise.nearestFeature(settings.seed() ^ 0x6A09E667F3BCC909L,
                                x * SHAPE_LATTICE_PERIOD / (double) width,
                                layer * 2.0D / layers,
                                z * SHAPE_LATTICE_PERIOD / (double) depth,
                                SHAPE_LATTICE_PERIOD, SHAPE_LATTICE_PERIOD);
                        // Erode only low-density edges. High middle cores stay
                        // continuous rather than fragmenting layer by layer.
                        density -= settings.erosion() * (1.0D - height) * (1.0D - cellular) * 0.40D;
                    }
                    if (density >= 0.0D) {
                        int brightness = clampByte((int) StrictMath.round(205.0D + 50.0D * density));
                        argb[(layer * depth + z) * width + x] = 0xE0000000
                                | brightness << 16 | brightness << 8 | brightness;
                    }
                }
            }
        }
        return new CloudMask(generation, width, depth, layers, argb);
    }

    private static boolean[] footprint(int width, int depth, CloudFieldSettings settings) {
        boolean[] result = new boolean[Math.multiplyExact(width, depth)];
        int selected = (int) StrictMath.round(settings.coverage() * result.length);
        if (selected == 0) {
            return result;
        }
        if (selected == result.length) {
            Arrays.fill(result, true);
            return result;
        }
        Integer[] rank = new Integer[result.length];
        double[] score = new double[result.length];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                rank[index] = index;
                // Four smooth macro lobes across the tile make contiguous
                // openings rather than independent per-cell coverage noise.
                score[index] = GradientPerlinNoise.tileable(settings.seed() ^ 0xBB67AE8584CAA73BL,
                        x * MACRO_LATTICE_PERIOD / (double) width, 0.375D,
                        z * MACRO_LATTICE_PERIOD / (double) depth,
                        MACRO_LATTICE_PERIOD, MACRO_LATTICE_PERIOD);
            }
        }
        Arrays.sort(rank, (left, right) -> {
            int byScore = Double.compare(score[right], score[left]);
            return byScore != 0 ? byScore : Integer.compare(left, right);
        });
        for (int index = 0; index < selected; index++) {
            result[rank[index]] = true;
        }
        return result;
    }

    private static double fBm(long seed, int x, int layer, int z, int width, int depth, int layers,
                              int octaves, double persistence) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double totalAmplitude = 0.0D;
        for (int octave = 0; octave < octaves; octave++) {
            int period = SHAPE_LATTICE_PERIOD << octave;
            sum += amplitude * GradientPerlinNoise.tileable(seed,
                    x * period / (double) width,
                    layer * (2 << octave) / (double) layers,
                    z * period / (double) depth, period, period);
            totalAmplitude += amplitude;
            amplitude *= persistence;
        }
        return sum / totalAmplitude;
    }

    private static void validateDimensions(int width, int depth, int layers, int octaves) {
        int highestPeriod = SHAPE_LATTICE_PERIOD << (octaves - 1);
        if (width <= 0 || depth <= 0 || layers <= 0 || layers > CloudMask.MAX_LAYERS
                || width % highestPeriod != 0 || depth % highestPeriod != 0) {
            throw new IllegalArgumentException("Cloud field dimensions do not support the fixed tileable fBm");
        }
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
