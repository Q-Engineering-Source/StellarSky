package stellarium.client.ring.cloud;

/** Minimal tileable cellular distance field used only for optional edge erosion. */
final class WorleyNoise {
    private WorleyNoise() {
    }

    static double nearestFeature(long seed, double x, double y, double z, int xPeriod, int zPeriod) {
        // The query's own cell contains a feature within sqrt(3). A cell three
        // or more away on any axis is at least 2 away on that axis, so cannot
        // win; offsets [-2, 2] are the proven sufficient 5x5x5 stencil.
        return normalizedNearestFeature(seed, x, y, z, xPeriod, zPeriod, 2);
    }

    /** Wider enumeration retained solely as a behavioral oracle for the CPU tests. */
    static double nearestFeatureWideReference(long seed, double x, double y, double z, int xPeriod, int zPeriod) {
        return normalizedNearestFeature(seed, x, y, z, xPeriod, zPeriod, 4);
    }

    private static double normalizedNearestFeature(long seed, double x, double y, double z,
                                                   int xPeriod, int zPeriod, int radius) {
        int baseX = floor(x);
        int baseY = floor(y);
        int baseZ = floor(z);
        double nearestSquared = Double.POSITIVE_INFINITY;
        for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
            for (int offsetY = -radius; offsetY <= radius; offsetY++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    int cellX = baseX + offsetX;
                    int cellY = baseY + offsetY;
                    int cellZ = baseZ + offsetZ;
                    long mixed = GradientPerlinNoise.mix(seed, Math.floorMod(cellX, xPeriod), cellY,
                            Math.floorMod(cellZ, zPeriod));
                    double pointX = cellX + unit(mixed);
                    double pointY = cellY + unit(mixed >>> 21);
                    double pointZ = cellZ + unit(mixed >>> 42);
                    double dx = x - pointX;
                    double dy = y - pointY;
                    double dz = z - pointZ;
                    nearestSquared = Math.min(nearestSquared, dx * dx + dy * dy + dz * dz);
                }
            }
        }
        return Math.min(1.0D, StrictMath.sqrt(nearestSquared) / StrictMath.sqrt(3.0D));
    }

    private static double unit(long value) {
        return (value & 0x1FFFFFL) / (double) 0x200000;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
