package stellarium.world.ring;

/**
 * Pure numerical counterpart of the shared FP64 display-ray kernel.
 *
 * <p>This exposes root and derivative semantics without an OpenGL context, so callers can compare
 * shader-boundary cases to {@link RingworldDisplayRay}. It uses only the geometry's one radius.</p>
 */
public final class RingworldCurvedRayReference {
    private RingworldCurvedRayReference() {
    }

    /** Returns sorted quadratic roots and a classification matching {@code ssCurvedHeightRoots}. */
    public static HeightRoots heightRoots(RingworldDisplayGeometry geometry, RingworldRenderObserver eye,
                                          RingworldDisplayGeometry.Vector ray, double worldY) {
        requireInside(geometry, eye.y());
        requireInside(geometry, worldY);
        double dx = ray.x();
        double dy = ray.y();
        double a = dx * dx + dy * dy;
        if (a == 0.0) {
            return worldY == eye.y() ? HeightRoots.coplanar() : HeightRoots.miss();
        }
        double radius = geometry.radiusMeters();
        double eyeDistance = radius - eye.y();
        double b = -2.0 * eyeDistance * dy;
        double c = (worldY - eye.y()) * (2.0 * radius - eye.y() - worldY);
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            return HeightRoots.miss();
        }
        if (discriminant == 0.0) {
            double root = -b / (2.0 * a);
            return HeightRoots.tangent(root);
        }
        double root = Math.sqrt(discriminant);
        double q = -0.5 * (b + Math.copySign(root, b));
        double first = q == 0.0 ? (-b - root) / (2.0 * a) : q / a;
        double second = q == 0.0 ? (-b + root) / (2.0 * a) : c / q;
        return HeightRoots.two(Math.min(first, second), Math.max(first, second));
    }

    /** Same physical path metric as {@link RingworldDisplayRay#physicalPathDerivative(double)}. */
    public static double pathWeight(RingworldDisplayGeometry geometry, RingworldRenderObserver eye,
                                    RingworldDisplayGeometry.Vector unitRay, double lambda) {
        double radialEyeDistance = geometry.radiusMeters() - eye.y();
        double qx = unitRay.x() * lambda;
        double b = radialEyeDistance - unitRay.y() * lambda;
        double radialSquared = qx * qx + b * b;
        if (radialSquared == 0.0) {
            throw new IllegalStateException("Display ray reaches the ringworld cylinder axis");
        }
        double radial = Math.sqrt(radialSquared);
        double longitude = geometry.radiusMeters()
                * (b * unitRay.x() + qx * unitRay.y()) / radialSquared;
        double height = (b * unitRay.y() - qx * unitRay.x()) / radial;
        return Math.hypot(Math.hypot(longitude, height), unitRay.z());
    }

    private static void requireInside(RingworldDisplayGeometry geometry, double worldY) {
        if (!Double.isFinite(worldY) || !(worldY < geometry.radiusMeters())) {
            throw new IllegalArgumentException("Height must remain strictly inside the ringworld cylinder");
        }
    }

    public enum HeightRootKind {
        TWO,
        TANGENT,
        MISS,
        COPLANAR
    }

    public record HeightRoots(HeightRootKind kind, double first, double second) {
        private static HeightRoots two(double first, double second) {
            return new HeightRoots(HeightRootKind.TWO, first, second);
        }

        private static HeightRoots tangent(double root) {
            return new HeightRoots(HeightRootKind.TANGENT, root, root);
        }

        private static HeightRoots miss() {
            return new HeightRoots(HeightRootKind.MISS, 0.0, 0.0);
        }

        private static HeightRoots coplanar() {
            return new HeightRoots(HeightRootKind.COPLANAR, 0.0, 0.0);
        }
    }
}
