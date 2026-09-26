package stellarium.client.ring.cloud;

import java.nio.FloatBuffer;

/** Pure capacity decision for the published tail page and one frozen projection. */
public final class CloudTailCoverage {
    public static final int PROXY_RANKS = 24;
    public static final int TOTAL_RANKS = 64;
    public static final double EDGE_ERROR_FACTOR = 8.0 * Math.ulp(1.0f);
    public static final double NUMERIC_TRACE_LIMIT = 1.0e30;
    private CloudTailCoverage() { }

    public static Coverage prepare(double lowerY, double upperY, double queryEyeY,
                                   double pixelAngularSize, double minimumX, double maximumX,
                                   double finalCellWidth, double configuredCoverage) {
        if (!Double.isFinite(lowerY) || !Double.isFinite(upperY) || !Double.isFinite(queryEyeY)
                || !Double.isFinite(pixelAngularSize) || pixelAngularSize <= 0.0
                || !Double.isFinite(minimumX) || !Double.isFinite(maximumX)
                || minimumX >= maximumX || !Double.isFinite(finalCellWidth) || finalCellWidth <= 0.0
                || finalCellWidth > Float.MAX_VALUE / 8.0
                || !Double.isFinite(configuredCoverage) || configuredCoverage < 0.0 || configuredCoverage > 1.0) {
            throw new IllegalArgumentException("Invalid cloud tail coverage inputs");
        }
        double capacity = Math.min(CloudLodLayout.MAX_CACHED_TAIL_DISTANCE,
                Math.max(0.0, Math.min(-minimumX, maximumX)));
        double extent = upperY <= lowerY ? 0.0 : Math.min(Double.MAX_VALUE,
                Math.max(Math.abs(lowerY * 0.5 + upperY * 0.5 - queryEyeY), upperY - lowerY));
        double required = Math.min(Double.MAX_VALUE, extent / pixelAngularSize);
        boolean fallback = extent > pixelAngularSize * capacity;
        double width = Math.max(finalCellWidth, Math.min(8.0 * finalCellWidth,
                8.0 * (pixelAngularSize * capacity)));
        // A declared empty cloud field must stay empty, including beyond its cache.
        int ranks = configuredCoverage == 0.0 ? 0 : PROXY_RANKS;
        return new Coverage(capacity, required, fallback, width, ranks);
    }

    /** Distances here describe cache support, never a replacement surface hit/depth. */
    public record Coverage(double cachedDistance, double requiredDistance, boolean fallbackNeeded,
                           double transitionWidth, int proxyRanks) {
        /** Both shader consumers upload this same frozen layout through reusable buffers. */
        public void copyUniforms(FloatBuffer target) {
            if (target == null || target.capacity() < 3) throw new IllegalArgumentException("Tail policy requires 3 floats");
            target.clear();
            target.put((float) transitionWidth).put(fallbackNeeded ? 1.0f : 0.0f).put(proxyRanks).flip();
        }

        /** CPU reference for the shared GLSL coverage law; not a GPU execution claim. */
        public int referenceAcceptedRanks(boolean addressable, boolean occupied, double margin) {
            if (!Double.isFinite(margin)) throw new IllegalArgumentException("Non-finite tail margin");
            double w = addressable ? 0.0 : 1.0;
            if (addressable && fallbackNeeded) {
                double progress = Math.max(0.0, Math.min(1.0, margin / transitionWidth));
                w = 1.0 - progress * progress * (3.0 - 2.0 * progress);
            }
            double cached = addressable && occupied ? 1.0 : 0.0;
            return (int) Math.floor(TOTAL_RANKS * ((1.0 - w) * cached + w * proxyRanks / TOTAL_RANKS) + 0.5);
        }
    }

    /** CPU reference of terminal geometry admission; parallel rays retain the existing nonterminal path. */
    public static double referenceTerminalHit(double eyeY, double eyeZ, double rayY, double rayZ,
                                             double lowerY, double upperY, double minZ, double maxZ,
                                             double pixelAngularSize, boolean degraded) {
        if (!Double.isFinite(eyeY) || !Double.isFinite(eyeZ) || !Double.isFinite(rayY)
                || !Double.isFinite(rayZ) || !Double.isFinite(lowerY) || !Double.isFinite(upperY)
                || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                || !Double.isFinite(pixelAngularSize) || pixelAngularSize <= 0.0)
            throw new IllegalArgumentException("Invalid terminal ray reference inputs");
        if (upperY <= lowerY || maxZ <= minZ || rayY == 0.0) return -1.0;
        double dy = lowerY * 0.5 + upperY * 0.5 - eyeY;
        double t = dy / rayY;
        if (!Double.isFinite(t) || t < CloudLodLayout.TAIL11_END_DISTANCE || t >= NUMERIC_TRACE_LIMIT) return -1.0;
        double z = eyeZ + rayZ * t;
        if (!Double.isFinite(z) || z < minZ || z >= maxZ) return -1.0;
        double extent = Math.max(Math.abs(dy), upperY - lowerY);
        if (extent / t <= pixelAngularSize) return -1.0;
        if (degraded) {
            double edgeError = EDGE_ERROR_FACTOR * (Math.abs(eyeZ) + Math.abs(rayZ) * t
                    + Math.max(Math.abs(minZ), Math.abs(maxZ)) + 1.0);
            if (z < minZ + edgeError || z >= maxZ - edgeError) return -1.0;
        }
        return t;
    }
}
