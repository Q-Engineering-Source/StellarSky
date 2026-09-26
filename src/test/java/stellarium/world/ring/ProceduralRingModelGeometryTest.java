package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import org.junit.Test;

/** Headless numerical contracts for the globally cached first procedural-ring mesh. */
public class ProceduralRingModelGeometryTest {
    private static final double ONE_AU = RingworldDisplayGeometry.DEFAULT_RADIUS_METERS;

    @Test
    public void packedFirstVertexUsesItsActualMinusPiAngleAndMatchesDisplayEmbedding() {
        double radius = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
        double surfaceY = 64.0D;
        var model = new ProceduralRingModelGeometry(radius, surfaceY, -32.0D, 32.0D, 64);
        var mesh = model.mesh();
        assertEquals(64 * 6, mesh.vertexCount());

        // Segment zero starts at theta=-pi, not at the local tangent theta=0.
        var first = point(mesh, 0);
        assertEquals(0.0D, first.x(), 1.0e-6D);
        assertEquals(2.0D * radius - surfaceY, first.y(), 1.0e-5D);
        assertEquals(-32.0D, first.z(), 1.0e-6D);
        assertEquals(0.0D, evaluate(plane(mesh, 0), first), radius * 1.0e-8D);

        double theta = -Math.PI;
        var pose = model.pose(0.0D, surfaceY, 0.0D, 0.0D);
        var positioned = pose.transformPoint(first);
        var expected = new RingworldDisplayGeometry(radius).cameraRelative(new RingworldRenderObserver(0.0D, surfaceY, 0.0D),
                new RingworldDisplayGeometry.Point(theta * radius, surfaceY, -32.0D));
        assertEquals(expected.x(), positioned.x(), 1.0e-5D);
        assertEquals(expected.y(), positioned.y(), 1.0e-5D);
        assertEquals(expected.z(), positioned.z(), 1.0e-6D);
    }

    @Test
    public void packedPlanesRemainSignedRayIntersectableAfterNonZeroPoseAndLargeOrigin() {
        for (double radius : new double[] {RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS, ONE_AU}) {
            var model = new ProceduralRingModelGeometry(radius, 64.0D, -48.0D, 48.0D, 128);
            var mesh = model.mesh();
            int firstVertex = 79 * 6;
            var globalPlane = plane(mesh, firstVertex);
            var pose = model.pose(radius * 0.23D, 96.0D, ONE_AU, 17.25D);
            var posedPlane = pose.transformPlane(globalPlane);
            var a = pose.transformPoint(point(mesh, firstVertex));
            var b = pose.transformPoint(point(mesh, firstVertex + 1));
            var c = pose.transformPoint(point(mesh, firstVertex + 2));
            double tolerance = radius * 1.0e-7D + 1.0e-4D;

            assertEquals(0.0D, evaluate(posedPlane, a), tolerance);
            assertEquals(0.0D, evaluate(posedPlane, b), tolerance);
            assertEquals(0.0D, evaluate(posedPlane, c), tolerance);

            double normalLength = Math.sqrt(posedPlane.nx() * posedPlane.nx()
                    + posedPlane.ny() * posedPlane.ny() + posedPlane.nz() * posedPlane.nz());
            assertEquals(1.0D, normalLength, 1.0e-12D);
            double offset = Math.max(100.0D, tolerance * 10.0D);
            var front = add(a, posedPlane, offset);
            var back = add(a, posedPlane, -offset);
            assertTrue("outward normal must retain positive signed-plane direction", evaluate(posedPlane, front) > 0.0D);
            assertTrue("inward normal must retain negative signed-plane direction", evaluate(posedPlane, back) < 0.0D);

            double denominator = -(posedPlane.nx() * posedPlane.nx() + posedPlane.ny() * posedPlane.ny()
                    + posedPlane.nz() * posedPlane.nz());
            double rayDistance = -evaluate(posedPlane, front) / denominator;
            var hit = add(front, posedPlane, -rayDistance);
            assertEquals(offset, rayDistance, tolerance);
            assertDistance(a, hit, tolerance);
        }
    }

    @Test
    public void posedVertexMatchesDisplayGeometryWithOneCameraOffsetAtRepresentativeTheta() {
        for (double radius : new double[] {RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS, ONE_AU}) {
            int segments = 128;
            double surfaceY = 64.0D;
            var model = new ProceduralRingModelGeometry(radius, surfaceY, -32.0D, 32.0D, segments);
            int segment = 73;
            double theta = -Math.PI + (2.0D * Math.PI * segment) / segments;
            var global = point(model.mesh(), segment * 6);
            double opticalEyeX = radius * 0.23D;
            double observerY = 48.0D;
            double observerZ = ONE_AU;
            double thirdPersonX = 17.25D;
            var actual = model.pose(opticalEyeX, observerY, observerZ, thirdPersonX).transformPoint(global);
            var display = new RingworldDisplayGeometry(radius).cameraRelative(
                    new RingworldRenderObserver(opticalEyeX, observerY, observerZ),
                    new RingworldDisplayGeometry.Point(theta * radius, surfaceY, -32.0D));
            double tolerance = Math.max(1.0e-4D, radius * 1.0e-11D);
            assertEquals(display.x() + thirdPersonX, actual.x(), tolerance);
            assertEquals(display.y(), actual.y(), tolerance);
            assertEquals(display.z(), actual.z(), tolerance);
        }
    }

