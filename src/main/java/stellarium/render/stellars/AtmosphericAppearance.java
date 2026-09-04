package stellarium.render.stellars;

/**
 * Keeps cached atmospheric appearance values reversible at render time.
 *
 * <p>The atmospheric endpoint is deliberately returned unchanged for a full
 * atmosphere. This preserves the legacy cache calculation, including its
 * floating-point behaviour, instead of reconstructing it from a transmission
 * value that may have reached zero at the horizon.</p>
 */
public final class AtmosphericAppearance {
	private AtmosphericAppearance() { }

	/** Blends an intrinsic value to its already-atmospheric cached value. */
	public static float blend(float intrinsic, float atmospheric, double atmosphereFade) {
		requireFade(atmosphereFade);
		if(atmosphereFade == 0.0)
			return intrinsic;
		if(atmosphereFade == 1.0)
			return atmospheric;
		return (float) (intrinsic + (atmospheric - intrinsic) * atmosphereFade);
	}

	/** Scales an atmosphere-only optical effect while retaining exact endpoints. */
	public static double scaleAtmosphericEffect(double effect, double atmosphereFade) {
		requireFade(atmosphereFade);
		if(atmosphereFade == 0.0)
			return 0.0;
		if(atmosphereFade == 1.0)
			return effect;
		return effect * atmosphereFade;
	}

	private static void requireFade(double fade) {
		if(!Double.isFinite(fade) || fade < 0.0 || fade > 1.0)
			throw new IllegalArgumentException("Atmosphere fade must be within [0, 1]");
	}
}
