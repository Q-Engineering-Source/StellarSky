package stellarium.world.ring;

/**
 * Immutable, display-only cylindrical geometry for the inside of one ringworld.
 *
 * <p>World X is longitudinal arc length, world Y is radial height directed inward, and world Z is
 * transverse. This class never wraps or mutates stored world coordinates: it selects the canonical
 * visible longitudinal branch for a single display calculation. It is deliberately independent of
 * Minecraft, OpenGL, world time, collision, gravity, and gameplay light.</p>
 */
public final class RingworldDisplayGeometry {
    /** Exact SI definition used for the default Earth-themed ringworld display radius. */
    public static final double DEFAULT_RADIUS_METERS = 149_597_870_700.0;
    /** User-selected first curvature acceptance scene; the general one-AU default remains available. */
    public static final double CURVATURE_ACCEPTANCE_RADIUS_METERS = DEFAULT_RADIUS_METERS * 0.000292;

    private static final double TAU = Math.PI * 2.0;
    private static final double SERIES_LIMIT = 1.0e-4;

    private final double radiusMeters;
    private final double curvaturePerMeter;
    private final double circumferenceMeters;

    /** Creates the one-AU default geometry. */
    public RingworldDisplayGeometry() {
        this(DEFAULT_RADIUS_METERS);
    }

