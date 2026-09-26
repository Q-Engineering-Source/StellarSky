package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Direct CPU-mesh coverage checks for greedy cloud-face rectangles. */
public class CloudGreedyMeshTest {
    private static final float EPSILON = 1.0E-4F;

    @Test
    public void sameColourFourByFourSolidBlockCollapsesToItsSixBoxFaces() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(2.0D, 1.0D, 2);
        CloudMask mask = fourByFourBlock();
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0L, 0L, mask, geometry, 0.0D,
                new CloudClipBounds(0.0D, 1.0D, -4.0D, 6.0D));

        CloudMesh mesh = CloudMeshBuilder.build(key);

        assertEquals(6, mesh.quadCount());
        assertEquals(160.0D, surfaceArea(mesh), 1.0E-6D);
        assertExpectedAtomicFaceCoverage(key, true, mesh);
    }

    @Test
    public void centreHoleKeepsItsInnerWallsAndOutwardNormalsAfterRectangleMerging() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(2.0D, 1.0D, 1);
        int[] pixels = new int[9];
        java.util.Arrays.fill(pixels, 0xFF336699);
        pixels[0] = 0;
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0L, 0L,
                new CloudMask(2L, 3, 3, pixels), geometry, 0.0D,
                new CloudClipBounds(0.0D, 1.0D, -2.0D, 4.0D));

        CloudMesh mesh = CloudMeshBuilder.build(key);

        assertEquals(96.0D, surfaceArea(mesh), 1.0E-6D);
        assertExpectedAtomicFaceCoverage(key, true, mesh);
    }

    @Test
    public void steppedLayersRetainTheExposedStepFaces() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(2.0D, 1.0D, 1);
        int[] voxels = new int[3 * 3 * 2];
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                voxels[z * 3 + x] = 0xFF556677;
            }
        }
        // Local x=-1 and x=0 are the wrapped texture columns 2 and 0.
        for (int z = 0; z < 3; z++) {
            voxels[9 + z * 3] = 0xFF556677;
            voxels[9 + z * 3 + 2] = 0xFF556677;
        }
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0L, 0L,
                new CloudMask(3L, 3, 3, 2, voxels), geometry, 0.0D,
                new CloudClipBounds(0.0D, 2.0D, -2.0D, 4.0D));

        assertExpectedAtomicFaceCoverage(key, true, CloudMeshBuilder.build(key));
    }

    @Test
    public void coplanarDifferentRgbFacesRemainSeparate() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(2.0D, 1.0D, 1);
        int[] pixels = new int[9];
        for (int z = 0; z < 3; z++) {
            pixels[z * 3] = 0xFF0000FF;
            pixels[z * 3 + 1] = 0xFFFF0000;
            pixels[z * 3 + 2] = 0xFFFF0000;
        }
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0L, 0L,
                new CloudMask(4L, 3, 3, pixels), geometry, 0.0D,
                new CloudClipBounds(0.0D, 1.0D, -2.0D, 4.0D));

        CloudMesh mesh = CloudMeshBuilder.build(key);

        assertEquals(3, normalQuadCount(mesh, 1));
        assertExpectedAtomicFaceCoverage(key, true, mesh);
    }

    @Test
    public void openWindowDoesNotInventCacheWallsButKeepsPhysicalYAndZCaps() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(2.0D, 1.0D, 0);
        CloudMask mask = new CloudMask(5L, 1, 1, 2, new int[]{0xFFFFFFFF, 0xFFFFFFFF});
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0L, 0L, mask, geometry, 10.0D,
                new CloudClipBounds(11.25D, 11.75D, 0.25D, 1.75D));

        CloudMesh mesh = CloudMeshBuilder.build(key, false);

        assertEquals(4, mesh.quadCount());
        assertExpectedAtomicFaceCoverage(key, false, mesh);
    }

    @Test
    public void fractionalCellsNegativeAnchorAndPhysicalClipsUseSharedPlanesWithoutCracks() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(1_000_000.1D, 1.0D, 1);
        long anchorZ = -1_000_000L;
        double baseZ = anchorZ * geometry.cellSizeBlocks();
        CloudMeshCacheKey key = CloudMeshCacheKey.at(-37L, anchorZ,
                new CloudMask(6L, 1, 1, new int[]{0xFFFFFFFF}), geometry, 0.0D,
                new CloudClipBounds(.25D, .75D, baseZ - geometry.cellSizeBlocks() + .25D,
                        baseZ + 2.0D * geometry.cellSizeBlocks() - .25D));

        CloudMesh mesh = CloudMeshBuilder.build(key);

        assertEquals(6, mesh.quadCount());
        assertExpectedAtomicFaceCoverage(key, true, mesh);
    }

    @Test
    public void defaultFieldHasARealGreedyReductionWithoutErasingItsRgbBoundaries() {
        CloudMask mask = CloudMaskGenerator.defaultMask();
        CloudGeometrySettings geometry = CloudGeometrySettings.DEFAULT;
        double extent = (geometry.visibleCellRadius() + 1.0D) * geometry.cellSizeBlocks();
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0L, 0L, mask, geometry, 0.0D,
                new CloudClipBounds(-4.0D, 40.0D, -extent, extent));

        CloudMesh mesh = CloudMeshBuilder.build(key, false);
        int unmergedFaces = expectedAtomicFaceCount(key, false);

        assertTrue("Greedy meshing should remove at least one same-RGB coplanar face",
                mesh.quadCount() < unmergedFaces);
    }

    private static CloudMask fourByFourBlock() {
        int[] pixels = new int[25];
        for (int localZ = -2; localZ <= 1; localZ++) {
            for (int localX = -2; localX <= 1; localX++) {
                pixels[Math.floorMod(localZ, 5) * 5 + Math.floorMod(localX, 5)] = 0xFF336699;
            }
        }
        return new CloudMask(1L, 5, 5, pixels);
    }

    private static void assertExpectedAtomicFaceCoverage(CloudMeshCacheKey key, boolean closeWindow, CloudMesh mesh) {
        int expected = 0;
        int radius = key.visibleCellRadius();
        float[] vertices = mesh.copyVertices();
        for (int localZ = -radius; localZ <= radius; localZ++) {
            long worldZ = key.anchorCellZ() + localZ;
            for (int localX = -radius; localX <= radius; localX++) {
                long worldX = key.anchorCellX() + localX;
                for (int layer = 0; layer < key.mask().layers(); layer++) {
                    if (!visible(key, worldX, layer, worldZ)) {
                        continue;
                    }
                    int rgb = key.mask().cellArgb(worldX, layer, worldZ) & 0x00FFFFFF;
                    double x0 = localX * key.geometry().cellSizeBlocks();
                    double x1 = (localX + 1.0D) * key.geometry().cellSizeBlocks();
                    double z0 = zLower(key, worldZ) - key.anchorCellZ() * key.geometry().cellSizeBlocks();
                    double z1 = zUpper(key, worldZ) - key.anchorCellZ() * key.geometry().cellSizeBlocks();
                    double y0 = yLower(key, layer);
                    double y1 = yUpper(key, layer);
                    if (!neighbourVisible(key, worldX, layer + 1, worldZ, localX, localZ, closeWindow)) {
                        assertSingleCover(vertices, x0, y1, z0, x1, y1, z1, 1, 1, rgb);
                        expected++;
                    }
                    if (!neighbourVisible(key, worldX, layer - 1, worldZ, localX, localZ, closeWindow)) {
                        assertSingleCover(vertices, x0, y0, z0, x1, y0, z1, 1, -1, rgb);
                        expected++;
                    }
                    if (!neighbourVisible(key, worldX - 1L, layer, worldZ, localX - 1, localZ, closeWindow)) {
                        assertSingleCover(vertices, x0, y0, z0, x0, y1, z1, 0, -1, rgb);
                        expected++;
                    }
                    if (!neighbourVisible(key, worldX + 1L, layer, worldZ, localX + 1, localZ, closeWindow)) {
                        assertSingleCover(vertices, x1, y0, z0, x1, y1, z1, 0, 1, rgb);
                        expected++;
                    }
                    if (!neighbourVisible(key, worldX, layer, worldZ - 1L, localX, localZ - 1, closeWindow)) {
                        assertSingleCover(vertices, x0, y0, z0, x1, y1, z0, 2, -1, rgb);
                        expected++;
                    }
                    if (!neighbourVisible(key, worldX, layer, worldZ + 1L, localX, localZ + 1, closeWindow)) {
                        assertSingleCover(vertices, x0, y0, z1, x1, y1, z1, 2, 1, rgb);
                        expected++;
                    }
                }
            }
        }
        assertEquals(expected, expectedAtomicFaceCount(key, closeWindow));
    }

    private static int expectedAtomicFaceCount(CloudMeshCacheKey key, boolean closeWindow) {
        int count = 0;
        int radius = key.visibleCellRadius();
        for (int localZ = -radius; localZ <= radius; localZ++) {
            long worldZ = key.anchorCellZ() + localZ;
            for (int localX = -radius; localX <= radius; localX++) {
                long worldX = key.anchorCellX() + localX;
                for (int layer = 0; layer < key.mask().layers(); layer++) {
                    if (!visible(key, worldX, layer, worldZ)) {
                        continue;
                    }
                    count += neighbourVisible(key, worldX, layer + 1, worldZ, localX, localZ, closeWindow) ? 0 : 1;
                    count += neighbourVisible(key, worldX, layer - 1, worldZ, localX, localZ, closeWindow) ? 0 : 1;
                    count += neighbourVisible(key, worldX - 1L, layer, worldZ, localX - 1, localZ, closeWindow) ? 0 : 1;
                    count += neighbourVisible(key, worldX + 1L, layer, worldZ, localX + 1, localZ, closeWindow) ? 0 : 1;
                    count += neighbourVisible(key, worldX, layer, worldZ - 1L, localX, localZ - 1, closeWindow) ? 0 : 1;
                    count += neighbourVisible(key, worldX, layer, worldZ + 1L, localX, localZ + 1, closeWindow) ? 0 : 1;
                }
            }
        }
        return count;
    }

    private static boolean neighbourVisible(CloudMeshCacheKey key, long worldX, int layer, long worldZ,
                                            int localX, int localZ, boolean closeWindow) {
        int radius = key.visibleCellRadius();
        return (!closeWindow || (localX >= -radius && localX <= radius && localZ >= -radius && localZ <= radius))
                && visible(key, worldX, layer, worldZ);
    }

    private static boolean visible(CloudMeshCacheKey key, long worldX, int layer, long worldZ) {
        return key.mask().occupiedAt(worldX, layer, worldZ)
                && zUpper(key, worldZ) > zLower(key, worldZ)
                && yUpper(key, layer) > yLower(key, layer);
    }

    private static double zLower(CloudMeshCacheKey key, long worldZ) {
        return Math.max(worldZ * key.geometry().cellSizeBlocks(), key.clipBounds().minWorldZ());
    }

    private static double zUpper(CloudMeshCacheKey key, long worldZ) {
        return Math.min((worldZ + 1.0D) * key.geometry().cellSizeBlocks(), key.clipBounds().maxWorldZ());
    }

    private static double yLower(CloudMeshCacheKey key, int layer) {
        return Math.max(layer * key.geometry().voxelHeightBlocks(), key.clipBounds().lowerY() - key.cloudBaseY());
    }

    private static double yUpper(CloudMeshCacheKey key, int layer) {
        return Math.min((layer + 1.0D) * key.geometry().voxelHeightBlocks(),
                key.clipBounds().upperY() - key.cloudBaseY());
    }

    private static void assertSingleCover(float[] vertices, double ax, double ay, double az,
                                          double bx, double by, double bz, int axis, int sign, int rgb) {
        float point0 = (float) ((ax + bx) * .5D);
        float point1 = (float) ((ay + by) * .5D);
        float point2 = (float) ((az + bz) * .5D);
        int covers = 0;
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            if (coversPointWithNormal(vertices, start, point0, point1, point2, axis, sign)) {
                covers++;
                for (int corner = 0; corner < 4; corner++) {
                    int vertex = start + corner * CloudMesh.FLOATS_PER_VERTEX;
                    assertEquals(((rgb >>> 16) & 0xFF) / 255.0F, vertices[vertex + 3], EPSILON);
                    assertEquals(((rgb >>> 8) & 0xFF) / 255.0F, vertices[vertex + 4], EPSILON);
                    assertEquals((rgb & 0xFF) / 255.0F, vertices[vertex + 5], EPSILON);
                }
            }
        }
        assertEquals("Each exposed source face centre must be covered exactly once", 1, covers);
    }

    private static boolean coversPointWithNormal(float[] vertices, int start, float x, float y, float z,
                                                  int axis, int sign) {
        float[] normal = cross(vertices, start, start + CloudMesh.FLOATS_PER_VERTEX,
                start + 2 * CloudMesh.FLOATS_PER_VERTEX);
        if (normal[axis] * sign <= EPSILON) {
            return false;
        }
        for (int other = 0; other < 3; other++) {
            if (other != axis && Math.abs(normal[other]) > EPSILON) {
                return false;
            }
        }
        float pointOnPlane = coordinate(x, y, z, axis);
        float minFirst = Float.POSITIVE_INFINITY;
        float maxFirst = Float.NEGATIVE_INFINITY;
        float minSecond = Float.POSITIVE_INFINITY;
        float maxSecond = Float.NEGATIVE_INFINITY;
        int firstAxis = (axis + 1) % 3;
        int secondAxis = (axis + 2) % 3;
        for (int corner = 0; corner < 4; corner++) {
            int vertex = start + corner * CloudMesh.FLOATS_PER_VERTEX;
            if (Math.abs(coordinate(vertices, vertex, axis) - pointOnPlane) > EPSILON) {
                return false;
            }
            float first = coordinate(vertices, vertex, firstAxis);
            float second = coordinate(vertices, vertex, secondAxis);
            minFirst = Math.min(minFirst, first);
            maxFirst = Math.max(maxFirst, first);
            minSecond = Math.min(minSecond, second);
            maxSecond = Math.max(maxSecond, second);
        }
        float firstPoint = coordinate(x, y, z, firstAxis);
        float secondPoint = coordinate(x, y, z, secondAxis);
        return firstPoint > minFirst + EPSILON && firstPoint < maxFirst - EPSILON
                && secondPoint > minSecond + EPSILON && secondPoint < maxSecond - EPSILON;
    }

    private static int normalQuadCount(CloudMesh mesh, int axis) {
        int count = 0;
        float[] vertices = mesh.copyVertices();
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            if (cross(vertices, start, start + CloudMesh.FLOATS_PER_VERTEX,
                    start + 2 * CloudMesh.FLOATS_PER_VERTEX)[axis] > EPSILON) {
                count++;
            }
        }
        return count;
    }

    private static double surfaceArea(CloudMesh mesh) {
        double area = 0.0D;
        float[] vertices = mesh.copyVertices();
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            area += triangleArea(vertices, start, start + CloudMesh.FLOATS_PER_VERTEX,
                    start + 2 * CloudMesh.FLOATS_PER_VERTEX);
            area += triangleArea(vertices, start, start + 2 * CloudMesh.FLOATS_PER_VERTEX,
                    start + 3 * CloudMesh.FLOATS_PER_VERTEX);
        }
        return area;
    }

    private static double triangleArea(float[] vertices, int first, int second, int third) {
        float[] normal = cross(vertices, first, second, third);
        return .5D * StrictMath.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
    }

    private static float[] cross(float[] vertices, int first, int second, int third) {
        float ax = vertices[second] - vertices[first];
        float ay = vertices[second + 1] - vertices[first + 1];
        float az = vertices[second + 2] - vertices[first + 2];
        float bx = vertices[third] - vertices[first];
        float by = vertices[third + 1] - vertices[first + 1];
        float bz = vertices[third + 2] - vertices[first + 2];
        return new float[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static float coordinate(float x, float y, float z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static float coordinate(float[] vertices, int vertex, int axis) {
        return vertices[vertex + axis];
    }
}
