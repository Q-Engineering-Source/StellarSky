package stellarium.world.ring;

import java.util.Objects;

/**
 * A display-space ray whose samples are inverted through {@link RingworldDisplayGeometry}.
 *
 * <p>{@code Q(lambda) = direction * lambda} starts at the optical observer's tangent origin.
 * Lambda is display-ray distance, not a straight physical-world distance. Consumers that integrate
 * a physical medium must use {@link #physicalPathDerivative(double)} as their path-length weight.</p>
 */
public final class RingworldDisplayRay {
    private final RingworldDisplayGeometry geometry;
    private final RingworldRenderObserver observer;
    private final RingworldDisplayGeometry.Vector direction;
    private final double radialEyeDistance;
    private final double axisLambda;

    public RingworldDisplayRay(RingworldDisplayGeometry geometry, RingworldRenderObserver observer,
                               RingworldDisplayGeometry.Vector displayDirection) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.observer = Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(displayDirection, "displayDirection");
        this.radialEyeDistance = geometry.radiusMeters() - observer.y();
        if (!Double.isFinite(radialEyeDistance) || radialEyeDistance <= 0.0) {
            throw new IllegalArgumentException("Optical observer must remain strictly inside the ringworld cylinder");
        }
        this.direction = normalize(displayDirection);
        this.axisLambda = this.direction.x() == 0.0 && this.direction.y() > 0.0
                ? radialEyeDistance / this.direction.y() : Double.POSITIVE_INFINITY;
    }

    public RingworldDisplayGeometry geometry() {
        return geometry;
    }

    public RingworldRenderObserver observer() {
        return observer;
    }

    /** Unit direction in display tangent coordinates. */
    public RingworldDisplayGeometry.Vector direction() {
        return direction;
    }

    /** Returns the physical point at a non-negative display-ray distance. */
    public RingworldDisplayGeometry.Point physicalPointAt(double lambda) {
        requireLambda(lambda, "lambda");
        requireBeforeAxis(lambda);
        return geometry.worldPoint(observer, displayPoint(lambda));
    }

    /**
     * Returns the first radial-cylinder event for physical {@code worldY} after {@code afterLambda}.
     * Tangency and coplanarity remain explicit non-hit outcomes.
     */
    public Event constantHeight(double worldY, double afterLambda) {
        requireInsideCylinder(worldY, "worldY");
        requireAfterLambda(afterLambda);
        if (outsideChart(afterLambda)) {
            return NoHit.OUTSIDE_CHART;
        }
        double dx = direction.x();
        double dy = direction.y();
        double a = dx * dx + dy * dy;
        if (a == 0.0) {
            return worldY == observer.y() ? NoHit.COPLANAR : NoHit.MISS;
        }

        // |(lambda*dx, A-lambda*dy)|^2 = (R-worldY)^2, with
        // c=(A-r)(A+r)=(worldY-eyeY)*(2R-eyeY-worldY): no R^2 subtraction.
        double b = -2.0 * radialEyeDistance * dy;
        double c = (worldY - observer.y())
                * (2.0 * geometry.radiusMeters() - observer.y() - worldY);
        requireFiniteCoefficient(b, "height-ray linear coefficient");
        requireFiniteCoefficient(c, "height-ray constant coefficient");
        double discriminant = b * b - 4.0 * a * c;
        if (!Double.isFinite(discriminant)) {
            throw new IllegalStateException("Height-ray discriminant is outside the representable domain");
        }
        if (discriminant < 0.0) {
            return NoHit.MISS;
        }
        if (discriminant == 0.0) {
            double lambda = -b / (2.0 * a);
            return lambda > afterLambda ? tangentOrAxisLimit(lambda) : NoHit.MISS;
        }

        double root = Math.sqrt(discriminant);
        double q = -0.5 * (b + Math.copySign(root, b));
        double first;
        double second;
        if (q == 0.0) {
            first = (-b - root) / (2.0 * a);
            second = (-b + root) / (2.0 * a);
        } else {
            first = q / a;
            second = c / q;
        }
        return firstValidHit(afterLambda, first, second);
    }

    /**
     * Returns the first positive crossing of the observer-centered canonical longitude for
     * {@code worldX}. The angular plane's opposite half is rejected explicitly.
     */
    public Event constantLongitude(double worldX, double afterLambda) {
        requireFinite(worldX, "worldX");
        requireAfterLambda(afterLambda);
        if (outsideChart(afterLambda)) {
            return NoHit.OUTSIDE_CHART;
        }
        double arc = geometry.canonicalArcOffset(worldX, observer.x());
        double angle = arc / geometry.radiusMeters();
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double denominator = direction.x() * cos + direction.y() * sin;
        double numerator = radialEyeDistance * sin;
        if (denominator == 0.0) {
            return numerator == 0.0 ? NoHit.COPLANAR : NoHit.MISS;
        }
        double lambda = numerator / denominator;
        if (!Double.isFinite(lambda) || lambda <= afterLambda) {
            return NoHit.MISS;
        }
        if (atOrBeyondAxis(lambda)) {
            return new AxisLimit(axisLambda);
        }
        double qx = direction.x() * lambda;
        double b = radialEyeDistance - direction.y() * lambda;
        // The angular-plane equation also admits the antipodal ray. This dot product selects
        // the intended radius-positive half-plane, where atan2(qx, b) equals the target angle.
        double facing = qx * sin + b * cos;
        return facing > 0.0 ? new Hit(lambda) : NoHit.MISS;
    }

    /** Returns the first positive physical Z-plane crossing after {@code afterLambda}. */
    public Event constantZ(double worldZ, double afterLambda) {
        requireFinite(worldZ, "worldZ");
        requireAfterLambda(afterLambda);
        if (outsideChart(afterLambda)) {
            return NoHit.OUTSIDE_CHART;
        }
        if (direction.z() == 0.0) {
            return worldZ == observer.z() ? NoHit.COPLANAR : NoHit.MISS;
        }
        double lambda = (worldZ - observer.z()) / direction.z();
        if (!Double.isFinite(lambda) || lambda <= afterLambda) {
            return NoHit.MISS;
        }
        if (atOrBeyondAxis(lambda)) {
            return new AxisLimit(axisLambda);
        }
        return new Hit(lambda);
    }

    /**
     * Returns {@code |dP/dlambda|} for the inverse physical curve at {@code lambda}.
     * It is finite only while longitude is defined, so the cylinder axis fails explicitly.
     */
    public double physicalPathDerivative(double lambda) {
        requireLambda(lambda, "lambda");
        requireBeforeAxis(lambda);
        double qx = direction.x() * lambda;
        double b = radialEyeDistance - direction.y() * lambda;
        double radialSquared = qx * qx + b * b;
        if (!Double.isFinite(radialSquared) || radialSquared == 0.0) {
            throw new IllegalStateException("Display ray reaches the ringworld cylinder axis");
        }
        double radial = Math.sqrt(radialSquared);
        double longitudeDerivative = geometry.radiusMeters()
                * (b * direction.x() + qx * direction.y()) / radialSquared;
        double heightDerivative = (b * direction.y() - qx * direction.x()) / radial;
        double result = Math.hypot(Math.hypot(longitudeDerivative, heightDerivative), direction.z());
        if (!Double.isFinite(result) || result <= 0.0) {
            throw new IllegalStateException("Physical display-ray derivative is outside the representable domain");
        }
        return result;
    }

    private Event firstValidHit(double afterLambda, double first, double second) {
        double candidate = Double.POSITIVE_INFINITY;
        if (Double.isFinite(first) && first > afterLambda) {
            candidate = first;
        }
        if (Double.isFinite(second) && second > afterLambda && second < candidate) {
            candidate = second;
        }
        if (!Double.isFinite(candidate)) {
            return NoHit.MISS;
        }
        if (atOrBeyondAxis(candidate)) {
            return new AxisLimit(axisLambda);
        }
        return new Hit(candidate);
    }

    private Event tangentOrAxisLimit(double lambda) {
        return atOrBeyondAxis(lambda) ? new AxisLimit(axisLambda) : new Tangent(lambda);
    }

    private boolean outsideChart(double afterLambda) {
        return axisLambda <= afterLambda;
    }

    private boolean atOrBeyondAxis(double lambda) {
        return axisLambda <= lambda;
    }

    private RingworldDisplayGeometry.Point displayPoint(double lambda) {
        return new RingworldDisplayGeometry.Point(direction.x() * lambda,
                direction.y() * lambda, direction.z() * lambda);
    }

    private void requireBeforeAxis(double lambda) {
        if (axisLambda <= lambda) {
            throw new IllegalStateException("Display ray reaches the ringworld cylinder axis before this event");
        }
    }

    private void requireInsideCylinder(double worldY, String name) {
        requireFinite(worldY, name);
        double radial = geometry.radiusMeters() - worldY;
        if (!(worldY < geometry.radiusMeters()) || !Double.isFinite(radial) || radial <= 0.0) {
            throw new IllegalArgumentException(name + " must remain strictly inside the ringworld cylinder");
        }
    }

    private static RingworldDisplayGeometry.Vector normalize(RingworldDisplayGeometry.Vector value) {
        double length = Math.hypot(Math.hypot(value.x(), value.y()), value.z());
        if (!Double.isFinite(length) || length == 0.0) {
            throw new IllegalArgumentException("Display ray direction must be finite and non-zero");
        }
        return new RingworldDisplayGeometry.Vector(value.x() / length, value.y() / length, value.z() / length);
    }

    private static void requireAfterLambda(double value) {
        requireLambda(value, "afterLambda");
    }

    private static void requireLambda(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteCoefficient(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(name + " is outside the representable domain");
        }
    }

    /** An event that crosses the queried physical surface. */
    public record Hit(double lambda) implements Event {
        public Hit {
            if (!Double.isFinite(lambda) || lambda <= 0.0) {
                throw new IllegalArgumentException("Hit lambda must be finite and strictly positive");
            }
        }
    }

    /** A tangent contact: it is geometrically informative but does not cross a volume boundary. */
    public record Tangent(double lambda) implements Event {
        public Tangent {
            if (!Double.isFinite(lambda) || lambda <= 0.0) {
                throw new IllegalArgumentException("Tangent lambda must be finite and strictly positive");
            }
        }
    }

    /** The ray reaches the cylinder axis before a queried surface can be represented. */
    public record AxisLimit(double lambda) implements Event {
        public AxisLimit {
            if (!Double.isFinite(lambda) || lambda <= 0.0) {
                throw new IllegalArgumentException("Axis-limit lambda must be finite and strictly positive");
            }
        }
    }

    /** Explicit non-hit results; callers must not reinterpret them as a fallback hit. */
    public enum NoHit implements Event {
        MISS,
        COPLANAR,
        OUTSIDE_CHART
    }

    public sealed interface Event permits Hit, Tangent, AxisLimit, NoHit {
    }
}
