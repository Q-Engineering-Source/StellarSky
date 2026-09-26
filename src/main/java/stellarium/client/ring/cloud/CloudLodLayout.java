package stellarium.client.ring.cloud;

import java.util.List;

/**
 * The single source of truth for the cloud LOD atlas and distance partition.
 *
 * <p>The single RGBA8 atlas is a finite camera-relative cache, never a world
 * period. Pages are packed vertically and sampled only after an explicit
 * absolute-cell bounds check. Shader source must consume {@link #glslDefines()}
 * instead of copying these values.</p>
 */
public final class CloudLodLayout {
    public static final int ATLAS_WIDTH = 512;
    public static final int ATLAS_HEIGHT = 8192;
    public static final int RGBA8_BYTES_PER_TEXEL = 4;
    public static final int ATLAS_BYTE_SIZE = ATLAS_WIDTH * ATLAS_HEIGHT * RGBA8_BYTES_PER_TEXEL;

    // xzScale/yScale are multipliers of the configured finest cell dimensions.
    public static final AtlasLevel LOD0_FINE_3D = new AtlasLevel("LOD0", 256, 256, 8, 0, 1, 1);
    public static final AtlasLevel LOD1_MID_3D = new AtlasLevel("LOD1", 256, 256, 4, 2048, 2, 2);
    public static final AtlasLevel LOD2_LOW_3D = new AtlasLevel("LOD2", 512, 512, 2, 3072, 4, 4);
    public static final AtlasLevel LOD3_VERY_LOW_3D = new AtlasLevel("LOD3", 512, 512, 2, 4096, 8, 4);
    // Far pages only need the finite ring strip in Z.  Depth 128 covers the
    // physical [-8192, 8192) strip plus halo at every 2D scale.
    public static final int TWO_D_DEPTH = 128;
    public static final AtlasLevel LOD4_2D_HIGH = new AtlasLevel("LOD4_2D_HIGH", 512, TWO_D_DEPTH, 1, 5120, 16, 8);
    public static final AtlasLevel LOD5_2D_MID = new AtlasLevel("LOD5_2D_MID", 512, TWO_D_DEPTH, 1, 5248, 32, 8);
    public static final AtlasLevel LOD6_2D_LOW = new AtlasLevel("LOD6_2D_LOW", 512, TWO_D_DEPTH, 1, 5376, 64, 8);
    public static final AtlasLevel LOD7_2D_LOWER = new AtlasLevel("LOD7_2D_LOWER", 512, TWO_D_DEPTH, 1, 5504, 128, 8);
    public static final AtlasLevel LOD8_2D_TAIL = new AtlasLevel("LOD8_2D_TAIL", 512, TWO_D_DEPTH, 1, 5632, 256, 8);
    public static final AtlasLevel LOD9_2D_TAIL = new AtlasLevel("LOD9_2D_TAIL", 512, TWO_D_DEPTH, 1, 5760, 512, 8);
    public static final AtlasLevel LOD10_2D_TAIL = new AtlasLevel("LOD10_2D_TAIL", 512, TWO_D_DEPTH, 1, 5888, 1024, 8);
    public static final AtlasLevel LOD11_2D_TAIL = new AtlasLevel("LOD11_2D_TAIL", 512, TWO_D_DEPTH, 1, 6016, 2048, 8);
    public static final AtlasLevel LOD12_2D_TAIL = new AtlasLevel("LOD12_2D_TAIL", 512, TWO_D_DEPTH, 1, 6144, 4096, 8);

    public static final List<AtlasLevel> THREE_DIMENSIONAL_LEVELS = List.of(
            LOD0_FINE_3D, LOD1_MID_3D, LOD2_LOW_3D, LOD3_VERY_LOW_3D);
    public static final List<AtlasLevel> TWO_DIMENSIONAL_LEVELS = List.of(
            LOD4_2D_HIGH, LOD5_2D_MID, LOD6_2D_LOW, LOD7_2D_LOWER, LOD8_2D_TAIL, LOD9_2D_TAIL,
            LOD10_2D_TAIL, LOD11_2D_TAIL, LOD12_2D_TAIL);
    public static final List<AtlasLevel> ATLAS_LEVELS = List.of(
            LOD0_FINE_3D, LOD1_MID_3D, LOD2_LOW_3D, LOD3_VERY_LOW_3D,
            LOD4_2D_HIGH, LOD5_2D_MID, LOD6_2D_LOW, LOD7_2D_LOWER, LOD8_2D_TAIL, LOD9_2D_TAIL,
            LOD10_2D_TAIL, LOD11_2D_TAIL, LOD12_2D_TAIL);

