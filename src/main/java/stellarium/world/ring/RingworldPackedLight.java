package stellarium.world.ring;

/** Shared packed-light and board-height primitives; policy stays with each caller. */
final class RingworldPackedLight {
    private static final int SKY_SHIFT = 20;
    private static final int SKY_MASK = 0x00F00000;

    private RingworldPackedLight() {
    }

    static int sky(int packedLight) {
        return (packedLight >>> SKY_SHIFT) & 15;
    }

    static int withSky(int packedLight, int sky) {
        if (sky < 0 || sky > 15) {
            throw new IllegalArgumentException("sky must be within [0, 15]");
        }
        return (packedLight & ~SKY_MASK) | (sky << SKY_SHIFT);
    }

    static int upperFaceY(int baseY, int thickness) {
        return Math.addExact(baseY, thickness);
    }
}
