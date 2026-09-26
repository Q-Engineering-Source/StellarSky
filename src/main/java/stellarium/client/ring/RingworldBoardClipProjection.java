package stellarium.client.ring;

import java.nio.FloatBuffer;

/** Infinite-far primitive clipping with the same perspective near plane and X/Y/W projection. */
final class RingworldBoardClipProjection {
    private RingworldBoardClipProjection() { }

    static boolean supports(FloatBuffer matrix) {
        if (matrix == null || matrix.limit() != 16) return false;
        for (int i = 0; i < 16; i++) if (!Float.isFinite(matrix.get(i))) return false;
        return matrix.get(2) == 0.0F && matrix.get(6) == 0.0F
                && matrix.get(3) == 0.0F && matrix.get(7) == 0.0F
                && matrix.get(11) == -1.0F && matrix.get(15) == 0.0F
                && matrix.get(10) <= -1.0F && matrix.get(14) < 0.0F;
    }

    /** Modifies a caller-owned copy, never the frozen optical frame or Minecraft's matrix stack. */
    static void removeFarPlane(FloatBuffer matrix) {
        if (!supports(matrix)) throw new IllegalArgumentException("Unsupported board mesh perspective projection");
        double nearScale = 2.0 / (1.0 - (double) matrix.get(10));
        matrix.put(14, (float) (nearScale * matrix.get(14)));
        matrix.put(10, -1.0F);
    }
}
