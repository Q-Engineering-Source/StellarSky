package stellarium.render.stellars;

/**
 * Chooses the representation captured before post-processing. Atmosphere
 * finalize already writes RGBE to frame1; vacuum sources must remain linear
 * until PostProcess performs their single RGBE conversion.
 */
public enum PostProcessInputRoute {
	ATMOSPHERE_RGBE_FRAME1(false),
	VACUUM_LINEAR_SCENE(true);

	private final boolean requiresRgbEEncoding;

	PostProcessInputRoute(boolean requiresRgbEEncoding) {
		this.requiresRgbEEncoding = requiresRgbEEncoding;
	}

	public static PostProcessInputRoute select(boolean renderAtmosphere, double atmosphereFade) {
		return select(renderAtmosphere, atmosphereFade, false);
	}

	/** Spatial-air B composes after world rendering, so its stellar input stays vacuum-linear. */
	public static PostProcessInputRoute select(boolean renderAtmosphere, double atmosphereFade, boolean spatialAir) {
		if(!Double.isFinite(atmosphereFade))
			throw new IllegalArgumentException("atmosphereFade must be finite");
		if(spatialAir)
			return VACUUM_LINEAR_SCENE;
		return renderAtmosphere && atmosphereFade > 0.0
				? ATMOSPHERE_RGBE_FRAME1 : VACUUM_LINEAR_SCENE;
	}

	public boolean requiresRgbEEncoding() {
		return this.requiresRgbEEncoding;
	}
}
