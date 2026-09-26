package stellarium.client.ring.cloud;

import java.util.Objects;

/**
 * Allocation-free per-batch admission for cached cloud pages.
 *
 * <p>A {@link Frame} freezes the same rigid pose and five retained clip planes used by the
 * raster path.  It deliberately omits the ordinary far plane: the ring's visual range is not a
 * Minecraft terrain far-distance contract.  Distance rejection is conservative; a batch touching
 * a LOD boundary remains eligible.</p>
 */
public final class CloudLodVisibility {
    private CloudLodVisibility() {
    }

    public static Frame prepare(Pose pose, double displayEyeX, double displayEyeY, double displayEyeZ,
                                float[] projection, float[] modelView) {
        Objects.requireNonNull(pose, "pose");
        requireFinite(displayEyeX, "displayEyeX");
        requireFinite(displayEyeY, "displayEyeY");
        requireFinite(displayEyeZ, "displayEyeZ");
        requireMatrix(projection, "projection");
        requireMatrix(modelView, "modelView");
        double eyeX = pose.cos * (displayEyeX - pose.translateX) - pose.sin * (displayEyeY - pose.radius - pose.translateY);
        double eyeY = pose.sin * (displayEyeX - pose.translateX) + pose.cos * (displayEyeY - pose.radius - pose.translateY) + pose.radius;
        double eyeZ = displayEyeZ - pose.translateZ;
        double[] posed = poseMatrix(pose);
        double[] combined = multiply(projection, multiply(modelView, posed));
        return new Frame(eyeX, eyeY, eyeZ, plane(combined, 0, 1), plane(combined, 0, -1),
                plane(combined, 1, 1), plane(combined, 1, -1), plane(combined, 2, 1));
    }

    /** Convenience overload for renderer integration without a temporary pose object. */
    public static Frame prepare(double cos, double sin, double radius, double translateX, double translateY,
                                double translateZ, double displayEyeX, double displayEyeY, double displayEyeZ,
                                float[] projection, float[] modelView) {
        return prepare(new Pose(cos, sin, radius, translateX, translateY, translateZ), displayEyeX, displayEyeY,
                displayEyeZ, projection, modelView);
    }

    /** Returns the scaled, non-overlapping actual cached-page band for its atlas level. */
    public static DistanceBand bandForLevel(int level, double cellSizeBlocks) {
        if (!Double.isFinite(cellSizeBlocks) || cellSizeBlocks <= 0.0D) {
            throw new IllegalArgumentException("Cloud cell size must be finite and positive");
        }
        if (level < 0 || level >= CloudLodLayout.DISTANCE_TIERS.size()) {
            throw new IllegalArgumentException("Unknown cloud LOD " + level);
        }
        double scale = Math.min(1.0D, cellSizeBlocks / 12.0D);
        CloudLodLayout.DistanceTier tier = CloudLodLayout.DISTANCE_TIERS.get(level);
        return new DistanceBand(tier.minimumDistance() * scale, tier.maximumDistanceExclusive() * scale);
    }

    public record Pose(double cos, double sin, double radius, double translateX, double translateY,
                       double translateZ) {
        public Pose {
            requireFinite(cos, "cos"); requireFinite(sin, "sin"); requireFinite(radius, "radius");
            requireFinite(translateX, "translateX"); requireFinite(translateY, "translateY");
            requireFinite(translateZ, "translateZ");
            if (radius <= 0.0D) throw new IllegalArgumentException("Cloud pose radius must be positive");
            double length = Math.hypot(cos, sin);
            if (Math.abs(length - 1.0D) > 1.0e-12D) throw new IllegalArgumentException("Cloud pose rotation must be unit length");
        }
    }

    public record DistanceBand(double minimumDistance, double maximumDistanceExclusive) {
        public DistanceBand {
            requireFinite(minimumDistance, "minimumDistance"); requireFinite(maximumDistanceExclusive, "maximumDistanceExclusive");
            if (minimumDistance < 0.0D || !(maximumDistanceExclusive > minimumDistance)) {
                throw new IllegalArgumentException("Cloud LOD distance band is invalid");
            }
        }
    }

    /** One immutable frame, prepared once before the page/batch loops. */
    public static final class Frame {
        private final double eyeX, eyeY, eyeZ;
        private final Plane left, right, bottom, top, near;

        private Frame(double eyeX, double eyeY, double eyeZ, Plane left, Plane right, Plane bottom, Plane top, Plane near) {
            this.eyeX = eyeX; this.eyeY = eyeY; this.eyeZ = eyeZ;
            this.left = left; this.right = right; this.bottom = bottom; this.top = top; this.near = near;
        }

        public boolean visible(CloudLodPatchMeshBuilder.Aabb bounds, int lodLevel, double cellSizeBlocks) {
            DistanceBand band = bandForLevel(lodLevel, cellSizeBlocks);
            return visible(bounds, band.minimumDistance(), band.maximumDistanceExclusive());
        }

        /** No allocation occurs in this method; it is safe to call for every cached mesh batch. */
        public boolean visible(CloudLodPatchMeshBuilder.Aabb bounds, double minimumDistance, double maximumDistanceExclusive) {
            Objects.requireNonNull(bounds, "bounds");
            if (!Double.isFinite(minimumDistance) || !Double.isFinite(maximumDistanceExclusive)
                    || minimumDistance < 0.0D || !(maximumDistanceExclusive > minimumDistance)) {
                throw new IllegalArgumentException("Cloud batch distance range is invalid");
            }
            double nearestSquared = nearestSquared(bounds);
            double farthestSquared = farthestSquared(bounds);
            double scale = Math.max(Math.max(Math.abs(eyeX), Math.abs(eyeY)), Math.max(Math.abs(eyeZ), maxAbs(bounds)));
            double allowance = Math.max(0.01D, scale * 1.0e-12D);
            double nearest = Math.sqrt(nearestSquared);
            double farthest = Math.sqrt(farthestSquared);
            // Strict rejection keeps every batch that merely touches either half-open boundary.
            if (farthest + allowance < minimumDistance || nearest - allowance > maximumDistanceExclusive) return false;
            return retained(left, bounds) && retained(right, bounds) && retained(bottom, bounds)
                    && retained(top, bounds) && retained(near, bounds);
        }