    @Test
    public void projected4096SegmentChordErrorStaysBelowQuarterPixelAtOneTenthRadius() {
        for (double radius : new double[] {RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS, ONE_AU}) {
            double radialSurface = radius - 64.0D;
            double halfAngle = Math.PI / ProceduralRingModelGeometry.DEFAULT_ANGULAR_SEGMENTS;
            double chordErrorMeters = radialSurface * (1.0D - Math.cos(halfAngle));
            for (int width : new int[] {1_920, 3_840}) {
                double focalPixels = width / (2.0D * Math.tan(Math.toRadians(70.0D) * 0.5D));
                double conservativePixels = chordErrorMeters * focalPixels / (radius * 0.1D);
                assertTrue("4096-sided chord error must remain sub-quarter-pixel at " + width + " px",
                        conservativePixels < 0.25D);
            }
        }
    }

    @Test
    public void cloudSegmentPolicyKeepsItsChordSagWithinTheDeclared64MeterBoundWithoutBuildingMeshes() {
        for (double radius : new double[] {RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS, ONE_AU}) {
            int segments = ProceduralRingModelGeometry.cloudSegments(radius);
            assertTrue(segments >= ProceduralRingModelGeometry.DEFAULT_ANGULAR_SEGMENTS);
            assertTrue(segments <= ProceduralRingModelGeometry.MAX_ANGULAR_SEGMENTS);
            int refinement = segments / ProceduralRingModelGeometry.DEFAULT_ANGULAR_SEGMENTS;
            assertTrue("cloud segment count is a power-of-two refinement of the cached base mesh",
                    refinement > 0 && (refinement & (refinement - 1)) == 0);
            double sag = 2.0D * radius * Math.pow(Math.sin(Math.PI / (2.0D * segments)), 2.0D);
            assertTrue("selected cloud chord sag must not exceed 64m", sag <= 64.0D);
            if (segments > ProceduralRingModelGeometry.DEFAULT_ANGULAR_SEGMENTS) {
                double coarserSag = 2.0D * radius * Math.pow(Math.sin(Math.PI / segments), 2.0D);
                assertTrue("the previous power-of-two count must be insufficient", coarserSag > 64.0D);
            }
        }
    }

    @Test
    public void meshIsGlobalAndBoundedWhilePoseMovesAndUvSeamIsExplicit() {
        var model = new ProceduralRingModelGeometry(ONE_AU, 64.0D, -8192.0D, 8192.0D);
        var mesh = model.mesh();
        assertSame(mesh, model.mesh());
        assertTrue(mesh.byteCount() <= 6L * 4096 * ProceduralRingModelGeometry.FLOATS_PER_VERTEX * Float.BYTES);
        assertEquals(0.0F, mesh.component(0, 14), 0.0F);
        int lastSegment = ProceduralRingModelGeometry.DEFAULT_ANGULAR_SEGMENTS - 1;
        assertEquals(1.0F, mesh.component(lastSegment * 6 + 1, 14), 0.0F);
        assertEquals(1.0F, mesh.component(lastSegment * 6 + 2, 14), 0.0F);
        FloatBuffer buffer = FloatBuffer.allocate(mesh.floatCount());
        mesh.writeTo(buffer);
        assertEquals(mesh.floatCount(), buffer.position());
    }

    private static ProceduralRingModelGeometry.Point point(ProceduralRingModelGeometry.Mesh mesh, int vertex) {
        return new ProceduralRingModelGeometry.Point((double) mesh.component(vertex, 0) + mesh.component(vertex, 3),
                (double) mesh.component(vertex, 1) + mesh.component(vertex, 4),
                (double) mesh.component(vertex, 2) + mesh.component(vertex, 5));
    }

    private static ProceduralRingModelGeometry.Plane plane(ProceduralRingModelGeometry.Mesh mesh, int vertex) {
        return new ProceduralRingModelGeometry.Plane((double) mesh.component(vertex, 6) + mesh.component(vertex, 10),
                (double) mesh.component(vertex, 7) + mesh.component(vertex, 11),
                (double) mesh.component(vertex, 8) + mesh.component(vertex, 12),
                (double) mesh.component(vertex, 9) + mesh.component(vertex, 13));
    }

    private static double evaluate(ProceduralRingModelGeometry.Plane plane, ProceduralRingModelGeometry.Point point) {
        return plane.nx() * point.x() + plane.ny() * point.y() + plane.nz() * point.z() + plane.d();
    }

    private static ProceduralRingModelGeometry.Point add(ProceduralRingModelGeometry.Point point,
                                                          ProceduralRingModelGeometry.Plane direction,
                                                          double distance) {
        return new ProceduralRingModelGeometry.Point(point.x() + direction.nx() * distance,
                point.y() + direction.ny() * distance, point.z() + direction.nz() * distance);
    }

    private static void assertDistance(ProceduralRingModelGeometry.Point expected,
                                       ProceduralRingModelGeometry.Point actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }
}
