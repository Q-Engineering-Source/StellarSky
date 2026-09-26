package stellarium.client.ring.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudLayerCountTest {
    @Test
    public void everyOccupancyPatternPreservesColumnCoverageAndRemovesInternalSteps() {
        for (int mask = 0; mask < 256; mask++) {
            for (int layers : new int[] {2, 4, 8}) {
                CloudColumn column = new CloudColumn();
                for (int index = 0; index < 8; index++) {
                    column.argb[index] = (mask & (1 << index)) == 0 ? 0 : 0xE0B0B0B0 + index * 0x010101;
                }
                int[] original = column.argb.clone();
                CloudWorldField.quantizeLayers(column, layers);
                assertEquals("column-union coverage", mask != 0, any(column));
                for (int index = 1; index < 8; index++) {
                    if (index % (8 / layers) != 0) assertEquals(column.argb[index - 1], column.argb[index]);
                }
                if (layers == 8) assertArrayEquals("default detail is unchanged", original, column.argb);
            }
        }
    }

    @Test
    public void actualSamplerKeepsHolesAndProducesOnlyConfiguredHeightGroups() {
        var full = CloudWorldField.sampler(new CloudFieldSettings(0L, .6D, true, .6D));
        var coarse = CloudWorldField.sampler(new CloudFieldSettings(0L, .6D, true, .6D, 2));
        var original = new CloudColumn();
        var reduced = new CloudColumn();
        boolean occupied = false, hole = false, changed = false;
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            full.fillColumn(x * 48.0D, z * 48.0D, original);
            coarse.fillColumn(x * 48.0D, z * 48.0D, reduced);
            assertEquals(any(original), any(reduced));
            occupied |= any(reduced);
            hole |= !any(reduced);
            for (int layer = 0; layer < 8; layer++) {
                assertEquals(reduced.argb[layer / 4 * 4], reduced.argb[layer]);
                changed |= reduced.argb[layer] != original.argb[layer];
            }
        }
        assertTrue(occupied && hole && changed);
    }

    @Test
    public void twoPhysicalLayersKeepTheSameVolumeAndGenerateFewerRealShellFaces() {
        var clip = new CloudClipBounds(0, 256, -8192, 8192);
        var fullGeometry = CloudGeometrySettings.forLayers(48, 4, 8, 64);
        var coarseGeometry = CloudGeometrySettings.forLayers(48, 16, 2, 64);
        assertEquals(fullGeometry, coarseGeometry);
        assertEquals(240.0, CloudModelHandoff.sheetY(224, coarseGeometry, clip), 0.0);
        var fullField = new CloudFieldSettings(0L, .6D, true, .6D);
        var coarseField = new CloudFieldSettings(0L, .6D, true, .6D, 2);
        assertNotEquals(fullField, coarseField);
        var fullCache = CloudWorldCache.around(1L, fullField, fullGeometry, 0, 0);
        var coarseCache = CloudWorldCache.around(2L, coarseField, coarseGeometry, 0, 0, fullCache);
        var level = CloudLodLayout.LOD0_FINE_3D;
        var fullMesh = CloudLodPatchMeshBuilder.build(fullCache.page(level), fullGeometry, 224, clip, 43682578.2444D);
        var coarseMesh = CloudLodPatchMeshBuilder.build(coarseCache.page(level), coarseGeometry, 224, clip, 43682578.2444D);
        assertTrue("fixture must retain clouds", coarseMesh.quadCount() > 0);
        assertTrue("actual generated faces must decrease", coarseMesh.quadCount() < fullMesh.quadCount());
        for (int quad = 0; quad < coarseMesh.quadCount(); quad++) {
            if (coarseMesh.physicalNormalComponent(quad, 1) != 0.0F) {
                double y = coarseMesh.physicalAnchor(quad);
                assertTrue("no internal four-metre height steps: " + y, y == 224.0 || y == 240.0 || y == 256.0);
            }
        }
        var restored = CloudWorldCache.around(3L, fullField, fullGeometry, 0, 0, coarseCache);
        assertEquals("switching back must rebuild the original field, not reuse coarse pages",
                fullCache.rgba8(), restored.rgba8());
    }

    private static boolean any(CloudColumn column) {
        for (int color : column.argb) if ((color >>> 24) != 0) return true;
        return false;
    }
}
