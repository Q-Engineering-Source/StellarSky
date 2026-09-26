package stellarium.client.ring.cloud;

import java.util.Arrays;
import java.util.Objects;

/** Non-periodic world-coordinate cloud field with one canonical eight-sample volume per XZ column. */
public final class CloudWorldField {
    private static final int CALIBRATION_SAMPLES = 4_096;
    private static final long CALIBRATION_X = -2_394_857L, CALIBRATION_Z = 7_154_091L;
    private static final double[] REGIONAL_WAVELENGTHS = {3_072, 12_288, 49_152, 196_608, 786_432, 3_145_728};
    private static final double[] REGIONAL_AMPLITUDES = {.42, .27, .19, .12, .07, .04};
    private static final long[] REGIONAL_SALTS = {0L, 0xD1B54A32D192ED03L, 0x94D049BB133111EBL,
            0xBF58476D1CE4E5B9L, 0x369DEA0F31A53F85L, 0xDB4F0B9175AE2165L};
    private static final double[] DETAIL_XZ = {48, 96, 192, 384, 768, 1_536};
    private static final double[] DETAIL_Y = {12, 16, 24, 32, 48, 64};
    private static final double[] DETAIL_AMPLITUDES = {.14, .11, .085, .065, .045, .03};
    private static final double REGIONAL_NORMALIZER = 1.11D;
    private static final double DETAIL_NORMALIZER = .475D;
    private static final int FILTER_CLASSES = 13;
    private static final double FILTER_BASE_CELL = 192.0D;
    private static final ThreadLocal<SamplerHolder> FACADE_SAMPLER = new ThreadLocal<>();

    private CloudWorldField() { }

    /** Creates one immutable, seed-aware sampler. Cache generation should reuse it for all columns. */
    static Sampler sampler(CloudFieldSettings settings) { return new Sampler(settings); }

    public static boolean occupied(CloudFieldSettings settings, long cellX, int layer, long cellZ,
                                   int layers, double cellSizeBlocks) {
        requireGrid(settings, layers, cellSizeBlocks);
        if (layer < 0 || layer >= layers) return false;
        CloudColumn column = new CloudColumn();
        if (layers == 1) facade(settings).fillFilteredColumn(cellX * cellSizeBlocks, cellZ * cellSizeBlocks, cellSizeBlocks, column);
        else facade(settings).fillColumn(cellX * cellSizeBlocks, cellZ * cellSizeBlocks, column);
        int[] reduced = new int[layers];
        reduceInto(layers, column, reduced);
        return alpha(reduced[layer]) != 0;
    }

    public static int argb(CloudFieldSettings settings, long cellX, int layer, long cellZ,
                           int layers, double cellSizeBlocks) {
        requireGrid(settings, layers, cellSizeBlocks);
        if (layer < 0 || layer >= layers) return 0;
        CloudColumn column = new CloudColumn();
        if (layers == 1) facade(settings).fillFilteredColumn(cellX * cellSizeBlocks, cellZ * cellSizeBlocks, cellSizeBlocks, column);
        else facade(settings).fillColumn(cellX * cellSizeBlocks, cellZ * cellSizeBlocks, column);
        int[] reduced = new int[layers];
        reduceInto(layers, column, reduced);
        return reduced[layer];
    }

    /** Backward-compatible facade: coverage now means final canonical-column union, not middle-layer fill. */
    static boolean coveredAt(CloudFieldSettings settings, double worldX, double worldZ) {
        CloudColumn column = new CloudColumn();
        facade(settings).fillColumn(worldX, worldZ, column);
        return any(column);
    }

    static boolean coveredAt(CloudFieldSettings settings, double worldX, double worldZ, double footprintCellSize) {
        Objects.requireNonNull(settings, "settings");
        if (!Double.isFinite(footprintCellSize) || footprintCellSize <= 0.0D) throw new IllegalArgumentException("invalid cloud footprint");
        CloudColumn column = new CloudColumn();
        facade(settings).fillFilteredColumn(worldX, worldZ, footprintCellSize, column);
        return any(column);
    }

