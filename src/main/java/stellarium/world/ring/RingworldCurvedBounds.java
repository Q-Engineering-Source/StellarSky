package stellarium.world.ring;

/**
 * Conservative camera-relative bounds for an axis-aligned physical box under
 * {@link RingworldDisplayGeometry}'s closed cylindrical display embedding.
 *
 * <p>This is a geometry/culling preparation primitive only. It does not install a culling hook or
 * change what any renderer draws. The longitudinal extrema are solved analytically: endpoints and
 * the four sine/cosine cardinal angles are sufficient, with both radial-height endpoints at every
 * candidate. Consequently the work is bounded and independent of box size; it is not an adaptive
 * sampling approximation.</p>
 */
public final class RingworldCurvedBounds {
    private static final double TAU = Math.PI * 2.0;
    private static final double HALF_PI = Math.PI * 0.5;
    private static final double[] CARDINAL_ANGLES = {0.0, HALF_PI, Math.PI, -HALF_PI};

    private RingworldCurvedBounds() {
    }

    /**
     * Returns an outward-rounded AABB enclosing every display point of {@code physicalBox}.
     *
     * <p>For a fixed radial height {@code y}, the embedding is
     * {@code qx = (R-y) sin(a)} and {@code qy = (y-eyeY) + (R-y)(1-cos(a))}.
     * Each coordinate is affine in {@code R-y}, so the two Y endpoints suffice. Longitudinal
     * extrema occur only at the physical interval endpoints or cardinal angles. A span of one full
     * circumference or more evaluates the complete circle.</p>
     */
    public static CameraRelativeAabb cameraRelativeAabb(RingworldDisplayGeometry geometry,
                                                         RingworldRenderObserver observer,
                                                         PhysicalAabb physicalBox) {
        if (geometry == null || observer == null || physicalBox == null) {
            throw new IllegalArgumentException("Geometry, observer, and physical box are required");
        }
        double radius = geometry.radiusMeters();
        requireInsideCylinder(observer.y(), radius, "observer.y");
        requireInsideCylinder(physicalBox.minY(), radius, "physicalBox.minY");
        requireInsideCylinder(physicalBox.maxY(), radius, "physicalBox.maxY");

        double span = physicalBox.maxX() - physicalBox.minX();
        if (!Double.isFinite(span)) {
            throw new IllegalArgumentException("Physical X span must be finite");
        }
        double circumference = geometry.circumferenceMeters();
        BoundsAccumulator accumulator = new BoundsAccumulator();
        if (span >= circumference) {
            for (double angle : CARDINAL_ANGLES) {
                includeHeightEndpoints(accumulator, radius, observer.y(), angle, physicalBox);
            }
        } else {
            double start = geometry.canonicalArcOffset(physicalBox.minX(), observer.x()) / radius;
            double end = start + span / radius;
            includeHeightEndpoints(accumulator, radius, observer.y(), start, physicalBox);
            includeHeightEndpoints(accumulator, radius, observer.y(), end, physicalBox);
            for (double cardinal : CARDINAL_ANGLES) {
                double turns = Math.ceil((start - cardinal) / TAU);
                double candidate = cardinal + turns * TAU;
                if (candidate >= start && candidate <= end) {
                    includeHeightEndpoints(accumulator, radius, observer.y(), candidate, physicalBox);
                }
            }
        }
        // The analytic interval endpoint uses start + span / R. Retain the kernel's direct
        // endpoint evaluation as a hard union because independently rounded X differences can
        // move a one-AU endpoint by an ulp from that reconstructed angle.
        includePhysicalEndpoint(accumulator, geometry, observer, physicalBox.minX(), physicalBox.minY());
        includePhysicalEndpoint(accumulator, geometry, observer, physicalBox.minX(), physicalBox.maxY());
        includePhysicalEndpoint(accumulator, geometry, observer, physicalBox.maxX(), physicalBox.minY());
        includePhysicalEndpoint(accumulator, geometry, observer, physicalBox.maxX(), physicalBox.maxY());

        double minZ = finiteDifference(physicalBox.minZ(), observer.z(), "minimum camera-relative z");
        double maxZ = finiteDifference(physicalBox.maxZ(), observer.z(), "maximum camera-relative z");
        return new CameraRelativeAabb(outwardLower(accumulator.minX), outwardUpper(accumulator.maxX),
                outwardLower(accumulator.minY), outwardUpper(accumulator.maxY),
                outwardLower(minZ), outwardUpper(maxZ));
    }

