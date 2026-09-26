package stellarium.client.ring.cloud;

import org.junit.Test;
import static org.junit.Assert.*;

public class CloudLocalBoundsTest {
    @Test public void flatBoundsContainEveryPhysicalCornerAfterCameraTranslation() {
        var b = new CloudLodPatchMeshBuilder.Aabb(1000, 1100, 224, 256, -200, 300);
        var slab = new CloudCurvaturePolicy.Segment(-2048, 2048, false, 1, 0, 0, 0);
        var bounds = CloudLocalBounds.project(b, slab, 149597870700.0, 1050, 4, 200, -100);
        assertNotNull(bounds);
        for (double x : new double[]{1000, 1100}) for (double y : new double[]{224, 256})
            for (double z : new double[]{-200, 300}) {
                double px = (x - 1050) * (1 - y / 149597870700.0) + 4;
                assertTrue(bounds.minX() <= px && bounds.maxX() >= px);
                assertTrue(bounds.minY() <= y - 200 && bounds.maxY() >= y - 200);
                assertTrue(bounds.minZ() <= z + 100 && bounds.maxZ() >= z + 100);
            }
        assertNull(CloudLocalBounds.project(b, new CloudCurvaturePolicy.Segment(2000, 3000, false, 1, 0, 0, 0),
                149597870700.0, 1050, 4, 200, -100));
    }

    @Test public void physicalMeshPreservesAnchorsAndPayloadBudget() {
        var g = new CloudGeometrySettings(48, 4, 48);
        var cache = CloudWorldCache.around(1, new CloudFieldSettings(8, 1, false, 0.2, 2), g, -1000, 0);
        var clip = new CloudClipBounds(0, 1000, -8192, 8192);
        var page = cache.page(CloudLodLayout.LOD0_FINE_3D);
        var mesh = CloudLodPatchMeshBuilder.build(page, g, 224, clip, 43682578.2444, true);
        assertTrue(mesh.physicalCoordinates());
        assertEquals(148L * mesh.quadCount(), mesh.byteCount());
        for (int q = 1; q < mesh.quadCount(); q++) {
            assertTrue("Physical batches must be spatially ordered for slab rejection",
                    mesh.materialCenterX(q - 1) <= mesh.materialCenterX(q));
        }
        for (int q = 0; q < mesh.quadCount(); q++) for (int c = 0; c < 4; c++) {
            double y = (double)mesh.cornerComponent(q,c,1) + mesh.cornerComponent(q,c,4);
            assertTrue(y >= 224 && y <= 256);
            for (int axis = 0; axis < 3; axis++) if (mesh.physicalNormalComponent(q,axis) != 0) {
                double coordinate = (double)mesh.cornerComponent(q,c,axis) + mesh.cornerComponent(q,c,axis+3);
                assertEquals(mesh.physicalAnchor(q), coordinate, 1.0e-5);
            }
        }
    }
}
