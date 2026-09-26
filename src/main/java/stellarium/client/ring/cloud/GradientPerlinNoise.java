package stellarium.client.ring.cloud;

/**
 * Project-owned seeded 3D gradient noise: each lattice corner contributes a
 * gradient-dot displacement, and all three axes use quintic interpolation.
 * X/Z lattice identities repeat exactly; Y deliberately remains volumetric.
 */
final class GradientPerlinNoise {
    private GradientPerlinNoise() {
    }

    static double tileable(long seed, double x, double y, double z, int xPeriod, int zPeriod) {
        if (xPeriod <= 0 || zPeriod <= 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Gradient Perlin coordinates and X/Z periods must be finite and positive");
        }
        int x0 = floor(x);
        int y0 = floor(y);
        int z0 = floor(z);
        double localX = x - x0;
        double localY = y - y0;
        double localZ = z - z0;
        double u = quintic(localX);
        double v = quintic(localY);
        double w = quintic(localZ);

        double n000 = gradientDot(seed, x0, y0, z0, xPeriod, zPeriod, localX, localY, localZ);
        double n100 = gradientDot(seed, x0 + 1, y0, z0, xPeriod, zPeriod, localX - 1.0D, localY, localZ);
        double n010 = gradientDot(seed, x0, y0 + 1, z0, xPeriod, zPeriod, localX, localY - 1.0D, localZ);
        double n110 = gradientDot(seed, x0 + 1, y0 + 1, z0, xPeriod, zPeriod, localX - 1.0D, localY - 1.0D, localZ);
        double n001 = gradientDot(seed, x0, y0, z0 + 1, xPeriod, zPeriod, localX, localY, localZ - 1.0D);
        double n101 = gradientDot(seed, x0 + 1, y0, z0 + 1, xPeriod, zPeriod, localX - 1.0D, localY, localZ - 1.0D);
        double n011 = gradientDot(seed, x0, y0 + 1, z0 + 1, xPeriod, zPeriod, localX, localY - 1.0D, localZ - 1.0D);
        double n111 = gradientDot(seed, x0 + 1, y0 + 1, z0 + 1, xPeriod, zPeriod,
                localX - 1.0D, localY - 1.0D, localZ - 1.0D);
        return lerp(lerp(lerp(n000, n100, u), lerp(n010, n110, u), v),
                lerp(lerp(n001, n101, u), lerp(n011, n111, u), v), w);
    }

    private static double gradientDot(long seed, int x, int y, int z, int xPeriod, int zPeriod,
                                      double dx, double dy, double dz) {
        long mixed = mix(seed, Math.floorMod(x, xPeriod), y, Math.floorMod(z, zPeriod));
        return switch ((int) (mixed & 15L)) {
            case 0 -> dx + dy;
            case 1 -> -dx + dy;
            case 2 -> dx - dy;
            case 3 -> -dx - dy;
            case 4 -> dx + dz;
            case 5 -> -dx + dz;
            case 6 -> dx - dz;
            case 7 -> -dx - dz;
            case 8 -> dy + dz;
            case 9 -> -dy + dz;
            case 10 -> dy - dz;
            case 11 -> -dy - dz;
            case 12 -> dx + dy;
            case 13 -> -dy + dz;
            case 14 -> dx - dz;
            default -> -dx - dy;
        };
    }

    static long mix(long seed, int x, int y, int z) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) y * 0xC2B2AE3D27D4EB4FL
                ^ (long) z * 0x165667B19E3779F9L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static double quintic(double value) {
        return value * value * value * (value * (value * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }
}
