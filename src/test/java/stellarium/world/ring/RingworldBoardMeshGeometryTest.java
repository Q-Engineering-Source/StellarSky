package stellarium.world.ring;

import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Behavior tests for the bounded, static C32 curved-board shell. */
public class RingworldBoardMeshGeometryTest {
    private static final double C32_RADIUS = RingworldDisplayGeometry.DEFAULT_RADIUS_METERS * 0.000292;
    private static final double BASE_Y = 6_400_000.0;
    private static final RingworldBoardMeshGeometry.Mesh CURRENT_THICKNESS =
            RingworldBoardMeshGeometry.build(new RingworldDisplayGeometry(C32_RADIUS), BASE_Y, 32.0);

    @Test
    public void c32MeshUsesGlobal8192GridAndContinuousFourFaceShell() {
        assertEquals(33_506, CURRENT_THICKNESS.segmentCount());
        assertEquals(33_506 * 24, CURRENT_THICKNESS.vertexCount());
        assertEquals(CURRENT_THICKNESS.vertexCount() * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX,
                CURRENT_THICKNESS.floatCount());
        assertEquals(-Math.PI * C32_RADIUS, CURRENT_THICKNESS.canonicalMinX(), 1.0e-6);
        assertEquals(Math.PI * C32_RADIUS, CURRENT_THICKNESS.canonicalMaxX(), 1.0e-6);
        assertTrue(CURRENT_THICKNESS.segmentCount() < RingworldBoardMeshGeometry.MAX_SEGMENTS);

        FloatBuffer data = CURRENT_THICKNESS.vertexBuffer();
        Set<Integer> faces = new HashSet<>();
        double minPhysicalX = Double.POSITIVE_INFINITY;
        double maxPhysicalX = Double.NEGATIVE_INFINITY;
        double minLocalY = Double.POSITIVE_INFINITY;
        double maxLocalY = Double.NEGATIVE_INFINITY;
        double minLocalZ = Double.POSITIVE_INFINITY;
        double maxLocalZ = Double.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < CURRENT_THICKNESS.vertexCount(); vertex++) {
            int offset = vertex * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX;
            int face = (int) data.get(offset + 19);
            faces.add(face);
            double physicalX = highLow(data, offset + 17, offset + 18) + data.get(offset + 14);
            minPhysicalX = Math.min(minPhysicalX, physicalX);
            maxPhysicalX = Math.max(maxPhysicalX, physicalX);
            minLocalY = Math.min(minLocalY, data.get(offset + 15));
            maxLocalY = Math.max(maxLocalY, data.get(offset + 15));
            minLocalZ = Math.min(minLocalZ, data.get(offset + 16));
            maxLocalZ = Math.max(maxLocalZ, data.get(offset + 16));
            assertTrue("local X stays inside its 8192 m global grid cell",
                    data.get(offset + 14) >= -1.0e-6 && data.get(offset + 14) <= 8_192.001);
        }
        assertEquals(Set.of(RingworldBoardMeshGeometry.FACE_TOP, RingworldBoardMeshGeometry.FACE_UNDERSIDE,
                RingworldBoardMeshGeometry.FACE_NEGATIVE_STRIP_SIDE,
                RingworldBoardMeshGeometry.FACE_POSITIVE_STRIP_SIDE), faces);
        assertEquals(CURRENT_THICKNESS.canonicalMinX(), minPhysicalX, 0.02);
        assertEquals(CURRENT_THICKNESS.canonicalMaxX(), maxPhysicalX, 0.02);
        assertEquals(0.0, minLocalY, 0.0);
        assertEquals(32.0, maxLocalY, 0.0);
        assertEquals(0.0, minLocalZ, 0.0);
        assertEquals(16_384.0, maxLocalZ, 0.0);
    }

    @Test
    public void highLowPositionsReconstructTheEmbeddedPhysicalVertices() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(C32_RADIUS);
        FloatBuffer data = CURRENT_THICKNESS.vertexBuffer();
        // The first segment has one triangle pair for each of the four continuous faces.
        for (int vertex : new int[] {0, 6, 12, 18}) {
            int offset = vertex * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX;
            double physicalX = highLow(data, offset + 17, offset + 18) + data.get(offset + 14);
            double physicalY = BASE_Y + data.get(offset + 15);
            double physicalZ = RingworldStripBounds.BOARD_MIN_Z + data.get(offset + 16);
            RingworldDisplayGeometry.Point expected = geometry.cameraRelative(new RingworldRenderObserver(0.0, 0.0, 0.0),
                    new RingworldDisplayGeometry.Point(physicalX, physicalY, physicalZ));
            assertEquals(expected.x(), highLow(data, offset, offset + 3), 0.01);
            assertEquals(expected.y(), highLow(data, offset + 1, offset + 4), 0.01);
            assertEquals(expected.z(), highLow(data, offset + 2, offset + 5), 1.0e-6);
        }
    }

    @Test
    public void everyTriangleCarriesThePlaneOfItsActualEmbeddedVertices() {
        FloatBuffer data = CURRENT_THICKNESS.vertexBuffer();
        // Sample the beginning, a middle batch, and the two terminal triangles of all four faces.
        int[] triangleStarts = {
                0,
                3 * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX,
                64 * 24 * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX,
                (CURRENT_THICKNESS.vertexCount() - 6) * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX,
                (CURRENT_THICKNESS.vertexCount() - 3) * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX
        };
        for (int triangleStart : triangleStarts) {
            double planeX = highLow(data, triangleStart + 6, triangleStart + 10);
            double planeY = highLow(data, triangleStart + 7, triangleStart + 11);
            double planeZ = highLow(data, triangleStart + 8, triangleStart + 12);
            double distance = highLow(data, triangleStart + 9, triangleStart + 13);
            assertEquals(1.0, Math.sqrt(planeX * planeX + planeY * planeY + planeZ * planeZ), 3.0e-7);
            for (int corner = 0; corner < 3; corner++) {
                int vertex = triangleStart + corner * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX;
                double x = highLow(data, vertex, vertex + 3);
                double y = highLow(data, vertex + 1, vertex + 4);
                double z = highLow(data, vertex + 2, vertex + 5);
                assertEquals("plane contains its emitted embedded vertex", distance,
                        planeX * x + planeY * y + planeZ * z, 0.03);
            }
        }
    }

    @Test
    public void batchesPartitionThePackedTriangleListAndContainEveryVertex() {
        FloatBuffer data = CURRENT_THICKNESS.vertexBuffer();
        int expectedFirst = 0;
        for (RingworldBoardMeshGeometry.Batch batch : CURRENT_THICKNESS.batches()) {
            assertEquals(expectedFirst, batch.vertexFirst());
            assertTrue(batch.vertexCount() > 0 && batch.vertexCount() <= 64 * 24);
            assertEquals(0, batch.vertexCount() % 3);
            for (int vertex = batch.vertexFirst(); vertex < batch.vertexFirst() + batch.vertexCount(); vertex++) {
                int offset = vertex * RingworldBoardMeshGeometry.FLOATS_PER_VERTEX;
                double x = highLow(data, offset, offset + 3);
                double y = highLow(data, offset + 1, offset + 4);
                double z = highLow(data, offset + 2, offset + 5);
                assertTrue(x >= batch.minDisplayX() - 1.0e-6 && x <= batch.maxDisplayX() + 1.0e-6);
                assertTrue(y >= batch.minDisplayY() - 1.0e-6 && y <= batch.maxDisplayY() + 1.0e-6);
                assertTrue(z >= batch.minDisplayZ() - 1.0e-6 && z <= batch.maxDisplayZ() + 1.0e-6);
            }
            expectedFirst += batch.vertexCount();
        }
        assertEquals(CURRENT_THICKNESS.vertexCount(), expectedFirst);
    }

    @Test
    public void legacyEightAndCurrentThirtyTwoBlockSlabsAreBothRepresented() {
        RingworldBoardMeshGeometry.Mesh legacy =
                RingworldBoardMeshGeometry.build(new RingworldDisplayGeometry(C32_RADIUS), BASE_Y, 8.0);
        assertEquals(CURRENT_THICKNESS.segmentCount(), legacy.segmentCount());
        assertEquals(8.0, legacy.thickness(), 0.0);
        assertEquals(32.0, CURRENT_THICKNESS.thickness(), 0.0);
        assertEquals(BASE_Y, legacy.baseY(), 0.0);
    }

    @Test
    public void defaultOneAuGeometryIsExplicitlyOutsideTheFirstReleaseMeshBudget() {
        assertThrows(RingworldBoardMeshGeometry.UnsupportedGeometryException.class,
                () -> RingworldBoardMeshGeometry.build(new RingworldDisplayGeometry(), BASE_Y, 32.0));
    }

    @Test
    public void copyAndUploadAccessorsDoNotExposeTheCachedPackedData() {
        RingworldBoardMeshGeometry.Mesh tiny = RingworldBoardMeshGeometry.build(
                new RingworldDisplayGeometry(10_000.0), 100.0, 8.0);
        float[] first = tiny.vertexData();
        float original = first[0];
        first[0] = 12345.0f;
        assertEquals(original, tiny.vertexData()[0], 0.0f);
        FloatBuffer target = FloatBuffer.allocate(tiny.floatCount());
        tiny.writeTo(target);
        assertEquals(tiny.floatCount(), target.position());
        assertTrue(tiny.vertexBuffer().isReadOnly());
        assertThrows(IllegalArgumentException.class, () -> tiny.writeTo(FloatBuffer.allocate(tiny.floatCount() - 1)));
        assertFalse(tiny.batches().isEmpty());
    }

    private static double highLow(FloatBuffer data, int highIndex, int lowIndex) {
        return (double) data.get(highIndex) + data.get(lowIndex);
    }

    @Test
    public void panelWallsHaveRealBoundaryFacesAndShareTheShellChord() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(C32_RADIUS);
        for (int orientation : new int[] {-1, 1}) {
            var bands = new RingworldSunshade.CameraRelativeBands(1.0, 0.0,
                    40_176_000.0, 20_088_000.0, 20_088_000.0, 1234.5, orientation,
                    RingworldSunshade.BandCoverage.PARTIAL);
            var walls = RingworldBoardMeshGeometry.buildPanelWalls(geometry, 4096.0, 32.0, bands, 3.25, 37.0);
            assertTrue(walls.vertexCount() > 0);
            FloatBuffer data = walls.vertexBuffer();
            Set<Integer> faces = new HashSet<>();
            for (int offset = 0; offset < data.limit(); offset += 20) {
                double tile = highLow(data, offset + 17, offset + 18);
                double s = tile + data.get(offset + 14);
                double y = 4096.0 + data.get(offset + 15);
                double z = -8192.0 + data.get(offset + 16);
                int face = (int) data.get(offset + 19);
                faces.add(face);
                double phase = orientation * (s + 3.25 - 1234.5);
                double boundary = face == 4 ? 0.0 : 20_088_000.0;
                assertEquals(Math.rint((phase - boundary) / 40_176_000.0),
                        (phase - boundary) / 40_176_000.0, 1.0e-10);
                double x0 = Math.max(-geometry.circumferenceMeters() * 0.5, tile);
                double x1 = Math.min(geometry.circumferenceMeters() * 0.5, tile + 8192.0);
                double t = (s - x0) / (x1 - x0);
                var eye = new RingworldRenderObserver(0, 0, 0);
                var a = geometry.cameraRelative(eye, new RingworldDisplayGeometry.Point(x0, y, z));
                var b = geometry.cameraRelative(eye, new RingworldDisplayGeometry.Point(x1, y, z));
                assertEquals(a.x() + (b.x() - a.x()) * t, highLow(data, offset, offset + 3), 1.0e-5);
                assertEquals(a.y() + (b.y() - a.y()) * t, highLow(data, offset + 1, offset + 4), 1.0e-5);
            }
            assertEquals(Set.of(4, 6), faces);
        }
    }

    @Test
    public void fullAndEmptyCoverageHaveNoArtificialPeriodicWalls() {
        for (var coverage : new RingworldSunshade.BandCoverage[] {
                RingworldSunshade.BandCoverage.EMPTY, RingworldSunshade.BandCoverage.FULL}) {
            var bands = new RingworldSunshade.CameraRelativeBands(1.0, 0.0, 256.0,
                    coverage == RingworldSunshade.BandCoverage.FULL ? 256.0 : 0.0,
                    coverage == RingworldSunshade.BandCoverage.FULL ? 0.0 : 256.0, 0.0, 0, coverage);
            assertEquals(0, RingworldBoardMeshGeometry.buildPanelWalls(new RingworldDisplayGeometry(C32_RADIUS),
                    4096.0, 32.0, bands, 0, 0).vertexCount());
        }
    }

    @Test
    public void obliqueAndAxialWallsStayInTheirPhysicalStripAndSubdivision() {
        for (double[] heading : new double[][] {{0.6, 0.8}, {-0.6, 0.8}, {0.0, 1.0}, {-1.0, 0.0}}) {
            var bands = new RingworldSunshade.CameraRelativeBands(heading[0], heading[1], 16384.0,
                    8192.0, 8192.0, 100.0, 1, RingworldSunshade.BandCoverage.PARTIAL);
            var walls = RingworldBoardMeshGeometry.buildPanelWalls(new RingworldDisplayGeometry(20000.0),
                    512.0, 8.0, bands, -12.0, 351.0);
            assertTrue(walls.vertexCount() > 0);
            FloatBuffer vertices = walls.vertexBuffer();
            for (int offset = 0; offset < vertices.limit(); offset += 20) {
                assertTrue(vertices.get(offset + 14) >= 0.0F && vertices.get(offset + 14) <= 8192.0F);
                assertTrue(vertices.get(offset + 16) >= 0.0F && vertices.get(offset + 16) <= 16384.0F);
                double s = highLow(vertices, offset + 17, offset + 18) + vertices.get(offset + 14);
                double z = vertices.get(offset + 16) - 8192.0;
                double projected = heading[0] * (s - 12.0) + heading[1] * (z - 351.0) - 100.0;
                double boundary = vertices.get(offset + 19) == 4.0F ? 0.0 : 8192.0;
                assertEquals(Math.rint((projected - boundary) / 16384.0),
                        (projected - boundary) / 16384.0, 1.0e-7);
            }
        }
    }

    @Test
    public void denseWallsFailWithAnExplicitBudgetRatherThanTruncatingCoverage() {
        var bands = new RingworldSunshade.CameraRelativeBands(1.0, 0.0, 256.0, 128.0, 128.0,
                0.0, 1, RingworldSunshade.BandCoverage.PARTIAL);
        assertThrows(RingworldBoardMeshGeometry.WallBudgetException.class,
                () -> RingworldBoardMeshGeometry.buildPanelWalls(new RingworldDisplayGeometry(C32_RADIUS),
                        512.0, 8.0, bands, 0.0, 0.0));
    }
}