    private static void includeHeightEndpoints(BoundsAccumulator accumulator, double radius, double eyeY,
                                               double angle, PhysicalAabb box) {
        include(accumulator, radius, eyeY, angle, box.minY());
        if (box.maxY() != box.minY()) {
            include(accumulator, radius, eyeY, angle, box.maxY());
        }
    }

    private static void includePhysicalEndpoint(BoundsAccumulator accumulator, RingworldDisplayGeometry geometry,
                                                RingworldRenderObserver observer, double worldX, double worldY) {
        RingworldDisplayGeometry.Point display = geometry.cameraRelative(observer,
                new RingworldDisplayGeometry.Point(worldX, worldY, observer.z()));
        accumulator.include(display.x(), display.y());
    }

    private static void include(BoundsAccumulator accumulator, double radius, double eyeY,
                                double angle, double worldY) {
        angle = canonicalEmbeddingAngle(angle);
        double radialDistance = radius - worldY;
        double sin = Math.sin(angle);
        double rise = 2.0 * Math.sin(angle * 0.5) * Math.sin(angle * 0.5);
        double x = radialDistance * sin;
        double y = (worldY - eyeY) + radialDistance * rise;
        accumulator.include(requireFinite(x, "camera-relative x"), requireFinite(y, "camera-relative y"));
    }

    private static double canonicalEmbeddingAngle(double angle) {
        double canonical = Math.IEEEremainder(angle, TAU);
        return canonical >= Math.PI ? canonical - TAU : canonical;
    }

    private static void requireInsideCylinder(double y, double radius, String name) {
        double radialDistance = radius - y;
        if (!Double.isFinite(y) || !(y < radius) || !Double.isFinite(radialDistance) || radialDistance <= 0.0) {
            throw new IllegalArgumentException(name + " must remain strictly inside the ringworld cylinder");
        }
    }

    private static double finiteDifference(double left, double right, String name) {
        return requireFinite(left - right, name);
    }

    private static double requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static double outwardLower(double value) {
        return nextFinite(value, false);
    }

    private static double outwardUpper(double value) {
        return nextFinite(value, true);
    }

    private static double nextFinite(double value, boolean upward) {
        double once = upward ? Math.nextUp(value) : Math.nextDown(value);
        if (!Double.isFinite(once)) {
            return value;
        }
        double twice = upward ? Math.nextUp(once) : Math.nextDown(once);
        return Double.isFinite(twice) ? twice : once;
    }

    private static final class BoundsAccumulator {
        private double minX = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private void include(double x, double y) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
    }

    /** Immutable finite physical axis-aligned box; all coordinates are inclusive mathematical bounds. */
    public record PhysicalAabb(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        public PhysicalAabb {
            requireFinite(minX, "minX");
            requireFinite(maxX, "maxX");
            requireFinite(minY, "minY");
            requireFinite(maxY, "maxY");
            requireFinite(minZ, "minZ");
            requireFinite(maxZ, "maxZ");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("AABB minimum must not exceed its maximum");
            }
        }
    }

    /** Immutable finite camera-relative AABB, conservatively rounded outward by the factory method. */
    public record CameraRelativeAabb(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        public CameraRelativeAabb {
            requireFinite(minX, "minX");
            requireFinite(maxX, "maxX");
            requireFinite(minY, "minY");
            requireFinite(maxY, "maxY");
            requireFinite(minZ, "minZ");
            requireFinite(maxZ, "maxZ");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("AABB minimum must not exceed its maximum");
            }
        }

        public boolean contains(RingworldDisplayGeometry.Point point) {
            return point.x() >= minX && point.x() <= maxX
                    && point.y() >= minY && point.y() <= maxY
                    && point.z() >= minZ && point.z() <= maxZ;
        }
    }
}
