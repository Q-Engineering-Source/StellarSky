package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldCurvedBoundsTest {

    @Test
    public void analyticBoundsContainDenseSamplesOfTheWholePhysicalBox() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(200.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(17.0, 25.0, -30.0);
        RingworldCurvedBounds.PhysicalAabb box = new RingworldCurvedBounds.PhysicalAabb(
                -160.0, 490.0, -40.0, 80.0, -12.0, 44.0);

        RingworldCurvedBounds.CameraRelativeAabb bounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, box);

        assertDenseBoxContained(geometry, eye, box, bounds, 96, 8, 4);
    }

    @Test
    public void interiorQuarterTurnExtremumIsIncludedEvenThoughEveryCornerMissesIt() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(100.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 0.0, 0.0);
        RingworldCurvedBounds.PhysicalAabb box = new RingworldCurvedBounds.PhysicalAabb(
                0.0, Math.PI * 100.0, 0.0, 10.0, -2.0, 2.0);
        RingworldCurvedBounds.CameraRelativeAabb bounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, box);

        RingworldDisplayGeometry.Point interior = geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(Math.PI * 50.0, 0.0, 0.0));
        assertTrue(bounds.contains(interior));
        assertEquals(100.0, bounds.maxX(), 1.0e-12);
        assertEquals(0.0, geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(0.0, 0.0, 0.0)).x(), 0.0);
        assertEquals(0.0, geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(Math.PI * 100.0, 0.0, 0.0)).x(), 1.0e-12);
    }

    @Test
    public void seamAntipodeAndFullCircumferenceUseAllCardinalExtrema() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(100.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 0.0, 10.0);
        double circumference = geometry.circumferenceMeters();
        RingworldCurvedBounds.PhysicalAabb seamBox = new RingworldCurvedBounds.PhysicalAabb(
                Math.PI * 100.0 - 20.0, Math.PI * 100.0 + 20.0, -10.0, 10.0, 3.0, 7.0);
        RingworldCurvedBounds.CameraRelativeAabb seamBounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, seamBox);
        assertDenseBoxContained(geometry, eye, seamBox, seamBounds, 64, 3, 2);

        RingworldCurvedBounds.PhysicalAabb fullBox = new RingworldCurvedBounds.PhysicalAabb(
                -20.0, circumference + 1.0, 0.0, 10.0, -4.0, 6.0);
        RingworldCurvedBounds.CameraRelativeAabb fullBounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, fullBox);
        assertTrue(fullBounds.minX() <= -100.0);
        assertTrue(fullBounds.maxX() >= 100.0);
        assertTrue(fullBounds.minY() <= 0.0);
        assertTrue(fullBounds.maxY() >= 200.0);
        assertTrue(fullBounds.minZ() <= -14.0);
        assertTrue(fullBounds.maxZ() >= -4.0);
    }

    @Test
    public void oneAuNearBoxRemainsCloseToTheCameraInsteadOfPayingForAWholeRing() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        RingworldRenderObserver eye = new RingworldRenderObserver(10_000.0, 64.0, 5.0);
        RingworldCurvedBounds.PhysicalAabb box = new RingworldCurvedBounds.PhysicalAabb(
                1_808.0, 18_192.0, 60.0, 80.0, -10.0, 20.0);

        RingworldCurvedBounds.CameraRelativeAabb bounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, box);
        assertDenseBoxContained(geometry, eye, box, bounds, 64, 2, 2);
        assertTrue(bounds.maxY() < 17.0);
        assertTrue(bounds.minY() > -5.0);
        assertTrue(bounds.maxX() < 8_193.0);
        assertTrue(bounds.minX() > -8_193.0);
    }

    @Test
    public void translationPreservesCameraRelativeBoundsAndTransverseEndpoints() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(500.0);
        RingworldRenderObserver originEye = new RingworldRenderObserver(0.0, 30.0, 0.0);
        RingworldCurvedBounds.PhysicalAabb originBox = new RingworldCurvedBounds.PhysicalAabb(
                -220.0, 180.0, -40.0, 50.0, -9.0, 11.0);
        RingworldCurvedBounds.CameraRelativeAabb originBounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, originEye, originBox);

        RingworldRenderObserver translatedEye = new RingworldRenderObserver(20_000.0, 30.0, -70.0);
        RingworldCurvedBounds.PhysicalAabb translatedBox = new RingworldCurvedBounds.PhysicalAabb(
                19_780.0, 20_180.0, -40.0, 50.0, -79.0, -59.0);
        RingworldCurvedBounds.CameraRelativeAabb translatedBounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, translatedEye, translatedBox);

        assertEquals(originBounds.minX(), translatedBounds.minX(), 1.0e-12);
        assertEquals(originBounds.maxX(), translatedBounds.maxX(), 1.0e-12);
        assertEquals(originBounds.minY(), translatedBounds.minY(), 1.0e-12);
        assertEquals(originBounds.maxY(), translatedBounds.maxY(), 1.0e-12);
        assertEquals(originBounds.minZ(), translatedBounds.minZ(), 0.0);
        assertEquals(originBounds.maxZ(), translatedBounds.maxZ(), 0.0);
        assertFalse(originBounds.contains(new RingworldDisplayGeometry.Point(10_000.0, 0.0, 0.0)));
    }

    @Test
    public void rejectsNonFiniteInvalidAndCylinderAxisBoxes() {
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldCurvedBounds.PhysicalAabb(1.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldCurvedBounds.PhysicalAabb(Double.NaN, 1.0, 0.0, 0.0, 0.0, 0.0));

        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(100.0);
        RingworldRenderObserver observer = new RingworldRenderObserver(0.0, 20.0, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> RingworldCurvedBounds.cameraRelativeAabb(geometry, observer,
                        new RingworldCurvedBounds.PhysicalAabb(0.0, 1.0, 0.0, 100.0, 0.0, 1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> RingworldCurvedBounds.cameraRelativeAabb(geometry,
                        new RingworldRenderObserver(0.0, 100.0, 0.0),
                        new RingworldCurvedBounds.PhysicalAabb(0.0, 1.0, 0.0, 1.0, 0.0, 1.0)));
    }

    @Test
    public void physicalEndpointsAreHardBoundsEvenWhenSpanAngleRoundsDifferently() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 64.0, -10.0);
        RingworldCurvedBounds.PhysicalAabb box = new RingworldCurvedBounds.PhysicalAabb(
                -348_560_363_791.6764, -67_402_691_712.46515,
                -2_309.9544052091937, 7_869.621344194937, -30.0, 40.0);

        RingworldCurvedBounds.CameraRelativeAabb bounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, box);
        RingworldDisplayGeometry.Point actualEndpoint = geometry.cameraRelative(eye,
                new RingworldDisplayGeometry.Point(box.maxX(), box.minY(), box.maxZ()));

        assertTrue("max-X/min-Y endpoint escaped the curved bounds", bounds.contains(actualEndpoint));
    }

    @Test
    public void endpointsRemainHardBoundsAcrossTheCanonicalSeamForALargeObserverX() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1_000.0);
        double circumference = geometry.circumferenceMeters();
        RingworldRenderObserver eye = new RingworldRenderObserver(20.0 * circumference + 123.0, 64.0, 30.0);
        RingworldCurvedBounds.PhysicalAabb box = new RingworldCurvedBounds.PhysicalAabb(
                eye.x() + Math.PI * 1_000.0 - 5.0, eye.x() + Math.PI * 1_000.0 + 7.0,
                -100.0, 300.0, -20.0, 40.0);
        RingworldCurvedBounds.CameraRelativeAabb bounds =
                RingworldCurvedBounds.cameraRelativeAabb(geometry, eye, box);

        for (double x : new double[]{box.minX(), box.maxX()}) {
            for (double y : new double[]{box.minY(), box.maxY()}) {
                for (double z : new double[]{box.minZ(), box.maxZ()}) {
                    assertTrue(bounds.contains(geometry.cameraRelative(eye,
                            new RingworldDisplayGeometry.Point(x, y, z))));
                }
            }
        }
    }

    private static void assertDenseBoxContained(RingworldDisplayGeometry geometry,
                                                RingworldRenderObserver eye,
                                                RingworldCurvedBounds.PhysicalAabb box,
                                                RingworldCurvedBounds.CameraRelativeAabb bounds,
                                                int xSteps, int ySteps, int zSteps) {
        for (int xIndex = 0; xIndex <= xSteps; xIndex++) {
            double x = interpolate(box.minX(), box.maxX(), xIndex, xSteps);
            for (int yIndex = 0; yIndex <= ySteps; yIndex++) {
                double y = interpolate(box.minY(), box.maxY(), yIndex, ySteps);
                for (int zIndex = 0; zIndex <= zSteps; zIndex++) {
                    double z = interpolate(box.minZ(), box.maxZ(), zIndex, zSteps);
                    RingworldDisplayGeometry.Point display = geometry.cameraRelative(eye,
                            new RingworldDisplayGeometry.Point(x, y, z));
                    assertTrue("outside curved bounds at " + x + "," + y + "," + z,
                            bounds.contains(display));
                }
            }
        }
    }

    private static double interpolate(double minimum, double maximum, int index, int steps) {
        return minimum + (maximum - minimum) * index / steps;
    }
}