    public static final double FINE_3D_END_DISTANCE = 512.0D;
    public static final double MID_3D_END_DISTANCE = 2_048.0D;
    public static final double LOW_3D_END_DISTANCE = 8_192.0D;
    public static final double VERY_LOW_3D_END_DISTANCE = 16_384.0D;
    public static final double HIGH_2D_END_DISTANCE = 32_768.0D;
    public static final double MID_2D_END_DISTANCE = 65_536.0D;
    public static final double LOW_2D_END_DISTANCE = 262_144.0D;
    /** Boundary between low and lower 2D pages. */
    public static final double LOW_OBSERVATION_DISTANCE = 131_072.0D;
    /** At this distance the renderer starts the two explicitly cached enlarged pages. */
    public static final double ENLARGE_START_DISTANCE = 262_144.0D;
    public static final int DEFAULT_FAR_DISTANCE_SCALE = 4;
    /** Half-width of the largest tail page at the default 12m finest cell. */
    public static final double TAIL8_END_DISTANCE = 524_288.0D;
    public static final double TAIL9_END_DISTANCE = 1_048_576.0D;
    public static final double TAIL10_END_DISTANCE = 2_097_152.0D;
    public static final double TAIL11_END_DISTANCE = 4_194_304.0D;
    public static final double MAX_CACHED_TAIL_DISTANCE = 8_388_608.0D;

    public static final DistanceTier FINE_3D = new DistanceTier(LOD0_FINE_3D, 0.0D, FINE_3D_END_DISTANCE);
    public static final DistanceTier MID_3D = new DistanceTier(LOD1_MID_3D, FINE_3D_END_DISTANCE, MID_3D_END_DISTANCE);
    public static final DistanceTier LOW_3D = new DistanceTier(LOD2_LOW_3D, MID_3D_END_DISTANCE, LOW_3D_END_DISTANCE);
    public static final DistanceTier VERY_LOW_3D = new DistanceTier(LOD3_VERY_LOW_3D, LOW_3D_END_DISTANCE, VERY_LOW_3D_END_DISTANCE);
    public static final DistanceTier HIGH_2D = new DistanceTier(LOD4_2D_HIGH, VERY_LOW_3D_END_DISTANCE, HIGH_2D_END_DISTANCE);
    public static final DistanceTier MID_2D = new DistanceTier(LOD5_2D_MID, HIGH_2D_END_DISTANCE, MID_2D_END_DISTANCE);
    public static final DistanceTier LOW_2D_NEAR = new DistanceTier(LOD6_2D_LOW,
            MID_2D_END_DISTANCE, LOW_OBSERVATION_DISTANCE);
    public static final DistanceTier LOW_2D_FAR = new DistanceTier(LOD6_2D_LOW,
            LOW_OBSERVATION_DISTANCE, LOW_2D_END_DISTANCE);
    public static final DistanceTier LOWER_2D = new DistanceTier(LOD7_2D_LOWER, LOW_OBSERVATION_DISTANCE, LOW_2D_END_DISTANCE);
    public static final DistanceTier TAIL8_2D = new DistanceTier(LOD8_2D_TAIL, ENLARGE_START_DISTANCE, TAIL8_END_DISTANCE);
    public static final DistanceTier TAIL9_2D = new DistanceTier(LOD9_2D_TAIL, TAIL8_END_DISTANCE, TAIL9_END_DISTANCE);
    public static final DistanceTier TAIL10_2D = new DistanceTier(LOD10_2D_TAIL, TAIL9_END_DISTANCE, TAIL10_END_DISTANCE);
    public static final DistanceTier TAIL11_2D = new DistanceTier(LOD11_2D_TAIL, TAIL10_END_DISTANCE, TAIL11_END_DISTANCE);
    public static final DistanceTier TAIL12_2D = new DistanceTier(LOD12_2D_TAIL, TAIL11_END_DISTANCE, MAX_CACHED_TAIL_DISTANCE);
    public static final List<DistanceTier> DISTANCE_TIERS = List.of(
            FINE_3D, MID_3D, LOW_3D, VERY_LOW_3D, HIGH_2D, MID_2D, LOW_2D_NEAR, LOWER_2D,
            TAIL8_2D, TAIL9_2D, TAIL10_2D, TAIL11_2D, TAIL12_2D);

    static {
        int priorEnd = 0;
        for (AtlasLevel level : ATLAS_LEVELS) {
            if (level.offsetY() < priorEnd || level.endYExclusive() > ATLAS_HEIGHT) {
                throw new ExceptionInInitializerError("Cloud LOD atlas ranges overlap or exceed its physical height");
            }
            priorEnd = level.endYExclusive();
        }
    }