    public static double threshold(CloudFieldSettings settings) { return facade(settings).threshold(); }

    static void reduceInto(int targetLayers, CloudColumn source, int[] output) {
        if ((targetLayers != 1 && targetLayers != 2 && targetLayers != 4 && targetLayers != 8) || output.length < targetLayers) throw new IllegalArgumentException("cloud reduction target must divide canonical eight layers");
        Arrays.fill(output, 0);
        int strongestGroup = -1, strongestCount = -1, strongestBrightness = -1;
        for (int group = 0; group < targetLayers; group++) {
            int start = group * CloudColumn.LAYERS / targetLayers;
            int end = (group + 1) * CloudColumn.LAYERS / targetLayers;
            int count = 0, red = 0, green = 0, blue = 0, brightness = 0;
            for (int index = start; index < end; index++) {
                int color = source.argb[index];
                if (alpha(color) == 0) continue;
                count++; red += color >>> 16 & 255; green += color >>> 8 & 255; blue += color & 255;
                brightness += color >>> 16 & 255;
            }
            if (count > strongestCount || count == strongestCount && brightness > strongestBrightness) {
                strongestGroup = group; strongestCount = count; strongestBrightness = brightness;
            }
            if (count * 2 >= end - start && count != 0) output[group] = opaque(red / count, green / count, blue / count);
        }
        if (any(source) && !any(output)) {
            int start = strongestGroup * CloudColumn.LAYERS / targetLayers;
            int end = (strongestGroup + 1) * CloudColumn.LAYERS / targetLayers;
            int count = 0, red = 0, green = 0, blue = 0;
            for (int index = start; index < end; index++) if (alpha(source.argb[index]) != 0) {
                int color = source.argb[index]; count++; red += color >>> 16 & 255; green += color >>> 8 & 255; blue += color & 255;
            }
            output[strongestGroup] = opaque(red / count, green / count, blue / count);
        }
    }

    static final class Sampler {
        private final CloudFieldSettings settings;
        private final double threshold;
        private final double[] filteredThresholds;

        private Sampler(CloudFieldSettings settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
            this.threshold = settings.coverage() == 0.0D ? Double.POSITIVE_INFINITY
                    : settings.coverage() == 1.0D ? Double.NEGATIVE_INFINITY : calibrate();
            this.filteredThresholds = settings.coverage() == 0.0D || settings.coverage() == 1.0D ? null : calibrateFilters();
        }

        double threshold() { return threshold; }

        void fillColumn(double x, double z, CloudColumn output) {
            fill(x, z, -1, output);
        }

        /** Bounded far proxy: filters invisible horizontal frequencies and uses its matching fixed calibration class. */
        void fillFilteredColumn(double x, double z, double physicalCellWidth, CloudColumn output) {
            if (!Double.isFinite(physicalCellWidth) || physicalCellWidth <= 0.0D) throw new IllegalArgumentException("invalid cloud footprint");
            fill(x, z, physicalCellWidth < FILTER_BASE_CELL ? -1 : filterClass(physicalCellWidth), output);
        }

        private void fill(double x, double z, int filterClass, CloudColumn output) {
            if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("cloud world coordinates must be finite");
            Arrays.fill(output.argb, 0);
            if (settings.coverage() == 0.0D) return;
            if (settings.coverage() == 1.0D) { Arrays.fill(output.argb, 0xE0FFFFFF); return; }
            fillContext(x, z, filterClass, output);
            double maximum = Double.NEGATIVE_INFINITY;
            for (int layer = 0; layer < CloudColumn.LAYERS; layer++) {
                double score = score(output, layer);
                output.scores[layer] = score;
                maximum = Math.max(maximum, score);
            }
            double selectedThreshold = filterClass < 0 ? threshold : filteredThresholds[filterClass];
            if (maximum < selectedThreshold) return;
            for (int layer = 0; layer < CloudColumn.LAYERS; layer++) if (output.scores[layer] >= selectedThreshold) {
                int brightness = clampByte((int) StrictMath.round(190.0D + 65.0D * clamp01((output.scores[layer] + 1.0D) * .5D)));
                output.argb[layer] = opaque(brightness, brightness, brightness);
            }
            if (settings.layers() < CloudColumn.LAYERS) quantizeLayers(output, settings.layers());
        }

