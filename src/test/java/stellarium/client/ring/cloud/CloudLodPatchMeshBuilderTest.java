package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import stellarium.world.ring.RingworldDisplayGeometry;

/** CPU contracts for immutable cached-page curved cloud patches. */
public class CloudLodPatchMeshBuilderTest {
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12.0D, 4.0D, 48);
    private static final double RADIUS = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
    private static final double BASE_Y = 100.0D;
    private static final CloudClipBounds OPEN_AIR = new CloudClipBounds(0.0D, 1_000.0D, -8_192.0D, 8_192.0D);

    @Test
    public void cachedMaterialChartKeepsUnwrappedLongitudeAndSignedAxesWithoutGrowingPayload() {
        for (double radius : new double[]{RADIUS, 149_597_870_700.0D}) {
            for (long origin : new long[]{-1_000_000_000L, -1376L, 0L, 1_000_000_000L}) {
                CloudWorldCache cache = CloudWorldCache.around(2L, new CloudFieldSettings(8L, 1.0D, false, 0.2D),
                        GEOMETRY, origin, 0L);
                var page = cache.page(CloudLodLayout.LOD0_FINE_3D);
                var mesh = CloudLodPatchMeshBuilder.build(page, GEOMETRY, BASE_Y, OPEN_AIR, radius);
                assertEquals(148L * mesh.quadCount(), mesh.byteCount());
                float[] data = mesh.copyQuadData();
                for (int q = 0; q < mesh.quadCount(); q++) {
                    double center = mesh.materialCenterX(q);
                    double min = page.originX() * GEOMETRY.cellSizeBlocks();
                    double max = (page.originX() + page.level().width()) * GEOMETRY.cellSizeBlocks();
                    assertTrue(center >= min - 1.0e-4D && center <= max + 1.0e-4D);
                    double a = chartWorldX(mesh, q, 0, radius, center);
                    double d = chartWorldX(mesh, q, 3, radius, center);
                    assertEquals(center, a + (d - a) * 0.5D, 2.0e-3D);
                    int rawAxis = (int) data[q * CloudLodPatchMeshBuilder.FLOATS_PER_QUAD + 32];
                    assertTrue(Math.abs(rawAxis) >= 1 && Math.abs(rawAxis) <= 3);
                    for (int component = 0; component < 3; component++) {
                        assertEquals(Math.abs(rawAxis) == component + 1 ? Math.signum(rawAxis) : 0.0F,
                                mesh.physicalNormalComponent(q, component), 0.0F);
                    }
                }
            }
        }
    }

    private static double chartWorldX(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner,
                                      double radius, double center) {
        double x = (double) mesh.cornerComponent(quad, corner, 0) + mesh.cornerComponent(quad, corner, 3);
        double y = (double) mesh.cornerComponent(quad, corner, 1) + mesh.cornerComponent(quad, corner, 4);
        double value = Math.atan2(x, radius - y) * radius;
        double circumference = Math.PI * 2.0D * radius;
        return value + Math.rint((center - value) / circumference) * circumference;
    }

    @Test
    public void fullThreeDimensionalPagePartitionsOnlyItsSixClosedBoundaryFacesIntoBoundedCurvedChords() {
        CloudWorldCache cache = fullCache();
        CloudWorldCache.Page page = cache.page(CloudLodLayout.LOD0_FINE_3D);
        var mesh = CloudLodPatchMeshBuilder.build(page, GEOMETRY, BASE_Y, OPEN_AIR, RADIUS);

        double cellWidth = GEOMETRY.cellSizeBlocks() * page.level().xzScale();
        double fullWidth = page.level().width() * cellWidth;
        double maximumChordSpan = Math.min(CloudLodPatchMeshBuilder.MAX_CURVED_SPAN_METERS, cellWidth * 16.0D);
        int xPieces = (int) Math.ceil(fullWidth / maximumChordSpan);
        // Top, bottom and both Z sides subdivide along X; the two X end caps do not.
        assertEquals(4 * xPieces + 2, mesh.quadCount());
        assertEquals(mesh.quadCount() * 6, mesh.vertexCount());
        assertEquals(1, mesh.batchCount());
        assertEquals((long) mesh.quadCount() * CloudLodPatchMeshBuilder.FLOATS_PER_QUAD * Float.BYTES,
                mesh.byteCount());
        FloatBuffer instanceData = FloatBuffer.allocate(mesh.floatCount());
        mesh.writeTo(instanceData);
        assertEquals(mesh.floatCount(), instanceData.position());

        double minX = page.originX() * cellWidth;
        double maxX = (page.originX() + page.level().width()) * cellWidth;
        double minZ = page.originZ() * cellWidth;
        double maxZ = (page.originZ() + page.level().depth()) * cellWidth;
        double minY = BASE_Y;
        double maxY = BASE_Y + page.level().layers() * GEOMETRY.thicknessBlocks() * page.level().yScale();
        double tolerance = 1.0e-4D;

        assertWorldXBoundary(mesh, -1.0F, minX, minY, maxY, minZ, maxZ, tolerance);
        assertWorldXBoundary(mesh, 1.0F, maxX, minY, maxY, minZ, maxZ, tolerance);
        assertXPartition(mesh, 0.0F, 1.0F, 0.0F, minX, maxX, maxY, minZ, maxZ,
                maximumChordSpan, xPieces, tolerance);
        assertXPartition(mesh, 0.0F, -1.0F, 0.0F, minX, maxX, minY, minZ, maxZ,
                maximumChordSpan, xPieces, tolerance);
        assertZPartition(mesh, -1.0F, minX, maxX, minY, maxY, minZ,
                maximumChordSpan, xPieces, tolerance);
        assertZPartition(mesh, 1.0F, minX, maxX, minY, maxY, maxZ,
                maximumChordSpan, xPieces, tolerance);
        assertEveryPackedQuadOwnsItsPlane(mesh, RADIUS * 1.0e-7D);
    }

    @Test
    public void clippedVoxelShellCapsAtAirBoundsAndLeavesRgbForTheAtlasLookup() {
        CloudWorldCache cache = fullCache();
        CloudWorldCache.Page page = cache.page(CloudLodLayout.LOD0_FINE_3D);
        CloudClipBounds clipped = new CloudClipBounds(BASE_Y + 1.0D, BASE_Y + 9.0D, -8_192.0D, 8_192.0D);
        var mesh = CloudLodPatchMeshBuilder.build(page, GEOMETRY, BASE_Y, clipped, RADIUS);
        for (int quad = 0; quad < mesh.quadCount(); quad++) for (int corner = 0; corner < 4; corner++) {
            double x = (double) mesh.cornerComponent(quad, corner, 0) + mesh.cornerComponent(quad, corner, 3);
            double embeddedY = (double) mesh.cornerComponent(quad, corner, 1) + mesh.cornerComponent(quad, corner, 4);
            double physicalY = RADIUS - Math.hypot(x, RADIUS - embeddedY);
            assertTrue(physicalY >= clipped.lowerY() - 1.0e-5D && physicalY <= clipped.upperY() + 1.0e-5D);
        }
        assertEveryPackedQuadOwnsItsPlane(mesh, RADIUS * 1.0e-7D);
    }

    @Test
    public void twoDimensionalPatchIsFullEvenWhenItsLocalCachedPageIsClear() {
        CloudWorldCache clear = CloudWorldCache.around(2L, new CloudFieldSettings(8L, 0.0D, false, 0.2D),
                GEOMETRY, 0L, 0L);
        CloudWorldCache.Page page = clear.page(CloudLodLayout.LOD4_2D_HIGH);
        var mesh = CloudLodPatchMeshBuilder.build(page, GEOMETRY, BASE_Y, OPEN_AIR, RADIUS);
        double width = GEOMETRY.cellSizeBlocks() * page.level().xzScale() * page.level().width();
        int xPieces = (int) Math.ceil(width / CloudLodPatchMeshBuilder.MAX_CURVED_SPAN_METERS);
        assertEquals(xPieces * 6, mesh.vertexCount());
        assertEveryPackedQuadOwnsItsPlane(mesh, RADIUS * 1.0e-7D);
    }

    @Test
    public void pageAccessorIsAbsoluteReadOnlyAndRejectsCoordinatesOutsideItsImmutableExtent() {
        CloudWorldCache.Page page = fullCache().page(CloudLodLayout.LOD1_MID_3D);
        int value = page.argbAt(0, page.originX(), page.originZ());
        assertTrue((value >>> 24 & 255) >= 128);
        assertThrows(IllegalArgumentException.class, () -> page.argbAt(-1, page.originX(), page.originZ()));
        assertThrows(IllegalArgumentException.class,
                () -> page.argbAt(0, page.originX() - 1L, page.originZ()));
        assertThrows(IllegalArgumentException.class,
                () -> page.argbAt(page.level().layers(), page.originX(), page.originZ()));
    }

    @Test
    public void adjacentOccupiedCellsWithDifferentCachedRgbDoNotCreateAnInternalGeometryFace() {
        CloudWorldCache cache = CloudWorldCache.around(3L, new CloudFieldSettings(0L, 0.45D, true, 0.20D),
                GEOMETRY, 0L, 0L);
        CloudWorldCache.Page page = cache.page(CloudLodLayout.LOD0_FINE_3D);
        Candidate candidate = findDifferentAdjacentOccupiedCells(page);
        assertTrue("fixture must contain adjacent occupied cells with distinct cached RGB", candidate != null);
        var mesh = CloudLodPatchMeshBuilder.build(page, GEOMETRY, BASE_Y, OPEN_AIR, RADIUS);
        double cellWidth = GEOMETRY.cellSizeBlocks() * page.level().xzScale();
        double boundaryX = (candidate.x() + 1L) * cellWidth;
        double y0 = BASE_Y + candidate.layer() * GEOMETRY.thicknessBlocks() * page.level().yScale();
        double y1 = y0 + GEOMETRY.thicknessBlocks() * page.level().yScale();
        double z0 = candidate.z() * cellWidth, z1 = z0 + cellWidth;
        double centerY = (y0 + y1) * 0.5D;
        double centerZ = (z0 + z1) * 0.5D;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            if (Math.abs(mesh.physicalNormalComponent(quad, 0)) < 0.999F) continue;
            boolean onBoundary = true;
            double minFaceY = Double.POSITIVE_INFINITY, maxFaceY = Double.NEGATIVE_INFINITY;
            double minFaceZ = Double.POSITIVE_INFINITY, maxFaceZ = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                onBoundary &= Math.abs(worldX(mesh, quad, corner) - boundaryX) < 1.0e-3D;
                double y = worldY(mesh, quad, corner);
                double z = packed(mesh, quad, corner, 2);
                minFaceY = Math.min(minFaceY, y);
                maxFaceY = Math.max(maxFaceY, y);
                minFaceZ = Math.min(minFaceZ, z);
                maxFaceZ = Math.max(maxFaceZ, z);
            }
            // Faces on an adjacent layer/Z boundary can touch this cell at an edge or a corner.
            // Only a face that covers both strict interior centers would be an emitted shared face.
            boolean coversCandidateInterior = minFaceY < centerY && centerY < maxFaceY
                    && minFaceZ < centerZ && centerZ < maxFaceZ;
            assertTrue("distinct cached RGB must not split an occupied shared X face",
                    !(onBoundary && coversCandidateInterior));
        }
    }

    private static CloudWorldCache fullCache() {
        return CloudWorldCache.around(1L, new CloudFieldSettings(7L, 1.0D, false, 0.2D), GEOMETRY, 0L, 0L);
    }

    private static Candidate findDifferentAdjacentOccupiedCells(CloudWorldCache.Page page) {
        for (int layer = 0; layer < page.level().layers(); layer++) {
            for (long z = page.originZ(); z < page.originZ() + page.level().depth(); z++) {
                for (long x = page.originX(); x + 1L < page.originX() + page.level().width(); x++) {
                    int left = page.argbAt(layer, x, z), right = page.argbAt(layer, x + 1L, z);
                    if ((left >>> 24 & 255) >= 128 && (right >>> 24 & 255) >= 128
                            && (left & 0x00FFFFFF) != (right & 0x00FFFFFF)) {
                        return new Candidate(layer, x, z);
                    }
                }
            }
        }
        return null;
    }

    private record Candidate(int layer, long x, long z) { }

    private static void assertWorldXBoundary(CloudLodPatchMeshBuilder.PatchMesh mesh, float nx, double expectedX,
                                             double minY, double maxY, double minZ, double maxZ, double tolerance) {
        List<Integer> matching = matchingNormals(mesh, nx, 0.0F, 0.0F);
        assertEquals(1, matching.size());
        int quad = matching.get(0);
        for (int corner = 0; corner < 4; corner++) {
            assertEquals(expectedX, worldX(mesh, quad, corner), tolerance);
        }
        assertRange(mesh, quad, minY, maxY, minZ, maxZ, tolerance);
    }

    private static void assertXPartition(CloudLodPatchMeshBuilder.PatchMesh mesh, float nx, float ny, float nz,
                                         double minX, double maxX, double expectedY, double minZ, double maxZ,
                                         double maximumChordSpan, int expectedPieces, double tolerance) {
        List<Interval> spans = new ArrayList<>();
        for (int quad : matchingNormals(mesh, nx, ny, nz)) {
            double start = Double.POSITIVE_INFINITY;
            double end = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                double x = worldX(mesh, quad, corner);
                start = Math.min(start, x);
                end = Math.max(end, x);
                assertEquals(expectedY, worldY(mesh, quad, corner), tolerance);
            }
            assertRange(mesh, quad, expectedY, expectedY, minZ, maxZ, tolerance);
            assertTrue("each curved chord must remain within its declared world-X span",
                    end - start <= maximumChordSpan + tolerance);
            spans.add(new Interval(start, end));
        }
        assertEquals(expectedPieces, spans.size());
        spans.sort(Comparator.comparingDouble(Interval::start));
        assertEquals(minX, spans.get(0).start(), tolerance);
        double previousEnd = minX;
        for (Interval span : spans) {
            assertEquals("curved chord partitions must not create a boundary gap or overlap", previousEnd,
                    span.start(), tolerance);
            previousEnd = span.end();
        }
        assertEquals(maxX, previousEnd, tolerance);
    }

    private static void assertZPartition(CloudLodPatchMeshBuilder.PatchMesh mesh, float nz,
                                         double minX, double maxX, double minY, double maxY, double expectedZ,
                                         double maximumChordSpan, int expectedPieces, double tolerance) {
        List<Interval> spans = new ArrayList<>();
        for (int quad : matchingNormals(mesh, 0.0F, 0.0F, nz)) {
            double start = Double.POSITIVE_INFINITY;
            double end = Double.NEGATIVE_INFINITY;
            double actualMinY = Double.POSITIVE_INFINITY;
            double actualMaxY = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                double x = worldX(mesh, quad, corner);
                start = Math.min(start, x);
                end = Math.max(end, x);
                actualMinY = Math.min(actualMinY, worldY(mesh, quad, corner));
                actualMaxY = Math.max(actualMaxY, worldY(mesh, quad, corner));
                assertEquals(expectedZ, packed(mesh, quad, corner, 2), tolerance);
            }
            assertEquals(minY, actualMinY, tolerance);
            assertEquals(maxY, actualMaxY, tolerance);
            assertTrue("each curved chord must remain within its declared world-X span",
                    end - start <= maximumChordSpan + tolerance);
            spans.add(new Interval(start, end));
        }
        assertEquals(expectedPieces, spans.size());
        spans.sort(Comparator.comparingDouble(Interval::start));
        assertEquals(minX, spans.get(0).start(), tolerance);
        double previousEnd = minX;
        for (Interval span : spans) {
            assertEquals("curved chord partitions must not create a boundary gap or overlap", previousEnd,
                    span.start(), tolerance);
            previousEnd = span.end();
        }
        assertEquals(maxX, previousEnd, tolerance);
    }

    private static List<Integer> matchingNormals(CloudLodPatchMeshBuilder.PatchMesh mesh, float nx, float ny, float nz) {
        List<Integer> result = new ArrayList<>();
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            float actualX = mesh.physicalNormalComponent(quad, 0);
            float actualY = mesh.physicalNormalComponent(quad, 1);
            float actualZ = mesh.physicalNormalComponent(quad, 2);
            assertTrue("a full solid page must expose only one of its six physical boundary normals",
                    (Math.abs(actualX) == 1.0F && actualY == 0.0F && actualZ == 0.0F)
                            || (Math.abs(actualY) == 1.0F && actualX == 0.0F && actualZ == 0.0F)
                            || (Math.abs(actualZ) == 1.0F && actualX == 0.0F && actualY == 0.0F));
            if (actualX == nx && actualY == ny && actualZ == nz) result.add(quad);
        }
        return result;
    }

    private static void assertRange(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad,
                                    double minY, double maxY, double minZ, double maxZ, double tolerance) {
        double actualMinY = Double.POSITIVE_INFINITY, actualMaxY = Double.NEGATIVE_INFINITY;
        double actualMinZ = Double.POSITIVE_INFINITY, actualMaxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            actualMinY = Math.min(actualMinY, worldY(mesh, quad, corner));
            actualMaxY = Math.max(actualMaxY, worldY(mesh, quad, corner));
            double z = packed(mesh, quad, corner, 2);
            actualMinZ = Math.min(actualMinZ, z);
            actualMaxZ = Math.max(actualMaxZ, z);
        }
        assertEquals(minY, actualMinY, tolerance);
        assertEquals(maxY, actualMaxY, tolerance);
        assertEquals(minZ, actualMinZ, tolerance);
        assertEquals(maxZ, actualMaxZ, tolerance);
    }

    private static double worldX(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner) {
        double x = packed(mesh, quad, corner, 0);
        double embeddedY = packed(mesh, quad, corner, 1);
        return Math.atan2(x, RADIUS - embeddedY) * RADIUS;
    }

    private static double worldY(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner) {
        double x = packed(mesh, quad, corner, 0);
        double embeddedY = packed(mesh, quad, corner, 1);
        return RADIUS - Math.hypot(x, RADIUS - embeddedY);
    }

    private static double packed(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner, int component) {
        return (double) mesh.cornerComponent(quad, corner, component)
                + mesh.cornerComponent(quad, corner, component + 3);
    }

    private record Interval(double start, double end) { }

    private static void assertEveryPackedQuadOwnsItsPlane(CloudLodPatchMeshBuilder.PatchMesh mesh, double tolerance) {
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            double nx = (double) mesh.planeComponent(quad, 0) + mesh.planeComponent(quad, 4);
            double ny = (double) mesh.planeComponent(quad, 1) + mesh.planeComponent(quad, 5);
            double nz = (double) mesh.planeComponent(quad, 2) + mesh.planeComponent(quad, 6);
            double d = (double) mesh.planeComponent(quad, 3) + mesh.planeComponent(quad, 7);
            for (int corner = 0; corner < 4; corner++) {
                double x = (double) mesh.cornerComponent(quad, corner, 0) + mesh.cornerComponent(quad, corner, 3);
                double y = (double) mesh.cornerComponent(quad, corner, 1) + mesh.cornerComponent(quad, corner, 4);
                double z = (double) mesh.cornerComponent(quad, corner, 2) + mesh.cornerComponent(quad, corner, 5);
                assertEquals(0.0D, nx * x + ny * y + nz * z + d, tolerance);
            }
        }
    }
}
