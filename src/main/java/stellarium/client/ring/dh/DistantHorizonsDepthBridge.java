package stellarium.client.ring.dh;

import java.util.Objects;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldRenderSnapshots;

/**
 * Frame-local description of DH's private depth texture.  This owns no GL resource and does not
 * prolong the texture's lifetime; it merely lets the final SS air pass reject a stale frame.
 */
public final class DistantHorizonsDepthBridge {
    private DistantHorizonsDepthBridge() {
    }

    public static Snapshot capture(RingworldCurvatureFrame frame, int textureId, float[] inverseViewProjection,
                                   int viewportX, int viewportY, int viewportWidth, int viewportHeight,
                                   double cameraOffsetX, double cameraOffsetY, double cameraOffsetZ) {
        // A rejected replacement must not leave the preceding frame's texture usable.
        clear();
        if (frame == null) {
            return null;
        }
        Snapshot snapshot = new Snapshot(frame, textureId, inverseViewProjection, viewportX, viewportY,
                viewportWidth, viewportHeight, cameraOffsetX, cameraOffsetY, cameraOffsetZ);
        RingworldRenderSnapshots.captureDistantDepth(snapshot);
        return snapshot;
    }

    /** Returns no texture unless it belongs to the exact currently admitted immutable frame. */
    public static Snapshot current() {
        Snapshot candidate = RingworldRenderSnapshots.currentDistantDepth();
        return candidate != null && candidate.frame == DistantHorizonsCurvatureState.currentFrame() ? candidate : null;
    }

    public static void clear() {
        RingworldRenderSnapshots.captureDistantDepth(null);
    }

    /** Values are arranged for OpenGL's column-major, transpose=false uniform upload. */
    public static final class Snapshot {
        private final RingworldCurvatureFrame frame;
        private final int textureId;
        private final float[] inverseViewProjection;
        private final int viewportX, viewportY, viewportWidth, viewportHeight;
        private final double cameraOffsetX, cameraOffsetY, cameraOffsetZ;

        private Snapshot(RingworldCurvatureFrame frame, int textureId, float[] inverseViewProjection,
                         int viewportX, int viewportY, int viewportWidth, int viewportHeight,
                         double cameraOffsetX, double cameraOffsetY, double cameraOffsetZ) {
            this.frame = Objects.requireNonNull(frame, "frame");
            if (textureId <= 0) throw new IllegalArgumentException("Distant Horizons depth texture must be positive");
            if (viewportWidth <= 0 || viewportHeight <= 0) {
                throw new IllegalArgumentException("Distant Horizons depth viewport must be non-empty");
            }
            if (inverseViewProjection == null || inverseViewProjection.length != 16) {
                throw new IllegalArgumentException("Distant Horizons inverse view-projection requires 16 values");
            }
            this.inverseViewProjection = inverseViewProjection.clone();
            for (float value : this.inverseViewProjection) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Distant Horizons inverse matrix is non-finite");
            }
            if (!Double.isFinite(cameraOffsetX) || !Double.isFinite(cameraOffsetY) || !Double.isFinite(cameraOffsetZ)) {
                throw new IllegalArgumentException("Distant Horizons camera offset is non-finite");
            }
            this.textureId = textureId;
            this.viewportX = viewportX;
            this.viewportY = viewportY;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.cameraOffsetX = cameraOffsetX;
            this.cameraOffsetY = cameraOffsetY;
            this.cameraOffsetZ = cameraOffsetZ;
        }

        public RingworldCurvatureFrame frame() { return frame; }
        public int textureId() { return textureId; }
        public float[] inverseViewProjection() { return inverseViewProjection.clone(); }
        public int viewportX() { return viewportX; }
        public int viewportY() { return viewportY; }
        public int viewportWidth() { return viewportWidth; }
        public int viewportHeight() { return viewportHeight; }
        public double cameraOffsetX() { return cameraOffsetX; }
        public double cameraOffsetY() { return cameraOffsetY; }
        public double cameraOffsetZ() { return cameraOffsetZ; }
    }
}