        private double calibrate() {
            double[] maxima = new double[CALIBRATION_SAMPLES];
            CloudColumn scratch = new CloudColumn();
            for (int index = 0; index < maxima.length; index++) {
                double x = (CALIBRATION_X + 3_071L * index + 97L * (index * (long) index % 8_191L)) * 12.0D;
                double z = (CALIBRATION_Z - 5_003L * index + 131L * (index * (long) index % 6_151L)) * 12.0D;
                fillContext(x, z, -1, scratch); double maximum = Double.NEGATIVE_INFINITY;
                for (int layer = 0; layer < CloudColumn.LAYERS; layer++) maximum = Math.max(maximum, score(scratch, layer));
                maxima[index] = maximum;
            }
            Arrays.sort(maxima);
            double rank = (1.0D - settings.coverage()) * (maxima.length - 1.0D);
            int lower = (int) StrictMath.floor(rank), upper = Math.min(maxima.length - 1, lower + 1);
            return lerp(maxima[lower], maxima[upper], rank - lower);
        }

        private double[] calibrateFilters() {
            double[] result = new double[FILTER_CLASSES];
            CloudColumn scratch = new CloudColumn();
            for (int filter = 0; filter < result.length; filter++) {
                double[] maxima = new double[CALIBRATION_SAMPLES];
                for (int index = 0; index < maxima.length; index++) {
                    double x = (CALIBRATION_X + 3_071L * index + 97L * (index * (long) index % 8_191L)) * 12.0D;
                    double z = (CALIBRATION_Z - 5_003L * index + 131L * (index * (long) index % 6_151L)) * 12.0D;
                    fillContext(x, z, filter, scratch); double maximum = Double.NEGATIVE_INFINITY;
                    for (int layer = 0; layer < CloudColumn.LAYERS; layer++) maximum = Math.max(maximum, score(scratch, layer));
                    maxima[index] = maximum;
                }
                Arrays.sort(maxima);
                double rank = (1.0D - settings.coverage()) * (maxima.length - 1.0D);
                int lower = (int) StrictMath.floor(rank), upper = Math.min(maxima.length - 1, lower + 1);
                result[filter] = lerp(maxima[lower], maxima[upper], rank - lower);
            }
            return result;
        }

        private void fillContext(double x, double z, int filterClass, CloudColumn output) {
            double minimum = filterClass < 0 ? 0.0D : 4.0D * filterWidth(filterClass);
            double warpX = 0.0D, warpZ = 0.0D;
            if (49_152.0D >= minimum) {
                warpX = perlin(settings.seed() ^ 0x9E3779B97F4A7C15L, x / 49_152.0D, 0.0D, z / 49_152.0D) * 2_048.0D;
                warpZ = perlin(settings.seed() ^ 0xC2B2AE3D27D4EB4FL, x / 49_152.0D, 7.0D, z / 49_152.0D) * 2_048.0D;
            }
            double wx = x + warpX, wz = z + warpZ, regional = 0.0D, regionalWeight = 0.0D;
            for (int index = 0; index < REGIONAL_WAVELENGTHS.length; index++) if (REGIONAL_WAVELENGTHS[index] >= minimum) {
                regional += REGIONAL_AMPLITUDES[index] * perlin(settings.seed() ^ REGIONAL_SALTS[index], wx / REGIONAL_WAVELENGTHS[index], .25D + index * .5D, wz / REGIONAL_WAVELENGTHS[index]);
                regionalWeight += REGIONAL_AMPLITUDES[index];
            }
            if (regionalWeight == 0.0D) throw new IllegalArgumentException("cloud footprint filters every regional wave");
            output.worldX = x; output.worldZ = z; output.sampleX = wx; output.sampleZ = wz; output.filterMinimum = minimum; output.regional = clamp(regional / regionalWeight, -1.0D, 1.0D);
            output.centerNoise = 384.0D >= minimum ? perlin(settings.seed() ^ 0x632BE59BD9B4E019L, wx / 384.0D, .5D, wz / 384.0D) : 0.0D;
            output.halfNoise = 768.0D >= minimum ? perlin(settings.seed() ^ 0x8CB92BA72F3D8DD7L, wx / 768.0D, .75D, wz / 768.0D) : 0.0D;
            output.crack = settings.worleyEnabled() && 96.0D >= minimum ? crack(settings.seed(), wx, wz) : 0.0D;
        }

