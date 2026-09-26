package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import stellarium.world.ring.RingworldDisplayGeometry;

/** Finite air-strip clipping must close partial cloud cells instead of retaining page-sized shells. */
public class CloudLodClipBoundaryTest {
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12.0D, 4.0D, 64);
    private static final double BASE_Y = 100.0D;
    private static final double RADIUS = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
    // Both bounds deliberately cut through cells: Z cells are 12m wide and cloud layers are 4m high.
    private static final CloudClipBounds PARTIAL_CLIP = new CloudClipBounds(101.25D, 126.75D, -17.5D, 19.25D);
    private static final double EPSILON = 1.0e-4D;

    @Test
    public void partialNegativeAndPositiveZCellsAreClosedAtTheExactFiniteStripCaps() {
        CloudWorldCache.Page page = opaqueCache(0L, 0L).page(CloudLodLayout.LOD0_FINE_3D);
        var mesh = CloudLodPatchMeshBuilder.build(page, GEOMETRY, BASE_Y, PARTIAL_CLIP, RADIUS);

        assertTrue("an opaque page intersecting the finite strip must produce geometry", mesh.quadCount() > 0);
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            assertPackedPlaneOwnsAllCorners(mesh, quad);
            for (int corner = 0; corner < 4; corner++) {
                double y = worldY(mesh, quad, corner);
                double z = packed(mesh, quad, corner, 2);
                assertTrue("all clipped cloud geometry must remain above the lower Y boundary",
                        y >= PARTIAL_CLIP.lowerY() - EPSILON);
                assertTrue("all clipped cloud geometry must remain below the upper Y boundary",
                        y <= PARTIAL_CLIP.upperY() + EPSILON);
                assertTrue("all clipped cloud geometry must remain inside the negative Z cap",
                        z >= PARTIAL_CLIP.minWorldZ() - EPSILON);
                assertTrue("all clipped cloud geometry must remain inside the positive Z cap",
                        z <= PARTIAL_CLIP.maxWorldZ() + EPSILON);
            }
        }

        assertClosedZCap(mesh, -1.0F, PARTIAL_CLIP.minWorldZ());
        assertClosedZCap(mesh, 1.0F, PARTIAL_CLIP.maxWorldZ());
    }

    @Test
    public void clearThreeDimensionalPageProducesNoShellEvenWhenItOverlapsTheClip() {
        CloudWorldCache clear = CloudWorldCache.around(2L, new CloudFieldSettings(13L, 0.0D, false, 0.2D),
                GEOMETRY, 0L, 0L);
        var mesh = CloudLodPatchMeshBuilder.build(clear.page(CloudLodLayout.LOD0_FINE_3D), GEOMETRY, BASE_Y,
                PARTIAL_CLIP, RADIUS);

        assertEquals("a clear voxel page must not be mistaken for an opaque shell", 0, mesh.quadCount());
    }

    @Test
    public void opaquePageWhollyOutsideTheFiniteStripProducesNoGeometry() {
        // LOD0's anchor-relative page lies around Z=35,000m here, far outside [-17.5,19.25].
        CloudWorldCache.Page outside = opaqueCache(0L, 2_928L).page(CloudLodLayout.LOD0_FINE_3D);
        var mesh = CloudLodPatchMeshBuilder.build(outside, GEOMETRY, BASE_Y, PARTIAL_CLIP, RADIUS);

        assertEquals("finite Z clipping must reject an opaque page that never reaches the strip", 0, mesh.quadCount());
    }

    private static CloudWorldCache opaqueCache(long cellX, long cellZ) {
        return CloudWorldCache.around(1L, new CloudFieldSettings(7L, 1.0D, false, 0.2D),
                GEOMETRY, cellX, cellZ);
    }

    private static void assertClosedZCap(CloudLodPatchMeshBuilder.PatchMesh mesh, float normalZ, double capZ) {
        boolean found = false;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            if (mesh.physicalNormalComponent(quad, 0) != 0.0F
                    || mesh.physicalNormalComponent(quad, 1) != 0.0F
                    || mesh.physicalNormalComponent(quad, 2) != normalZ) continue;
            boolean exactCap = true;
            for (int corner = 0; corner < 4; corner++) {
                exactCap &= Math.abs(packed(mesh, quad, corner, 2) - capZ) <= EPSILON;
            }
            found |= exactCap;
        }
        assertTrue("partial cell must emit a closed Z cap at " + capZ, found);
    }

    private static void assertPackedPlaneOwnsAllCorners(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad) {
        double nx = mesh.planeComponent(quad, 0) + mesh.planeComponent(quad, 4);
        double ny = mesh.planeComponent(quad, 1) + mesh.planeComponent(quad, 5);
        double nz = mesh.planeComponent(quad, 2) + mesh.planeComponent(quad, 6);
        double d = mesh.planeComponent(quad, 3) + mesh.planeComponent(quad, 7);
        for (int corner = 0; corner < 4; corner++) {
            double x = packed(mesh, quad, corner, 0);
            double y = packed(mesh, quad, corner, 1);
            double z = packed(mesh, quad, corner, 2);
            assertEquals("each packed plane must own every one of its packed corners", 0.0D,
                    nx * x + ny * y + nz * z + d, 1.0e-3D);
        }
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
}
