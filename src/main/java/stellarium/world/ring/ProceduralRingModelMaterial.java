package stellarium.world.ring;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Camera-independent, deterministic RGBA8 material inputs for the first procedural far ring.
 *
 * <p>The horizontal coordinate is global ring longitude and always wraps.  The field is evaluated
 * on a circle rather than from a one-dimensional hash, so the underlying continuous model has no
 * artificial longitude seam.  This class creates CPU data only: a renderer owns GL texture upload,
 * cache lifetime, material selection, and all surface/cloud geometry.</p>
 */
public final class ProceduralRingModelMaterial {
    public static final int CHANNELS = 4;
    public static final int MAX_TEXTURE_BYTES = 1_048_576;
    public static final Settings DEFAULT = new Settings(0L, 0.45D, 0.20D, 2_048, 128);

    private static final double TAU = StrictMath.PI * 2.0D;

    private ProceduralRingModelMaterial() {
    }

    /** Builds the two fixed RGBA8 inputs from settings alone. */
    public static Assets generate(Settings settings) {
        Objects.requireNonNull(settings, "settings");
        int texelCount = Math.multiplyExact(settings.width(), settings.height());
        byte[] surface = new byte[Math.multiplyExact(texelCount, CHANNELS)];
        byte[] clouds = new byte[Math.multiplyExact(texelCount, CHANNELS)];
        for (int y = 0; y < settings.height(); y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Procedural ring material generation was interrupted before row " + y);
            }
            double v = (y + 0.5D) / settings.height();
            for (int x = 0; x < settings.width(); x++) {
                double u = (x + 0.5D) / settings.width();
                int offset = (y * settings.width() + x) * CHANNELS;
                write(surface, offset, surfaceAt(settings.seed(), u, v));
                write(clouds, offset, cloudAt(settings, u, v));
            }
        }
        return new Assets(settings, surface, clouds);
    }

    /** Immutable cache key; any value change denotes a different generated material. */
    public record Settings(long seed, double cloudCoverage, double cloudErosion, int width, int height) {
        public Settings {
            if (!Double.isFinite(cloudCoverage) || cloudCoverage < 0.0D || cloudCoverage > 1.0D
                    || !Double.isFinite(cloudErosion) || cloudErosion < 0.0D || cloudErosion > 1.0D) {
                throw new IllegalArgumentException("Cloud coverage and erosion must be finite values within [0, 1]");
            }
            if (width < 2 || height < 2) {
                throw new IllegalArgumentException("Procedural ring material dimensions must both be at least two texels");
            }
            if (width > (MAX_TEXTURE_BYTES / CHANNELS) / height) {
                throw new IllegalArgumentException("Each procedural ring RGBA8 texture is limited to "
                        + MAX_TEXTURE_BYTES + " bytes");
            }
        }
    }

    /**
     * Immutable material byte data.  Array-returning methods make defensive copies; buffer methods
     * expose a newly copied read-only view suitable for an owning uploader.
     */
    public static final class Assets {
        private final Settings settings;
        private final byte[] surfaceRgba8;
        private final byte[] cloudRgba8;

        private Assets(Settings settings, byte[] surfaceRgba8, byte[] cloudRgba8) {
            this.settings = Objects.requireNonNull(settings, "settings");
            int expectedBytes = Math.multiplyExact(Math.multiplyExact(settings.width(), settings.height()), CHANNELS);
            if (surfaceRgba8.length != expectedBytes || cloudRgba8.length != expectedBytes) {
                throw new IllegalArgumentException("Procedural ring material texture byte count does not match settings");
            }
            this.surfaceRgba8 = surfaceRgba8.clone();
            this.cloudRgba8 = cloudRgba8.clone();
        }

        public Settings settings() {
            return settings;
        }

        public int width() {
            return settings.width();
        }

        public int height() {
            return settings.height();
        }

        /** Total bytes of both GPU-ready RGBA8 inputs. */
        public int byteSize() {
            return surfaceRgba8.length + cloudRgba8.length;
        }

        public byte[] surfaceRgba8() {
            return surfaceRgba8.clone();
        }

        public byte[] cloudRgba8() {
            return cloudRgba8.clone();
        }

        public ByteBuffer surfaceRgba8Buffer() {
            return readOnlyCopy(surfaceRgba8);
        }

        public ByteBuffer cloudRgba8Buffer() {
            return readOnlyCopy(cloudRgba8);
        }

        /** Nearest RGBA8 sample with periodic longitude and clamped strip coordinate. */
        public Rgba surfaceAt(double longitude, double stripCoordinate) {
            return sample(surfaceRgba8, width(), height(), longitude, stripCoordinate);
        }

        /** Nearest RGBA8 sample with periodic longitude and clamped strip coordinate. */
        public Rgba cloudAt(double longitude, double stripCoordinate) {
            return sample(cloudRgba8, width(), height(), longitude, stripCoordinate);
        }

        private static ByteBuffer readOnlyCopy(byte[] source) {
            return ByteBuffer.wrap(source.clone()).asReadOnlyBuffer();
        }
    }

    /** Unsigned RGBA8 texel returned without exposing internal material storage. */
    public record Rgba(int red, int green, int blue, int alpha) {
        public Rgba {
            requireChannel(red);
            requireChannel(green);
            requireChannel(blue);
            requireChannel(alpha);
        }
    }

    private static Rgba surfaceAt(long seed, double u, double v) {
        double continental = normalized(periodicFbm(seed ^ 0x6A09E667F3BCC909L, u, v, 2.0D, 5));
        double terrain = normalized(periodicFbm(seed ^ 0xBB67AE8584CAA73BL, u, v, 8.0D, 4));
        double ridge = 1.0D - StrictMath.abs(periodicFbm(seed ^ 0x3C6EF372FE94F82BL, u, v, 18.0D, 3));
        double elevation = clamp01(continental * 0.76D + terrain * 0.18D + ridge * 0.12D);
        double latitude = StrictMath.abs(clamp01(v) * 2.0D - 1.0D);
        if (elevation < 0.515D) {
            double shallow = smoothstep(0.24D, 0.515D, elevation);
            return blend(new Rgba(6, 28, 70, 255), new Rgba(21, 92, 138, 255), shallow);
        }
        double coast = smoothstep(0.515D, 0.59D, elevation);
        Rgba land = blend(new Rgba(183, 164, 103, 255), new Rgba(53, 114, 63, 255), coast);
        double mountain = smoothstep(0.67D, 0.90D, elevation) * (0.35D + ridge * 0.65D);
        land = blend(land, new Rgba(105, 101, 91, 255), mountain);
        double snow = smoothstep(0.70D, 0.94D, elevation) * smoothstep(0.45D, 0.98D, latitude);
        return blend(land, new Rgba(232, 237, 239, 255), snow);
    }

    private static Rgba cloudAt(Settings settings, double u, double v) {
        if (settings.cloudCoverage() == 0.0D) {
            return new Rgba(0, 0, 0, 0);
        }
        if (settings.cloudCoverage() == 1.0D) {
            double tone = normalized(periodicFbm(settings.seed() ^ 0xA54FF53A5F1D36F1L, u, v, 10.0D, 3));
            return blend(new Rgba(205, 214, 221, 255), new Rgba(250, 253, 255, 255), tone);
        }
        double macro = normalized(periodicFbm(settings.seed() ^ 0x510E527FADE682D1L, u, v, 4.0D, 5));
        double detail = normalized(periodicFbm(settings.seed() ^ 0x9B05688C2B3E6C1FL, u, v, 15.0D, 3));
        double shaped = clamp01(macro * (1.0D - settings.cloudErosion() * 0.45D)
                + detail * settings.cloudErosion() * 0.45D);
        double threshold = 1.0D - settings.cloudCoverage();
        double alpha = smoothstep(threshold - 0.10D, threshold + 0.10D, shaped);
        double tone = clamp01(0.42D + detail * 0.58D);
        Rgba color = blend(new Rgba(176, 188, 201, 255), new Rgba(250, 253, 255, 255), tone);
        return new Rgba(color.red(), color.green(), color.blue(), toByte(alpha));
    }

    /** fBm through a circular longitude embedding; u=0 and u=1 are the same model coordinate. */
    private static double periodicFbm(long seed, double u, double v, double baseFrequency, int octaves) {
        double longitude = wrapUnit(u);
        double angle = TAU * longitude;
        double cosine = StrictMath.cos(angle);
        double sine = StrictMath.sin(angle);
        double value = 0.0D;
        double amplitude = 1.0D;
        double amplitudeSum = 0.0D;
        double frequency = baseFrequency;
        for (int octave = 0; octave < octaves; octave++) {
            value += valueNoise(seed + octave * 0x9E3779B97F4A7C15L,
                    cosine * frequency, sine * frequency,
                    (clamp01(v) - 0.5D) * frequency * 2.0D) * amplitude;
            amplitudeSum += amplitude;
            frequency *= 2.0D;
            amplitude *= 0.5D;
        }
        return value / amplitudeSum;
    }

    private static double valueNoise(long seed, double x, double y, double z) {
        int x0 = floor(x);
        int y0 = floor(y);
        int z0 = floor(z);
        double fx = smooth(x - x0);
        double fy = smooth(y - y0);
        double fz = smooth(z - z0);
        double x00 = lerp(lattice(seed, x0, y0, z0), lattice(seed, x0 + 1, y0, z0), fx);
        double x10 = lerp(lattice(seed, x0, y0 + 1, z0), lattice(seed, x0 + 1, y0 + 1, z0), fx);
        double x01 = lerp(lattice(seed, x0, y0, z0 + 1), lattice(seed, x0 + 1, y0, z0 + 1), fx);
        double x11 = lerp(lattice(seed, x0, y0 + 1, z0 + 1), lattice(seed, x0 + 1, y0 + 1, z0 + 1), fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private static double lattice(long seed, int x, int y, int z) {
        long mixed = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xC2B2AE3D27D4EB4FL
                ^ (long) z * 0x165667B19E3779F9L;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CEB9FE1A85EC53L;
        mixed ^= mixed >>> 33;
        return ((mixed >>> 11) * 0x1.0p-53D) * 2.0D - 1.0D;
    }

    private static Rgba sample(byte[] data, int width, int height, double u, double v) {
        requireFiniteCoordinate(u, "longitude");
        requireFiniteCoordinate(v, "strip coordinate");
        int x = (int) StrictMath.floor(wrapUnit(u) * width);
        int y = Math.min(height - 1, (int) StrictMath.floor(clamp01(v) * height));
        int offset = (y * width + x) * CHANNELS;
        return new Rgba(unsigned(data[offset]), unsigned(data[offset + 1]), unsigned(data[offset + 2]),
                unsigned(data[offset + 3]));
    }

    private static void write(byte[] target, int offset, Rgba color) {
        target[offset] = (byte) color.red();
        target[offset + 1] = (byte) color.green();
        target[offset + 2] = (byte) color.blue();
        target[offset + 3] = (byte) color.alpha();
    }

    private static Rgba blend(Rgba from, Rgba to, double amount) {
        return new Rgba(toChannel(lerp(from.red(), to.red(), amount)),
                toChannel(lerp(from.green(), to.green(), amount)),
                toChannel(lerp(from.blue(), to.blue(), amount)),
                toChannel(lerp(from.alpha(), to.alpha(), amount)));
    }

    private static double normalized(double signed) {
        return clamp01(signed * 0.5D + 0.5D);
    }

    private static double smoothstep(double low, double high, double value) {
        if (value <= low) return 0.0D;
        if (value >= high) return 1.0D;
        double amount = (value - low) / (high - low);
        return amount * amount * (3.0D - 2.0D * amount);
    }

    private static double smooth(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double wrapUnit(double value) {
        double wrapped = value - StrictMath.floor(value);
        return wrapped == 1.0D ? 0.0D : wrapped;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * clamp01(amount);
    }

    private static int toByte(double value) {
        return (int) StrictMath.round(clamp01(value) * 255.0D);
    }

    private static int toChannel(double value) {
        return (int) StrictMath.round(Math.max(0.0D, Math.min(255.0D, value)));
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static void requireChannel(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("RGBA8 channel must be within [0, 255]");
        }
    }

    private static void requireFiniteCoordinate(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
