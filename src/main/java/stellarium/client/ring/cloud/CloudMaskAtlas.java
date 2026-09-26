package stellarium.client.ring.cloud;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Converts the fixed first-release cloud volume into the shader's RGBA8 atlas. */
public final class CloudMaskAtlas {
    public static final int WIDTH = 64;
    public static final int DEPTH = 64;
    public static final int LAYERS = 8;
    public static final int ATLAS_HEIGHT = DEPTH * LAYERS;
    public static final int BYTES_PER_TEXEL = 4;
    public static final int BYTE_SIZE = WIDTH * ATLAS_HEIGHT * BYTES_PER_TEXEL;

    private CloudMaskAtlas() {
    }

    /**
     * Produces a direct, read-ready RGBA8 buffer for a nearest/clamp atlas.
     * Texel {@code (x, z + layer * 64)} maps to {@code mask(x, layer, z)}.
     * RGB remains source RGB; alpha is the binary occupancy predicate used by
     * both the CPU mesh and shader ({@code 128} for solid, {@code 0} for air).
     */
    public static ByteBuffer toRgba8(CloudMask mask) {
        Objects.requireNonNull(mask, "mask");
        if (mask.width() != WIDTH || mask.depth() != DEPTH || mask.layers() != LAYERS) {
            throw new IllegalArgumentException("Cloud atlas requires a 64 x 64 x 8 mask");
        }
        ByteBuffer atlas = ByteBuffer.allocateDirect(BYTE_SIZE).order(ByteOrder.nativeOrder());
        for (int layer = 0; layer < LAYERS; layer++) {
            for (int z = 0; z < DEPTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    int argb = mask.cellArgb(x, layer, z);
                    atlas.put((byte) (argb >>> 16));
                    atlas.put((byte) (argb >>> 8));
                    atlas.put((byte) argb);
                    atlas.put((byte) (mask.occupiedAt(x, layer, z) ? 128 : 0));
                }
            }
        }
        return atlas.flip();
    }
}