    /**
     * @param radiusMeters the one radius that controls both curvature and central-Sun distance
     */
    public RingworldDisplayGeometry(double radiusMeters) {
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0.0) {
            throw new IllegalArgumentException("Ringworld display radius must be finite and positive");
        }
        double curvature = 1.0 / radiusMeters;
        if (!Double.isFinite(curvature)) {
            throw new IllegalArgumentException("Ringworld display curvature must be representable");
        }
        double circumference = TAU * radiusMeters;
        if (!Double.isFinite(circumference)) {
            throw new IllegalArgumentException("Ringworld display circumference must be representable");
        }
        this.radiusMeters = radiusMeters;
        this.curvaturePerMeter = curvature;
        this.circumferenceMeters = circumference;
    }

    public double radiusMeters() {
        return radiusMeters;
    }

    public double circumferenceMeters() {
        return circumferenceMeters;
    }

    /** Returns the curvature coupled to {@link #radiusMeters()}; it is never an independent setting. */
    public double curvaturePerMeter() {
        return curvaturePerMeter;
    }

    /**
     * Returns the camera-relative longitudinal arc in {@code [-circumference/2, circumference/2)}.
     * It is a display branch only; callers must not write it back to gameplay/world storage.
     */
    public double canonicalArcOffset(double worldX, double eyeX) {
        requireFinite(worldX, "worldX");
        requireFinite(eyeX, "eyeX");
        double offset = worldX - eyeX;
        if (!Double.isFinite(offset)) {
            throw new IllegalArgumentException("Longitudinal offset must be finite");
        }
        return canonicalize(offset);
    }

    /**
     * Embeds a physical world point into the optical camera tangent frame.
     *
     * <p>The result is camera-relative and stable near zero arc offset; it is not a replacement for
     * game coordinates or a render-pass adapter.</p>
     */
    public Point cameraRelative(RingworldRenderObserver observer, Point world) {
        requireInsideCylinder(observer.y(), "observer.y");
        requireInsideCylinder(world.y(), "world.y");

        double s = canonicalArcOffset(world.x(), observer.x());
        double a = s / radiusMeters;
        double sin = Math.sin(a);
        double v = 2.0 * Math.sin(a * 0.5) * Math.sin(a * 0.5);
        double qx = s * sinc(a) - world.y() * sin;
        // Preserve small height differences near the observer, but combine the height terms
        // before multiplication on the far hemisphere: y*v may overflow while y*(1-v) is finite.
        double qy = v <= 1.0
                ? (world.y() - observer.y()) + s * vOverA(a) - world.y() * v
                : world.y() * (1.0 - v) - observer.y() + s * vOverA(a);
        double qz = world.z() - observer.z();
        return new Point(requireFiniteResult(qx, "camera-relative x"),
                requireFiniteResult(qy, "camera-relative y"),
                requireFiniteResult(qz, "camera-relative z"));
    }

    /**
     * Inverts a camera-relative display point and returns a world X on the observer-centered
     * canonical branch.
     *
     * <p>Because a closed ring has no injective unbounded X coordinate, the returned X is
     * {@code observer.x() + canonicalArc}; it is not globally rewrapped or suitable for overwriting
     * physics storage. The cylinder axis is intentionally rejected: longitude is undefined there.</p>
     */
    public Point worldPoint(RingworldRenderObserver observer, Point cameraRelative) {
        requireInsideCylinder(observer.y(), "observer.y");
        double radialToEye = radiusMeters - observer.y();
        double b = radialToEye - cameraRelative.y();
        double radialDistance = Math.hypot(cameraRelative.x(), b);
        if (!Double.isFinite(radialDistance) || radialDistance == 0.0) {
            throw new IllegalArgumentException("Cannot invert a point on the ringworld cylinder axis");
        }

        double worldY = inverseHeight(cameraRelative.x(), b, radialDistance, observer.y(), cameraRelative.y());
        requireInsideCylinder(worldY, "inverted world.y");
        double angle = Math.atan2(cameraRelative.x(), b);
        // The canonical branch is half-open. atan2(+0, negative) returns +pi, which is the same
        // display point as -pi but not this API's canonical representative.
        if (angle == Math.PI) {
            angle = -Math.PI;
        }
        double arc = angle * radiusMeters;
        double worldX = observer.x() + arc;
        double worldZ = observer.z() + cameraRelative.z();
        return new Point(requireFiniteResult(worldX, "canonical world x"), worldY,
                requireFiniteResult(worldZ, "world z"));
    }

    /** Returns the forward-map Jacobian determinant {@code (R - worldY) / R}. */
    public double jacobianDeterminant(double worldY) {
        requireInsideCylinder(worldY, "worldY");
        return (radiusMeters - worldY) / radiusMeters;
    }

    /**
     * Transforms a non-zero physical-space normal by the embedding's inverse transpose and
     * normalizes it. The scaled implementation is direction-equivalent to that inverse transpose
     * while avoiding an overflow when a valid point is very near the cylinder axis.
     */
    public Vector transformNormal(RingworldRenderObserver observer, Point world, Vector worldNormal) {
        requireInsideCylinder(observer.y(), "observer.y");
        requireInsideCylinder(world.y(), "world.y");
        double s = canonicalArcOffset(world.x(), observer.x());
        double a = s / radiusMeters;
        double sin = Math.sin(a);
        double cos = Math.cos(a);
        double determinant = jacobianDeterminant(world.y());

        double maximumComponent = Math.max(Math.abs(worldNormal.x()),
                Math.max(Math.abs(worldNormal.y()), Math.abs(worldNormal.z())));
        if (maximumComponent == 0.0) {
            throw new IllegalArgumentException("transformed normal must be finite and non-zero");
        }
        double nx = worldNormal.x() / maximumComponent;
        double ny = worldNormal.y() / maximumComponent;
        double nz = worldNormal.z() / maximumComponent;
        double x;
        double y;
        double z;
        if (determinant <= 1.0) {
            // Multiply A^-T by determinant before normalizing.
            x = cos * nx - determinant * sin * ny;
            y = sin * nx + determinant * cos * ny;
            z = determinant * nz;
        } else {
            // For determinant > 1, retain A^-T directly and divide before mixing so a valid
            // large normal cannot overflow merely because its direction is being transformed.
            double inverseDeterminant = 1.0 / determinant;
            x = cos * (nx * inverseDeterminant) - sin * ny;
            y = sin * (nx * inverseDeterminant) + cos * ny;
            z = nz;
        }
        return normalized(x, y, z, "transformed normal");
    }

    /**
     * Returns central-Sun optical data coupled to this geometry's radius.
     *
     * <p>The central Sun is in the tangent basis at {@code (0, R - eyeY, -eyeZ)}. This only
     * supplies apparent geometry; it does not alter gameplay light intensity or any world clock.</p>
     */
    public SunView centralSun(RingworldRenderObserver observer, double sunRadiusMeters) {
        requireInsideCylinder(observer.y(), "observer.y");
        if (!Double.isFinite(sunRadiusMeters) || sunRadiusMeters <= 0.0) {
            throw new IllegalArgumentException("Sun radius must be finite and positive");
        }
        double up = radiusMeters - observer.y();
        double transverse = -observer.z();
        double distance = Math.hypot(up, transverse);
        if (!Double.isFinite(distance) || distance <= sunRadiusMeters) {
            throw new IllegalArgumentException("Observer must remain outside the physical Sun");
        }
        Vector direction = new Vector(0.0, up / distance, transverse / distance);
        return new SunView(direction, distance, Math.asin(sunRadiusMeters / distance));
    }

    private double inverseHeight(double qx, double b, double radialDistance,
                                 double eyeY, double qy) {
        double halfQx = qx * 0.5;
        if (b >= 0.0) {
            // R - r = (R - b) - (r - b), and r - b = qx^2 / (r + b).
            // Halving before the quotient prevents both qx^2 and r + b from overflowing.
            double halfDenominator = radialDistance * 0.5 + b * 0.5;
            return eyeY + qy - qx * (halfQx / halfDenominator);
        }
        // In the antipodal hemisphere r + b loses all useful precision. Use the conjugate
        // identity R - r = (R + b) - (r + b), where r + b = qx^2 / (r - b).
        double halfDenominator = radialDistance * 0.5 - b * 0.5;
        return radiusMeters + b - qx * (halfQx / halfDenominator);
    }

    private double canonicalize(double arc) {
        requireFinite(arc, "longitudinal arc");
        double canonical = Math.IEEEremainder(arc, circumferenceMeters);
        if (canonical >= circumferenceMeters * 0.5) {
            canonical -= circumferenceMeters;
        }
        return requireFiniteResult(canonical, "canonical longitudinal arc");
    }

    private double sinc(double angle) {
        double absolute = Math.abs(angle);
        if (absolute < SERIES_LIMIT) {
            double squared = angle * angle;
            return 1.0 - squared / 6.0 + squared * squared / 120.0 - squared * squared * squared / 5_040.0;
        }
        return Math.sin(angle) / angle;
    }

    private double vOverA(double angle) {
        double absolute = Math.abs(angle);
        if (absolute < SERIES_LIMIT) {
            double squared = angle * angle;
            return angle * (0.5 - squared / 24.0 + squared * squared / 720.0
                    - squared * squared * squared / 40_320.0);
        }
        return 2.0 * Math.sin(angle * 0.5) * Math.sin(angle * 0.5) / angle;
    }

    private void requireInsideCylinder(double height, String name) {
        requireFinite(height, name);
        double radialDistance = radiusMeters - height;
        if (!(height < radiusMeters) || !Double.isFinite(radialDistance) || radialDistance <= 0.0) {
            throw new IllegalArgumentException(name + " must remain strictly inside the ringworld cylinder");
        }
    }

    private static Vector normalized(double x, double y, double z, String name) {
        double length = Math.hypot(Math.hypot(x, y), z);
        if (!Double.isFinite(length) || length == 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
        return new Vector(x / length, y / length, z / length);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static double requireFiniteResult(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " is outside the representable display domain");
        }
        return value;
    }

    /** Immutable finite point; its interpretation is selected by the method that accepts or returns it. */
    public record Point(double x, double y, double z) {
        public Point {
            requireFinite(x, "point.x");
            requireFinite(y, "point.y");
            requireFinite(z, "point.z");
        }
    }

    /** Immutable finite vector. */
    public record Vector(double x, double y, double z) {
        public Vector {
            requireFinite(x, "vector.x");
            requireFinite(y, "vector.y");
            requireFinite(z, "vector.z");
        }
    }

    /** Immutable central-Sun optical data, constructible only through {@link #centralSun}. */
    public static final class SunView {
        private final Vector direction;
        private final double distanceMeters;
        private final double angularRadiusRadians;

        private SunView(Vector direction, double distanceMeters, double angularRadiusRadians) {
            this.direction = direction;
            this.distanceMeters = distanceMeters;
            this.angularRadiusRadians = angularRadiusRadians;
        }

        public Vector direction() {
            return direction;
        }

        public double distanceMeters() {
            return distanceMeters;
        }

        public double angularRadiusRadians() {
            return angularRadiusRadians;
        }
    }
}
