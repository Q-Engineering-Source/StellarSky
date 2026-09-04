package stellarium.world.ring;

/** Physical-Y atmosphere coverage for the first-release finite ringworld strip. */
public final class RingworldThinAtmosphere {
    private static final double TOP_Y = 256.0;

    private RingworldThinAtmosphere() {
    }

    /**
     * Returns the atmosphere fade for a physical Minecraft observer coordinate.
     * The board uses its confirmed half-open Z interval; construction walls and
     * exterior void never retain atmosphere.
     */
    public static double fadeAt(double observerY, double observerZ, double fadeStartY) {
        requireFinite(observerY, "observerY");
        requireFinite(observerZ, "observerZ");
        validateFadeStartY(fadeStartY);
        if (!RingworldStripBounds.insideBoard(observerZ) || observerY >= TOP_Y) {
            return 0.0;
        }
        if (observerY <= fadeStartY) {
            return 1.0;
        }
        double progress = (observerY - fadeStartY) / (TOP_Y - fadeStartY);
        double smoothstep = progress * progress * (3.0 - 2.0 * progress);
        return 1.0 - smoothstep;
    }

    private static void validateFadeStartY(double fadeStartY) {
        requireFinite(fadeStartY, "fadeStartY");
        if (fadeStartY < 0.0 || fadeStartY >= TOP_Y) {
            throw new IllegalArgumentException("fadeStartY must be in [0, 256)");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
