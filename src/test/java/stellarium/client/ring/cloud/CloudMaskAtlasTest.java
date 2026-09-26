package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

/** Headless byte-level contract for the nearest cloud-mask GPU atlas. */
public class CloudMaskAtlasTest {
    @Test
    public void everyVolumeVoxelMapsToItsExactLayeredAtlasTexel() {
        int[] argb = new int[CloudMaskAtlas.WIDTH * CloudMaskAtlas.DEPTH * CloudMaskAtlas.LAYERS];
        for (int layer = 0; layer < CloudMaskAtlas.LAYERS; layer++) {
            for (int z = 0; z < CloudMaskAtlas.DEPTH; z++) {
                for (int x = 0; x < CloudMaskAtlas.WIDTH; x++) {
                    int alpha = ((x + z + layer) & 1) == 0 ? 127 : 128;
                    argb[(layer * CloudMaskAtlas.DEPTH + z) * CloudMaskAtlas.WIDTH + x] = alpha << 24
                            | x << 16 | z << 8 | layer;
                }
            }
        }
        CloudMask mask = new CloudMask(3L, CloudMaskAtlas.WIDTH, CloudMaskAtlas.DEPTH,
                CloudMaskAtlas.LAYERS, argb);
        ByteBuffer atlas = CloudMaskAtlas.toRgba8(mask);

        assertTrue(atlas.isDirect());
        assertEquals(0, atlas.position());
        assertEquals(CloudMaskAtlas.BYTE_SIZE, atlas.limit());
        for (int layer = 0; layer < CloudMaskAtlas.LAYERS; layer++) {
            for (int z = 0; z < CloudMaskAtlas.DEPTH; z++) {
                for (int x = 0; x < CloudMaskAtlas.WIDTH; x++) {
                    int offset = ((z + layer * CloudMaskAtlas.DEPTH) * CloudMaskAtlas.WIDTH + x)
                            * CloudMaskAtlas.BYTES_PER_TEXEL;
                    assertEquals(x, unsigned(atlas.get(offset)));
                    assertEquals(z, unsigned(atlas.get(offset + 1)));
                    assertEquals(layer, unsigned(atlas.get(offset + 2)));
                    assertEquals(mask.occupiedAt(x, layer, z) ? 128 : 0, unsigned(atlas.get(offset + 3)));
                }
            }
        }
    }

    @Test
    public void atlasRejectsMasksThatWouldChangeTheShaderLayout() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudMaskAtlas.toRgba8(new CloudMask(1L, 1, 1, new int[]{0xFFFFFFFF})));
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }
}