        /** Package-visible reference predicate used by CPU property tests; no GL or mutable state. */
        boolean retainedPoint(double x, double y, double z, double minimumDistance, double maximumDistanceExclusive) {
            double distance = Math.sqrt(squared(x - eyeX) + squared(y - eyeY) + squared(z - eyeZ));
            return distance >= minimumDistance && distance < maximumDistanceExclusive
                    && left.value(x, y, z) >= 0.0D && right.value(x, y, z) >= 0.0D
                    && bottom.value(x, y, z) >= 0.0D && top.value(x, y, z) >= 0.0D && near.value(x, y, z) >= 0.0D;
        }

        private double nearestSquared(CloudLodPatchMeshBuilder.Aabb b) {
            return squared(nearest(eyeX, b.minX(), b.maxX()) - eyeX)
                    + squared(nearest(eyeY, b.minY(), b.maxY()) - eyeY)
                    + squared(nearest(eyeZ, b.minZ(), b.maxZ()) - eyeZ);
        }

        private double farthestSquared(CloudLodPatchMeshBuilder.Aabb b) {
            return squared(farthest(eyeX, b.minX(), b.maxX()) - eyeX)
                    + squared(farthest(eyeY, b.minY(), b.maxY()) - eyeY)
                    + squared(farthest(eyeZ, b.minZ(), b.maxZ()) - eyeZ);
        }
    }

    private record Plane(double x, double y, double z, double w) {
        private double value(double px, double py, double pz) { return x * px + y * py + z * pz + w; }
    }

    private static boolean retained(Plane plane, CloudLodPatchMeshBuilder.Aabb b) {
        double px = plane.x >= 0.0D ? b.maxX() : b.minX();
        double py = plane.y >= 0.0D ? b.maxY() : b.minY();
        double pz = plane.z >= 0.0D ? b.maxZ() : b.minZ();
        double maximum = plane.value(px, py, pz);
        double magnitude = Math.abs(plane.w) + Math.abs(plane.x) * Math.max(Math.abs(b.minX()), Math.abs(b.maxX()))
                + Math.abs(plane.y) * Math.max(Math.abs(b.minY()), Math.abs(b.maxY()))
                + Math.abs(plane.z) * Math.max(Math.abs(b.minZ()), Math.abs(b.maxZ()));
        return maximum >= -(magnitude * 1.0e-6D + 1.0e-9D);
    }

    private static Plane plane(double[] matrix, int component, int sign) {
        // Retain x+w, w-x, y+w, w-y and z+w.  row is the source component row;
        // sign +1 means row+w, -1 means w-row.
        int first = component;
        return new Plane(sign * matrix[first] + matrix[3], sign * matrix[first + 4] + matrix[7],
                sign * matrix[first + 8] + matrix[11], sign * matrix[first + 12] + matrix[15]);
    }

    private static double[] poseMatrix(Pose pose) {
        return new double[] {pose.cos, -pose.sin, 0, 0, pose.sin, pose.cos, 0, 0, 0, 0, 1, 0,
                pose.translateX - pose.sin * pose.radius, pose.radius + pose.translateY - pose.cos * pose.radius,
                pose.translateZ, 1};
    }

    private static double[] multiply(float[] left, double[] right) {
        double[] result = new double[16];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            result[column * 4 + row] = left[row] * right[column * 4] + left[4 + row] * right[column * 4 + 1]
                    + left[8 + row] * right[column * 4 + 2] + left[12 + row] * right[column * 4 + 3];
        }
        return result;
    }

    private static double[] multiply(float[] left, float[] right) {
        double[] result = new double[16];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            result[column * 4 + row] = left[row] * right[column * 4] + left[4 + row] * right[column * 4 + 1]
                    + left[8 + row] * right[column * 4 + 2] + left[12 + row] * right[column * 4 + 3];
        }
        return result;
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] result = new double[16];
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            result[column * 4 + row] = left[row] * right[column * 4] + left[4 + row] * right[column * 4 + 1]
                    + left[8 + row] * right[column * 4 + 2] + left[12 + row] * right[column * 4 + 3];
        }
        return result;
    }

    private static double maxAbs(CloudLodPatchMeshBuilder.Aabb b) {
        return Math.max(Math.max(Math.abs(b.minX()), Math.abs(b.maxX())),
                Math.max(Math.max(Math.abs(b.minY()), Math.abs(b.maxY())), Math.max(Math.abs(b.minZ()), Math.abs(b.maxZ()))));
    }
    private static double nearest(double value, double min, double max) { return value < min ? min : value > max ? max : value; }
    private static double farthest(double value, double min, double max) { return Math.abs(value - min) >= Math.abs(value - max) ? min : max; }
    private static double squared(double value) { return value * value; }
    private static void requireFinite(double value, String name) { if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite"); }
    private static void requireMatrix(float[] matrix, String name) {
        Objects.requireNonNull(matrix, name);
        if (matrix.length != 16) throw new IllegalArgumentException(name + " must contain 16 values");
        for (float value : matrix) if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " contains a non-finite value");
    }
}