        private double score(CloudColumn context, int layer) {
            double u = (layer + .5D) / CloudColumn.LAYERS;
            double center = .5D + .16D * context.centerNoise;
            double half = .28D + .06D * context.halfNoise;
            double envelope = clamp(1.0D - sq((u - center) / half), -1.0D, 1.0D);
            double detail = 0.0D, detailWeight = 0.0D;
            double minimum = context.filterMinimum;
            for (int index = 0; index < DETAIL_XZ.length; index++) if (DETAIL_XZ[index] >= minimum) { detail += DETAIL_AMPLITUDES[index] * perlin(settings.seed() + 0x632BE59BD9B4E019L * (index + 1L),
                    context.sampleX / DETAIL_XZ[index], (u * 32.0D) / DETAIL_Y[index], context.sampleZ / DETAIL_XZ[index]);
                detailWeight += DETAIL_AMPLITUDES[index]; }
            detail = detailWeight == 0.0D ? 0.0D : detail / detailWeight;
            return .58D * context.regional + .24D * envelope + .50D * detail - .30D * settings.erosion() * context.crack;
        }

        private static int filterClass(double width) { int result = 0; double ceiling = FILTER_BASE_CELL; while (result < FILTER_CLASSES - 1 && width > ceiling) { ceiling *= 2.0D; result++; } return result; }
        private static double filterWidth(int filter) { return FILTER_BASE_CELL * (1L << filter); }
    }

    /**
     * Keep the atlas ABI while removing internal physical height steps. Equal colors and
     * occupancy throughout each group let the shell builder eliminate internal faces and
     * merge its side walls. Column-union coverage uses the existing reduction contract.
     */
    static void quantizeLayers(CloudColumn column, int layers) {
        CloudFieldSettings.requireLayers(layers);
        reduceInto(layers, column, column.reduced);
        for (int layer = 0; layer < CloudColumn.LAYERS; layer++) {
            column.argb[layer] = column.reduced[layer * layers / CloudColumn.LAYERS];
        }
    }

