package stellarium.client.ring.cloud;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Upload facade for the finite, absolute-cell world cache atlas. */
public final class CloudLodAtlas {
    public static final int WIDTH = CloudLodLayout.ATLAS_WIDTH;
    public static final int HEIGHT = CloudLodLayout.ATLAS_HEIGHT;
    public static final int BYTES_PER_TEXEL = CloudLodLayout.RGBA8_BYTES_PER_TEXEL;
    public static final int BYTE_SIZE = CloudLodLayout.ATLAS_BYTE_SIZE;

    private CloudLodAtlas() { }

    public static ByteBuffer toRgba8(CloudWorldCache cache) {
        return Objects.requireNonNull(cache, "cache").rgba8();
    }
}
