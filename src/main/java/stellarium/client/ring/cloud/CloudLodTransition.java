package stellarium.client.ring.cloud;

/** Frozen total widths around the first three fixed LOD boundaries, in blocks. */
public record CloudLodTransition(double fineWidth, double midWidth, double lowWidth) {
    public static final CloudLodTransition HARD = new CloudLodTransition(0.0, 0.0, 0.0);
    public static final CloudLodTransition DEFAULT = new CloudLodTransition(128.0, 384.0, 1024.0);

    public CloudLodTransition {
        requireWidth(fineWidth, 256.0);
        requireWidth(midWidth, 768.0);
        requireWidth(lowWidth, 2048.0);
    }

    public double upperBoundary(int boundary) {
        return center(boundary) + width(boundary) * 0.5;
    }

    /** Also bounds shader floating-point rounding at the two extreme quantiles. */
    public double lowerBoundary(int boundary) {
        return center(boundary) - width(boundary) * 0.5;
    }

    public double boundary(int boundary, double quantile) {
        if (!Double.isFinite(quantile) || quantile < 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("Cloud transition quantile must be in [0, 1]");
        }
        // Inverse of smoothstep(t)=t*t*(3-2*t), not smoothstep(rank).
        double offset = -StrictMath.sin(StrictMath.asin(1.0 - 2.0 * quantile) / 3.0);
        return center(boundary) + width(boundary) * offset;
    }

    /** CPU reference of the exact, 8x8 ordered pixel rank used by cloud_style.glsl. */
    public static double pixelRank(int x, int y) {
        int rank = 0;
        for (int bit = 0; bit < 3; bit++) {
            int bx = (x >> bit) & 1, by = (y >> bit) & 1;
            rank = 4 * rank + 2 * (bx ^ by) + by;
        }
        return (rank + 0.5) / 64.0;
    }

    public double width(int boundary) {
        return switch (boundary) {
            case 0 -> fineWidth;
            case 1 -> midWidth;
            case 2 -> lowWidth;
            default -> throw new IllegalArgumentException("Only the first three cloud LOD boundaries transition");
        };
    }

    private static double center(int boundary) {
        return switch (boundary) {
            case 0 -> CloudLodLayout.FINE_3D_END_DISTANCE;
            case 1 -> CloudLodLayout.MID_3D_END_DISTANCE;
            case 2 -> CloudLodLayout.LOW_3D_END_DISTANCE;
            default -> throw new IllegalArgumentException("Invalid cloud LOD boundary");
        };
    }

    private static void requireWidth(double value, double maximum) {
        if (!Double.isFinite(value) || value < 0.0 || value > maximum) {
            throw new IllegalArgumentException("Cloud transition width must be within [0, " + maximum + "]");
        }
    }
}