    private record SamplerHolder(CloudFieldSettings settings, Sampler sampler) { }
    private static Sampler facade(CloudFieldSettings settings) {
        SamplerHolder holder = FACADE_SAMPLER.get();
        if (holder == null || !holder.settings.equals(settings)) { holder = new SamplerHolder(settings, new Sampler(settings)); FACADE_SAMPLER.set(holder); }
        return holder.sampler;
    }
    private static boolean any(CloudColumn column) { for (int value : column.argb) if (alpha(value) != 0) return true; return false; }
    private static boolean any(int[] values) { for (int value : values) if (alpha(value) != 0) return true; return false; }
    private static int alpha(int color) { return color >>> 24 & 255; }
    private static int opaque(int r, int g, int b) { return 0xE0000000 | r << 16 | g << 8 | b; }
    private static double crack(long seed, double x, double z) {
        long gx = floor(x / 384.0D), gz = floor(z / 384.0D); double first = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
        for (long dz = -2; dz <= 2; dz++) for (long dx = -2; dx <= 2; dx++) {
            long hash = mix(seed ^ mix(gx + dx) ^ Long.rotateLeft(mix(gz + dz), 29));
            double fx = (gx + dx + unit(hash)) * 384.0D, fz = (gz + dz + unit(Long.rotateLeft(hash, 17))) * 384.0D;
            double distance = Math.hypot(x - fx, z - fz);
            if (distance < first) { second = first; first = distance; } else if (distance < second) second = distance;
        }
        double gap = clamp01((second - first) / 192.0D);
        return 1.0D - smoothstep(.05D, .65D, gap);
    }
    private static double perlin(long seed, double x, double y, double z) {
        long x0 = floor(x), y0 = floor(y), z0 = floor(z); double fx = x - x0, fy = y - y0, fz = z - z0;
        double u = fade(fx), v = fade(fy), w = fade(fz);
        double a = lerp(grad(seed,x0,y0,z0,fx,fy,fz), grad(seed,x0+1,y0,z0,fx-1,fy,fz),u);
        double b = lerp(grad(seed,x0,y0+1,z0,fx,fy-1,fz), grad(seed,x0+1,y0+1,z0,fx-1,fy-1,fz),u);
        double c = lerp(grad(seed,x0,y0,z0+1,fx,fy,fz-1), grad(seed,x0+1,y0,z0+1,fx-1,fy,fz-1),u);
        double d = lerp(grad(seed,x0,y0+1,z0+1,fx,fy-1,fz-1), grad(seed,x0+1,y0+1,z0+1,fx-1,fy-1,fz-1),u);
        return lerp(lerp(a,b,v), lerp(c,d,v),w) * .9649214149D;
    }
    private static double grad(long seed,long x,long y,long z,double dx,double dy,double dz) { long h=mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),43)); return switch ((int)h&15) { case 0->dx+dy;case 1->-dx+dy;case 2->dx-dy;case 3->-dx-dy;case 4->dx+dz;case 5->-dx+dz;case 6->dx-dz;case 7->-dx-dz;case 8->dy+dz;case 9->-dy+dz;case 10->dy-dz;case 11->-dy-dz;case 12->dx+dy;case 13->-dx+dy;case 14->-dy+dz;default->-dy-dz;}; }
    private static long floor(double value) { if (!Double.isFinite(value)||value<Long.MIN_VALUE||value>=Long.MAX_VALUE) throw new IllegalArgumentException("cloud world coordinate is outside long lattice range"); return (long)Math.floor(value); }
    private static long mix(long value) { value^=value>>>30; value*=0xBF58476D1CE4E5B9L; value^=value>>>27; value*=0x94D049BB133111EBL; return value^value>>>31; }
    private static double unit(long hash) { return (hash>>>11)*0x1.0p-53; }
    private static double fade(double value) { return value*value*value*(value*(value*6.0D-15.0D)+10.0D); }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static double clamp(double value,double low,double high) { return Math.max(low,Math.min(high,value)); }
    private static double clamp01(double value) { return clamp(value,0.0D,1.0D); }
    private static double sq(double value) { return value*value; }
    private static double smoothstep(double low, double high, double value) { double t = clamp01((value - low) / (high - low)); return t * t * (3.0D - 2.0D * t); }
    private static int clampByte(int value) { return Math.max(0,Math.min(255,value)); }
    private static void requireGrid(CloudFieldSettings settings, int layers, double cellSizeBlocks) {
        Objects.requireNonNull(settings, "settings");
        if ((layers != 1 && layers != 2 && layers != 4 && layers != 8) || !Double.isFinite(cellSizeBlocks) || cellSizeBlocks <= 0.0D) {
            throw new IllegalArgumentException("cloud layers must be 1, 2, 4, or 8 with positive finite cell size");
        }
    }
}
