package stellarium.world.ring;

/**
 * Immutable first-release spatial-air volume for the finite ringworld strip.
 *
 * <p>The volume is {@code lowerY <= y < upperY} and
 * {@code -8192 <= z < 8192}. Density is one through
 * {@code fullDensityTopY}, then falls to zero with a smoothstep curve. The
 * lower face and both transverse exterior regions are vacuum; this is a
 * visual medium definition, not gravity, pressure, or oxygen simulation.</p>
 */
public record RingworldAirProfile(double lowerY, double fullDensityTopY, double upperY) {
    public RingworldAirProfile {
        requireFinite(lowerY, "lowerY");
        requireFinite(fullDensityTopY, "fullDensityTopY");
        requireFinite(upperY, "upperY");
        if (lowerY > fullDensityTopY || fullDensityTopY >= upperY) {
            throw new IllegalArgumentException("Require lowerY <= fullDensityTopY < upperY");
        }
    }

    /** Returns local medium density in {@code [0, 1]} at an exact world position. */
    public double densityAt(double y, double z) {
        requireFinite(y, "y");
        requireFinite(z, "z");
        if (y < lowerY || y >= upperY || !RingworldStripBounds.insideBoard(z)) {
            return 0.0;
        }
        if (y <= fullDensityTopY) {
            return 1.0;
        }
        double progress = (y - fullDensityTopY) / (upperY - fullDensityTopY);
        double smoothstep = progress * progress * (3.0 - 2.0 * progress);
        return 1.0 - smoothstep;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
