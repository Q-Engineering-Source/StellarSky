package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import stellarium.world.ring.RingworldDisplayGeometry;

/** Verifies the compact per-face physical anchor consumed by the curved atlas lookup. */
public class CloudLodPhysicalAnchorTest {
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12.0D, 4.0D, 64);
    private static final double RADIUS = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
    private static final double BASE_Y = 100.0D;
    private static final CloudClipBounds OPEN = new CloudClipBounds(0.0D, 1_000.0D, -8_192.0D, 8_192.0D);

    @Test
    public void thirtySevenFloatPayloadAnchorsAllSixPhysicalFaceAxesAndSubmitsFourStripVertices() {
        var mesh = CloudLodPatchMeshBuilder.build(fullPage(0L, 0L), GEOMETRY, BASE_Y, OPEN, RADIUS);
        assertTrue(mesh.quadCount() > 0);
        assertEquals(mesh.quadCount() * CloudLodPatchMeshBuilder.FLOATS_PER_QUAD, mesh.floatCount());
        assertEquals(mesh.quadCount() * 6, mesh.vertexCount());
        assertEquals(mesh.quadCount() * 4, mesh.submittedVertexCount());

        boolean[] normal = new boolean[6];
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            float nx = mesh.physicalNormalComponent(quad, 0);
            float ny = mesh.physicalNormalComponent(quad, 1);
            float nz = mesh.physicalNormalComponent(quad, 2);
            double anchor = mesh.physicalAnchor(quad);
            if (nx != 0.0F) {
                normal[nx < 0.0F ? 0 : 1] = true;
                for (int corner = 0; corner < 4; corner++) {
                    assertEquals(anchor, unwrappedWorldX(mesh, quad, corner, anchor), 0.02D);
                }
            } else if (ny != 0.0F) {
                normal[ny < 0.0F ? 2 : 3] = true;
                for (int corner = 0; corner < 4; corner++) assertEquals(anchor, worldY(mesh, quad, corner), 0.02D);
            } else {
                normal[nz < 0.0F ? 4 : 5] = true;
                for (int corner = 0; corner < 4; corner++) assertEquals(anchor, packed(mesh, quad, corner, 2), 1.0e-5D);
            }
        }
        for (int index = 0; index < normal.length; index++) assertTrue("missing physical boundary normal " + index, normal[index]);
    }

    @Test
    public void normalXAnchorPreservesTheOriginalBranchAfterSeveralRingCircumferences() {
        double circumference = Math.PI * 2.0D * RADIUS;
        long cellX = Math.round((circumference * 3.0D + 1_024.0D) / GEOMETRY.cellSizeBlocks());
        var mesh = CloudLodPatchMeshBuilder.build(fullPage(cellX, 0L), GEOMETRY, BASE_Y, OPEN, RADIUS);
        boolean found = false;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            if (mesh.physicalNormalComponent(quad, 0) == 0.0F) continue;
            found = true;
            double anchor = mesh.physicalAnchor(quad);
            for (int corner = 0; corner < 4; corner++) {
                assertEquals("normal-X lookup must use the page branch rather than atan2's principal turn", anchor,
                        unwrappedWorldX(mesh, quad, corner, anchor), 0.25D);
            }
        }
        assertTrue("fixture must include the closed X faces", found);
    }

    @Test
    public void fractionalFiniteStripCapsCarryTheExactPhysicalZAnchor() {
        CloudClipBounds clip = new CloudClipBounds(0.0D, 1_000.0D, -8_192.5D, 8_192.5D);
        // Separate 3D pages reach each finite strip end at 12m cell width.
        assertFractionalCap(CloudLodPatchMeshBuilder.build(fullPage(0L, -683L), GEOMETRY, BASE_Y, clip, RADIUS),
                -1.0F, clip.minWorldZ());
        assertFractionalCap(CloudLodPatchMeshBuilder.build(fullPage(0L, 683L), GEOMETRY, BASE_Y, clip, RADIUS),
                1.0F, clip.maxWorldZ());
    }

    private static CloudWorldCache.Page fullPage(long cellX, long cellZ) {
        return CloudWorldCache.around(1L, new CloudFieldSettings(7L, 1.0D, false, 0.2D), GEOMETRY, cellX, cellZ)
                .page(CloudLodLayout.LOD0_FINE_3D);
    }

    private static void assertFractionalCap(CloudLodPatchMeshBuilder.PatchMesh mesh, float normalZ, double cap) {
        boolean found = false;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            if (mesh.physicalNormalComponent(quad, 2) != normalZ) continue;
            for (int corner = 0; corner < 4; corner++) assertEquals(cap, packed(mesh, quad, corner, 2), 1.0e-5D);
            assertEquals(cap, mesh.physicalAnchor(quad), 1.0e-5D);
            found = true;
        }
        assertTrue("fractional strip cap must be closed at " + cap, found);
    }

    private static double unwrappedWorldX(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner, double anchor) {
        double x = packed(mesh, quad, corner, 0);
        double embeddedY = packed(mesh, quad, corner, 1);
        double principal = Math.atan2(x, RADIUS - embeddedY) * RADIUS;
        double circumference = Math.PI * 2.0D * RADIUS;
        return principal + Math.rint((anchor - principal) / circumference) * circumference;
    }

    private static double worldY(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner) {
        double x = packed(mesh, quad, corner, 0);
        return RADIUS - Math.hypot(x, RADIUS - packed(mesh, quad, corner, 1));
    }

    private static double packed(CloudLodPatchMeshBuilder.PatchMesh mesh, int quad, int corner, int component) {
        return (double) mesh.cornerComponent(quad, corner, component)
                + mesh.cornerComponent(quad, corner, component + 3);
    }
}
