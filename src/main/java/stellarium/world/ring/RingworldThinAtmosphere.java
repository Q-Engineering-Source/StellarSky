package stellarium.world.ring;

/** Physical-Y atmosphere coverage for the first-release finite ringworld strip. */
public final class RingworldThinAtmosphere {
    private static final RingworldAirProfile DEFAULT_PROFILE = RingworldSettings.defaultAtmosphereProfile();

    private RingworldThinAtmosphere() {
    }

    /**
     * Returns the atmosphere fade for a physical Minecraft observer coordinate.
     * The board uses its confirmed half-open Z interval; construction walls and
     * exterior void never retain atmosphere.
     */
    public static double fadeAt(double observerY, double observerZ, double fadeStartY) {
        return fadeAt(observerY, observerZ, new RingworldAirProfile(0.0, fadeStartY, 256.0));
    }

    /** Delegates the legacy helper to the immutable spatial-air product model. */
    public static double fadeAt(double observerY, double observerZ, RingworldAirProfile profile) {
        return java.util.Objects.requireNonNull(profile, "profile").densityAt(observerY, observerZ);
    }

    /** The unconfigured first-release profile, for compatibility-only callers. */
    public static RingworldAirProfile defaultProfile() {
        return DEFAULT_PROFILE;
    }
}
