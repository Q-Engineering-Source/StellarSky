package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldDisplayRayTest {
    private static final double ONE_AU = RingworldDisplayGeometry.DEFAULT_RADIUS_METERS;

    @Test
    public void oneAuLocalUpwardHeightShellUsesTheStableNearRoot() {
        RingworldDisplayRay ray = ray(new RingworldDisplayGeometry(), new RingworldRenderObserver(0.0, 64.0, 0.0),
                0.0, 1.0, 0.0);

        RingworldDisplayRay.Event event = ray.constantHeight(128.0, 0.0);
        assertHit(event, 64.0, 1.0e-12);
        assertEquals(128.0, ray.physicalPointAt(((RingworldDisplayRay.Hit) event).lambda()).y(), 1.0e-9);
    }

    @Test
    public void radialShellReturnsTheFirstOfTwoForwardRootsAndCanResumeAfterIt() {
        RingworldDisplayRay ray = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 0.6, 0.8, 0.0);

        RingworldDisplayRay.Hit entry = hit(ray.constantHeight(200.0, 0.0));
        RingworldDisplayRay.Hit exit = hit(ray.constantHeight(200.0, entry.lambda()));
        assertTrue(entry.lambda() > 0.0);
        assertTrue(exit.lambda() > entry.lambda());
        assertEquals(200.0, ray.physicalPointAt(entry.lambda()).y(), 1.0e-9);
        assertEquals(200.0, ray.physicalPointAt(exit.lambda()).y(), 1.0e-9);
    }

    @Test
    public void tangentAndCoplanarAreExplicitNonCrossingOutcomes() {
        RingworldDisplayRay tangentRay = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 0.6, 0.8, 0.0);
        RingworldDisplayRay.Event tangent = tangentRay.constantHeight(460.0, 0.0);
        assertTrue(tangent instanceof RingworldDisplayRay.Tangent);
        assertEquals(720.0, ((RingworldDisplayRay.Tangent) tangent).lambda(), 1.0e-9);

        RingworldDisplayRay coplanarRay = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 0.0, 0.0, 1.0);
        assertSame(RingworldDisplayRay.NoHit.COPLANAR, coplanarRay.constantHeight(100.0, 0.0));
        RingworldDisplayRay zCoplanarRay = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 1.0, 0.0, 0.0);
        assertSame(RingworldDisplayRay.NoHit.COPLANAR, zCoplanarRay.constantZ(0.0, 0.0));
    }

    @Test
    public void axisAndEventsBeyondItReturnAnExplicitVisibilityLimit() {
        RingworldDisplayRay ray = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 0.0, 1.0, 0.0);

        assertThrows(IllegalStateException.class, () -> ray.physicalPointAt(900.0));
        assertAxisLimit(ray.constantHeight(50.0, 0.0), 900.0, 1.0e-9);
        assertSame(RingworldDisplayRay.NoHit.OUTSIDE_CHART, ray.constantHeight(50.0, 900.0));
    }

    @Test
    public void axisLimitAlsoTerminatesZAndLongitudeQueriesAndResumedShellExits() {
        RingworldDisplayRay upward = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 0.0, 1.0, 0.0);
        RingworldDisplayRay.Hit entry = hit(upward.constantHeight(200.0, 0.0));
        assertEquals(100.0, entry.lambda(), 1.0e-12);
        assertAxisLimit(upward.constantHeight(200.0, entry.lambda()), 900.0, 1.0e-9);
        assertAxisLimit(upward.constantLongitude(Math.PI * 500.0, 0.0), 900.0, 1.0e-9);

        RingworldDisplayRay zRay = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, 0.0), 0.0, 0.8, 0.6);
        assertAxisLimit(zRay.constantZ(1_000.0, 0.0), 1_125.0, 1.0e-9);
        assertSame(RingworldDisplayRay.NoHit.OUTSIDE_CHART, zRay.constantZ(1_000.0, 1_125.0));
    }

    @Test
    public void nearAntipodeInverseStaysFiniteWithoutTouchingTheAxis() {
        double dx = 1.0e-6;
        RingworldDisplayRay ray = ray(new RingworldDisplayGeometry(), new RingworldRenderObserver(0.0, 64.0, 0.0),
                dx, Math.sqrt(1.0 - dx * dx), 0.0);
        RingworldDisplayGeometry.Point point = ray.physicalPointAt(2.0 * (ONE_AU - 64.0) * ray.direction().y());

        assertTrue(Double.isFinite(point.x()));
        assertTrue(Double.isFinite(point.y()));
        assertTrue(point.x() > Math.PI * ONE_AU * 0.999);
        assertEquals(64.0, point.y(), 1.0e-3);
    }

    @Test
    public void longitudeHalfPlaneRejectsTheOppositeBranch() {
        RingworldDisplayRay ray = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 0.0, 0.0), 0.6, 0.8, 0.0);
        RingworldDisplayRay.Hit positive = hit(ray.constantLongitude(Math.PI * 500.0, 0.0));
        assertEquals(1_250.0, positive.lambda(), 1.0e-9);
        assertEquals(Math.PI * 500.0, ray.physicalPointAt(positive.lambda()).x(), 1.0e-9);
        assertSame(RingworldDisplayRay.NoHit.MISS, ray.constantLongitude(-Math.PI * 500.0, 0.0));
    }

    @Test
    public void transversePlaneAndPhysicalDerivativeAgreeWithFiniteDifferences() {
        RingworldDisplayRay ray = ray(new RingworldDisplayGeometry(1_000.0),
                new RingworldRenderObserver(0.0, 100.0, -20.0), 0.3, 0.4, Math.sqrt(0.75));
        RingworldDisplayRay.Hit zHit = hit(ray.constantZ(80.0, 0.0));
        assertEquals(100.0 / Math.sqrt(0.75), zHit.lambda(), 1.0e-9);

        double lambda = 200.0;
        double epsilon = 1.0e-4;
        RingworldDisplayGeometry.Point before = ray.physicalPointAt(lambda - epsilon);
        RingworldDisplayGeometry.Point after = ray.physicalPointAt(lambda + epsilon);
        double finiteDifference = Math.sqrt(square((after.x() - before.x()) / (2.0 * epsilon))
                + square((after.y() - before.y()) / (2.0 * epsilon))
                + square((after.z() - before.z()) / (2.0 * epsilon)));
        assertEquals(finiteDifference, ray.physicalPathDerivative(lambda), 1.0e-6);
    }

    private static RingworldDisplayRay ray(RingworldDisplayGeometry geometry, RingworldRenderObserver observer,
                                           double x, double y, double z) {
        return new RingworldDisplayRay(geometry, observer, new RingworldDisplayGeometry.Vector(x, y, z));
    }

    private static RingworldDisplayRay.Hit hit(RingworldDisplayRay.Event event) {
        assertTrue("expected hit but was " + event, event instanceof RingworldDisplayRay.Hit);
        return (RingworldDisplayRay.Hit) event;
    }

    private static void assertHit(RingworldDisplayRay.Event event, double expected, double tolerance) {
        assertEquals(expected, hit(event).lambda(), tolerance);
    }

    private static void assertAxisLimit(RingworldDisplayRay.Event event, double expected, double tolerance) {
        assertTrue("expected axis limit but was " + event, event instanceof RingworldDisplayRay.AxisLimit);
        assertEquals(expected, ((RingworldDisplayRay.AxisLimit) event).lambda(), tolerance);
    }

    private static double square(double value) {
        return value * value;
    }
}
