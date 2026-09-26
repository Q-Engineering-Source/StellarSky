package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import org.junit.Test;

/** Cache limits disappear only when the same physical field continues beyond them. */
public class CloudWindowContinuityTest {
    private static final CloudGeometrySettings CELL = new CloudGeometrySettings(12.0, 4.0, 0);

    @Test
    public void horizonDoesNotInventFourWallsAroundOneCachedCellOfAnInfiniteLayer() {
        CloudMask mask = new CloudMask(1, 1, 1, new int[]{0xFFFFFFFF});
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0, 0, mask, CELL, 128,
                new CloudClipBounds(0, 512, -8192, 8192));
        assertEquals(6, CloudMeshBuilder.build(key).quadCount());
        assertEquals(2, CloudMeshBuilder.build(key, false).quadCount());
    }

    @Test
    public void removingCacheWallsStillPreservesActualYCropsAndBothZCaps() {
        CloudMask mask = new CloudMask(1, 1, 1, new int[]{0xFFFFFFFF});
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0, 0, mask, CELL, 128,
                new CloudClipBounds(129, 131, 3, 9));
        CloudMesh mesh = CloudMeshBuilder.build(key, false);
        assertEquals(4, mesh.quadCount());
        FloatBuffer vertices = mesh.vertexBuffer();
        while (vertices.hasRemaining()) {
            vertices.get();
            float y = vertices.get();
            float z = vertices.get();
            assertTrue(y == 1.0f || y == 3.0f);
            assertTrue(z == 3.0f || z == 9.0f);
            vertices.position(vertices.position() + 3);
        }
    }

    @Test
    public void genuineOccupiedToEmptyFacesSurviveAtTheWindowEdge() {
        CloudMask mask = new CloudMask(1, 2, 1, new int[]{0xFFFFFFFF, 0});
        CloudMeshCacheKey key = CloudMeshCacheKey.at(0, 0, mask, CELL, 128,
                new CloudClipBounds(0, 512, -8192, 8192));
        assertEquals(4, CloudMeshBuilder.build(key, false).quadCount());
    }
}
