package stellarium.world.ring;

/** Thin-air fog parameters; does not change fog mode, color, media, or any lightmap. */
public final class RingworldAirFog {
    // Beyond all legal Minecraft coordinates and ordinary/DUT far planes, yet finite in GL.
    private static final float CLEAR_START = Float.MAX_VALUE / 4.0f;
    private static final float CLEAR_END = Float.MAX_VALUE / 2.0f;

    private RingworldAirFog() {
    }

    public static LinearRange linearRange(float start, float end, double atmosphereFade) {
        requireFade(atmosphereFade);
        if (!Float.isFinite(start) || !Float.isFinite(end)) {
            throw new IllegalArgumentException("Air fog range must be finite");
        }
        if (atmosphereFade == 1.0) return new LinearRange(start, end);
        if (atmosphereFade == 0.0) return new LinearRange(CLEAR_START, CLEAR_END);
        double expanded = start + ((double) end - start) / atmosphereFade;
        return new LinearRange(start, (float) Math.max(-CLEAR_END, Math.min(CLEAR_END, expanded)));
    }

    public static float density(float density, double atmosphereFade, boolean exponentialSquared) {
        requireFade(atmosphereFade);
        if (!Float.isFinite(density) || density < 0.0f) {
            throw new IllegalArgumentException("Air fog density must be finite and non-negative");
        }
        if (atmosphereFade == 1.0) return density;
        return (float) (density * (exponentialSquared ? Math.sqrt(atmosphereFade) : atmosphereFade));
    }

    private static void requireFade(double fade) {
        if (!Double.isFinite(fade) || fade < 0.0 || fade > 1.0) {
            throw new IllegalArgumentException("Atmosphere fade must be within [0, 1]");
        }
    }

    public record LinearRange(float start, float end) {
    }
}