    private CloudLodLayout() {
    }

    /** Validates a renderer-selected power-of-two far distance multiplier. */
    public static int requirePowerOfTwoFarDistanceScale(int scale) {
        if (scale <= 0 || (scale & (scale - 1)) != 0) {
            throw new IllegalArgumentException("Far cloud distance scale must be a positive power of two");
        }
        return scale;
    }

    /**
     * Defines injected before the cloud shaders. The numeric atlas layout and
     * all distance boundaries consequently have exactly one Java owner.
     */
    public static String glslDefines() {
        StringBuilder result = new StringBuilder(1_500);
        appendDefine(result, "SS_CLOUD_ATLAS_WIDTH", ATLAS_WIDTH);
        appendDefine(result, "SS_CLOUD_ATLAS_HEIGHT", ATLAS_HEIGHT);
        for (AtlasLevel level : ATLAS_LEVELS) {
            String prefix = "SS_CLOUD_" + level.glslName();
            appendDefine(result, prefix + "_WIDTH", level.width());
            appendDefine(result, prefix + "_DEPTH", level.depth());
            appendDefine(result, prefix + "_LAYERS", level.layers());
            appendDefine(result, prefix + "_OFFSET_Y", level.offsetY());
            appendDefine(result, prefix + "_XZ_SCALE", level.xzScale());
            appendDefine(result, prefix + "_Y_SCALE", level.yScale());
        }
        appendFloatDefine(result, "SS_CLOUD_LOD_FINE_3D_END", FINE_3D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_MID_3D_END", MID_3D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_LOW_3D_END", LOW_3D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_VERY_LOW_3D_END", VERY_LOW_3D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_HIGH_2D_END", HIGH_2D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_MID_2D_END", MID_2D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_LOW_2D_END", LOW_2D_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_LOW_2D_SPLIT", LOW_OBSERVATION_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_LOW_OBSERVATION_DISTANCE", LOW_OBSERVATION_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_ENLARGE_START", ENLARGE_START_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_TAIL8_END", TAIL8_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_TAIL9_END", TAIL9_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_TAIL10_END", TAIL10_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_LOD_TAIL11_END", TAIL11_END_DISTANCE);
        appendFloatDefine(result, "SS_CLOUD_MAX_CACHED_TAIL_DISTANCE", MAX_CACHED_TAIL_DISTANCE);
        appendDefine(result, "SS_CLOUD_DEFAULT_FAR_DISTANCE_SCALE", DEFAULT_FAR_DISTANCE_SCALE);
        appendFloatDefine(result, "SS_CLOUD_TAIL_EDGE_ERROR_FACTOR", CloudTailCoverage.EDGE_ERROR_FACTOR);
        appendFloatDefine(result, "SS_CLOUD_NUMERIC_TRACE_LIMIT", CloudTailCoverage.NUMERIC_TRACE_LIMIT);
        return result.toString();
    }

    private static void appendDefine(StringBuilder output, String name, int value) {
        output.append("#define ").append(name).append(' ').append(value).append('\n');
    }

    private static void appendFloatDefine(StringBuilder output, String name, double value) {
        output.append("#define ").append(name).append(' ').append(value).append("\n");
    }

    /** One contiguous atlas rectangle, packed as layers of depth rows. */
    public record AtlasLevel(String glslName, int width, int depth, int layers, int offsetY, int xzScale, int yScale) {
        public AtlasLevel {
            if (width <= 0 || width > ATLAS_WIDTH || depth <= 0 || layers <= 0 || offsetY < 0
                    || xzScale <= 0 || yScale <= 0) {
                throw new IllegalArgumentException("Invalid cloud LOD atlas level");
            }
        }

        public int endYExclusive() {
            return offsetY + depth * layers;
        }

        public int atlasY(int layer, int z) {
            if (layer < 0 || layer >= layers || z < 0 || z >= depth) {
                throw new IllegalArgumentException("Cloud LOD atlas coordinate is outside its level");
            }
            return offsetY + layer * depth + z;
        }

        public boolean isThreeDimensional() {
            return layers > 1;
        }
    }

    /** Distance is inclusive at the lower bound and exclusive at the upper bound. */
    public record DistanceTier(AtlasLevel atlasLevel, double minimumDistance, double maximumDistanceExclusive) {
        public DistanceTier {
            if (atlasLevel == null || minimumDistance < 0.0D || maximumDistanceExclusive <= minimumDistance) {
                throw new IllegalArgumentException("Invalid cloud LOD distance tier");
            }
        }

        public boolean contains(double distance) {
            return Double.isFinite(distance) && distance >= minimumDistance && distance < maximumDistanceExclusive;
        }
    }
}
