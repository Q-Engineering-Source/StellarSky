package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldDisplayGeometryTest {
    private static final double ONE_AU = RingworldDisplayGeometry.DEFAULT_RADIUS_METERS;

    @Test
    public void defaultRadiusUsesTheExactOneAuDefinitionAndNearTerrainRemainsAlmostFlat() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        RingworldRenderObserver eye = new RingworldRenderObserver(1_024.0, 64.0, -32.0);
        RingworldDisplayGeometry.Point point = new RingworldDisplayGeometry.Point(9_216.0, 64.0, -32.0);

        RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye, point);
        double expectedRise = 2.0 * (ONE_AU - 64.0) * Math.pow(Math.sin(8_192.0 / (2.0 * ONE_AU)), 2.0);

        assertEquals(ONE_AU, geometry.radiusMeters(), 0.0);
        double expectedX = (ONE_AU - 64.0) * Math.sin(8_192.0 / ONE_AU);
        assertEquals(expectedX, display.x(), 1.0e-12);
        assertEquals(expectedRise, display.y(), 1.0e-15);
        assertEquals(0.0002242975, display.y(), 5.0e-11);
        assertEquals(0.0, display.z(), 0.0);
        RingworldDisplayGeometry.Point restored = geometry.worldPoint(eye, display);
        assertEquals(point.x(), restored.x(), 1.0e-9);
        assertEquals(point.y(), restored.y(), 1.0e-9);
        assertEquals(point.z(), restored.z(), 1.0e-12);
    }

    @Test
    public void syntheticRadiusUsesTheSameCircularEmbeddingWithoutBecomingAnIndependentSunDistance() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(131_072.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 64.0, 0.0);
        RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(8_192.0, 64.0, 0.0));

        double expectedRise = 2.0 * (131_072.0 - 64.0)
                * Math.pow(Math.sin(8_192.0 / (2.0 * 131_072.0)), 2.0);
        assertEquals(expectedRise, display.y(), 1.0e-12);
        assertEquals(255.7917182, display.y(), 5.0e-7);
    }

    @Test
    public void forwardAndInversePreserveBelowGroundAndArbitraryObserverCoordinates() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        RingworldRenderObserver eye = new RingworldRenderObserver(12_345.0, 80.0, -700.0);
        RingworldDisplayGeometry.Point physical = new RingworldDisplayGeometry.Point(4_153.0, -120.0, 1_825.5);

        RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye, physical);
        RingworldDisplayGeometry.Point restored = geometry.worldPoint(eye, display);

        assertEquals(physical.x(), restored.x(), 1.0e-9);
        assertEquals(physical.y(), restored.y(), 1.0e-9);
        assertEquals(physical.z(), restored.z(), 1.0e-12);
    }

    @Test
    public void canonicalOffsetClosesAtFullCircumferenceAndUsesOneHalfOpenAntipodeBranch() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        double circumference = geometry.circumferenceMeters();
        double eyeX = 2_000.0;
        double base = 4_096.0;

        assertEquals(base, geometry.canonicalArcOffset(eyeX + base, eyeX), 1.0e-12);
        assertEquals(base, geometry.canonicalArcOffset(eyeX + base + circumference, eyeX), 1.0e-3);
        assertEquals(base, geometry.canonicalArcOffset(eyeX + base - circumference, eyeX), 1.0e-3);
        assertEquals(-circumference * 0.5, geometry.canonicalArcOffset(eyeX + circumference * 0.5, eyeX), 0.0);
        assertEquals(-circumference * 0.5, geometry.canonicalArcOffset(eyeX - circumference * 0.5, eyeX), 0.0);

        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 64.0, 0.0);
        RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(circumference * 0.5, 64.0, 0.0));
        RingworldDisplayGeometry.Point restored = geometry.worldPoint(eye, display);
        assertEquals(-circumference * 0.5, restored.x(), 0.0);
        assertEquals(64.0, restored.y(), 5.0e-5);
    }

    @Test
    public void zeroAngleAndNormalTransformsFollowTheInverseTranspose() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1_024.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(20.0, 64.0, 5.0);
        RingworldDisplayGeometry.Point zero = new RingworldDisplayGeometry.Point(20.0, 32.0, 17.0);
        RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye, zero);
        RingworldDisplayGeometry.Point restored = geometry.worldPoint(eye, display);

        assertEquals(0.0, display.x(), 0.0);
        assertEquals(-32.0, display.y(), 0.0);
        assertEquals(12.0, display.z(), 0.0);
        assertEquals(20.0, restored.x(), 1.0e-12);
        assertEquals(32.0, restored.y(), 1.0e-12);
        assertEquals(17.0, restored.z(), 1.0e-12);

        RingworldDisplayGeometry.Point world = new RingworldDisplayGeometry.Point(276.0, 512.0, 0.0);
        RingworldDisplayGeometry.Vector normal = geometry.transformNormal(eye, world,
                new RingworldDisplayGeometry.Vector(0.0, 1.0, 0.0));
        double angle = geometry.canonicalArcOffset(world.x(), eye.x()) / geometry.radiusMeters();
        assertEquals(-Math.sin(angle), normal.x(), 1.0e-12);
        assertEquals(Math.cos(angle), normal.y(), 1.0e-12);
        assertEquals(0.0, normal.z(), 1.0e-12);
        assertEquals(0.5, geometry.jacobianDeterminant(512.0), 0.0);
    }

    @Test
    public void genericNormalUsesTheSameDeterminantScaledInverseTransposeDirection() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1_000.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 50.0, 0.0);
        RingworldDisplayGeometry.Point world = new RingworldDisplayGeometry.Point(250.0, 200.0, 0.0);
        RingworldDisplayGeometry.Vector input = new RingworldDisplayGeometry.Vector(2.0, -3.0, 4.0);

        RingworldDisplayGeometry.Vector actual = geometry.transformNormal(eye, world, input);
        double angle = 0.25;
        double determinant = 0.8;
        double expectedX = Math.cos(angle) * 2.0 - determinant * Math.sin(angle) * -3.0;
        double expectedY = Math.sin(angle) * 2.0 + determinant * Math.cos(angle) * -3.0;
        double expectedZ = determinant * 4.0;
        double length = Math.hypot(Math.hypot(expectedX, expectedY), expectedZ);
        assertEquals(expectedX / length, actual.x(), 1.0e-12);
        assertEquals(expectedY / length, actual.y(), 1.0e-12);
        assertEquals(expectedZ / length, actual.z(), 1.0e-12);
    }

    @Test
    public void centralSunViewIsCoupledToGeometryRadiusAndRejectsAnInsideSunObserver() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1_000.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 100.0, 300.0);
        // This deliberately small synthetic Sun remains outside the synthetic test ring's observer domain.
        RingworldDisplayGeometry.SunView view = geometry.centralSun(eye, 10.0);
        double distance = Math.hypot(900.0, 300.0);

        assertEquals(distance, view.distanceMeters(), 1.0e-12);
        assertEquals(0.0, view.direction().x(), 0.0);
        assertEquals(900.0 / distance, view.direction().y(), 1.0e-12);
        assertEquals(-300.0 / distance, view.direction().z(), 1.0e-12);
        assertEquals(Math.asin(10.0 / distance), view.angularRadiusRadians(), 1.0e-15);

        RingworldDisplayGeometry smallerRing = new RingworldDisplayGeometry(500.0);
        assertEquals(Math.asin(10.0 / Math.hypot(400.0, 300.0)),
                smallerRing.centralSun(eye, 10.0).angularRadiusRadians(), 1.0e-15);
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldDisplayGeometry(100.0).centralSun(
                        new RingworldRenderObserver(0.0, 0.0, 0.0), 100.0));
    }

    @Test
    public void rejectsNonFiniteValuesCylinderAxisAndStrictHeightDomain() {
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplayGeometry(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplayGeometry(0.0));
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplayGeometry(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplayGeometry(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplayGeometry(Double.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldDisplayGeometry.Point(Double.NaN, 0.0, 0.0));

        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(100.0);
        RingworldRenderObserver validEye = new RingworldRenderObserver(0.0, 20.0, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> geometry.cameraRelative(validEye, new RingworldDisplayGeometry.Point(0.0, 100.0, 0.0)));
        assertThrows(IllegalArgumentException.class,
                () -> geometry.cameraRelative(new RingworldRenderObserver(0.0, 100.0, 0.0),
                        new RingworldDisplayGeometry.Point(0.0, 0.0, 0.0)));
        assertThrows(IllegalArgumentException.class,
                () -> geometry.worldPoint(validEye, new RingworldDisplayGeometry.Point(0.0, 80.0, 0.0)));
        assertThrows(IllegalArgumentException.class,
                () -> geometry.transformNormal(validEye, new RingworldDisplayGeometry.Point(0.0, 0.0, 0.0),
                        new RingworldDisplayGeometry.Vector(0.0, 0.0, 0.0)));
    }

    @Test
    public void nearAntipodeInverseDoesNotAmplifyCancellationAtOneAu() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        RingworldRenderObserver eye = new RingworldRenderObserver(0, 64, 0);
        for (double angularGap : new double[]{1e-3, 1e-5, 1e-6, 1e-7}) {
            RingworldDisplayGeometry.Point world = new RingworldDisplayGeometry.Point(
                    (Math.PI - angularGap) * ONE_AU, 64, 0);
            RingworldDisplayGeometry.Point restored = geometry.worldPoint(eye, geometry.cameraRelative(eye, world));
            assertEquals("height near antipode gap=" + angularGap, 64, restored.y(), .001);
        }
    }

    @Test
    public void inverseKeepsTheObserversUnwrappedLocalCircumferenceBranch() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1000);
        RingworldRenderObserver eye = new RingworldRenderObserver(20 * geometry.circumferenceMeters() + 123, 64, 0);
        RingworldDisplayGeometry.Point world = new RingworldDisplayGeometry.Point(eye.x() + 10, 80, 0);
        RingworldDisplayGeometry.Point restored = geometry.worldPoint(eye, geometry.cameraRelative(eye, world));
        assertEquals(world.x(), restored.x(), 1e-9);
    }

    @Test
    public void finiteNormalMagnitudeDoesNotChangeItsTransformedDirection() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1000);
        RingworldRenderObserver eye = new RingworldRenderObserver(0, 64, 0);
        RingworldDisplayGeometry.Point world = new RingworldDisplayGeometry.Point(250 * Math.PI, -1000, 0);
        RingworldDisplayGeometry.Vector expected = geometry.transformNormal(eye, world,
                new RingworldDisplayGeometry.Vector(1, -1, 0));
        RingworldDisplayGeometry.Vector actual = geometry.transformNormal(eye, world,
                new RingworldDisplayGeometry.Vector(1e308, -1e308, 0));
        assertEquals(expected.x(), actual.x(), 1e-12);
        assertEquals(expected.y(), actual.y(), 1e-12);
    }

    @Test
    public void extremeFiniteInputsKeepCanonicalizationAndRoundTripRepresentable() {
        RingworldDisplayGeometry smallRadius = new RingworldDisplayGeometry(1_000.0);
        double canonical = smallRadius.canonicalArcOffset(1.0e308, 0.0);
        assertTrue(canonical >= -smallRadius.circumferenceMeters() * 0.5);
        assertTrue(canonical < smallRadius.circumferenceMeters() * 0.5);

        RingworldDisplayGeometry largeRadius = new RingworldDisplayGeometry(1.0e307);
        assertEquals(1.0e-307, largeRadius.curvaturePerMeter(), Math.ulp(1.0e-307));
        double arc = Math.PI * 1.0e307 / 3.0;
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 0.0, 0.0);
        RingworldDisplayGeometry.Point physical = new RingworldDisplayGeometry.Point(arc, -1.0e307, 12.0);
        RingworldDisplayGeometry.Point restored = largeRadius.worldPoint(eye,
                largeRadius.cameraRelative(eye, physical));
        assertTrue(Double.isFinite(restored.x()));
        assertTrue(Double.isFinite(restored.y()));
        assertTrue(Double.isFinite(restored.z()));
        assertEquals(physical.x(), restored.x(), Math.ulp(physical.x()) * 8.0);
        assertEquals(physical.y(), restored.y(), Math.ulp(physical.y()) * 8.0);

        RingworldDisplayGeometry.Vector normal = largeRadius.transformNormal(eye, physical,
                new RingworldDisplayGeometry.Vector(1.0e308, -1.0e308, 1.0e308));
        assertTrue(Double.isFinite(normal.x()));
        assertTrue(Double.isFinite(normal.y()));
        assertTrue(Double.isFinite(normal.z()));
    }

    @Test
    public void antipodalForwardHeightDoesNotOverflowBeforeFiniteCancellation() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1e-308);
        RingworldRenderObserver eye = new RingworldRenderObserver(0, 0, 0);
        RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(Math.PI * geometry.radiusMeters(), -1e308, 0));
        assertEquals(1e308, display.y(), 4 * Math.ulp(1e308));
    }
}
