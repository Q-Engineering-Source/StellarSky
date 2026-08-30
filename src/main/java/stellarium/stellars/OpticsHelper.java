package stellarium.stellars;

import java.util.Random;

import net.minecraftforge.common.config.Configuration;
import stellarapi.api.lib.config.SimpleConfigHandler;
import stellarapi.api.lib.config.property.ConfigPropertyDouble;
import stellarapi.api.lib.math.Spmath;
import stellarapi.api.optics.EyeDetector;

public class OpticsHelper extends SimpleConfigHandler {
	private static final double REFERENCE_TWINKLE_AMOUNT = 0.3;
	// Magnitude Base
	private static final double MAG_BASE = Math.pow(10.0, 0.4);

	// Magnitude of the Sun
	private static final double MAG_SUN = -26.74;

	// Default resolution in rad
	public static final float DEFAULT_RESOLUTION = (float) Math.toRadians(EyeDetector.DEFAULT_RESOLUTION);

	// Magnitude of star with maximal brightness(intensity) 1.0 with default resolution
	private static final double MAG_UPPER_LIMIT = -0.5;
	// MAYBE Configurable Upper Magnitude Limit

	// Relative brightness of an object with size of 1 (rad)^2 compared to the star with default resolution (same flux)
	private static final float SURF_MULTIPLIER = (float) (2 * Math.PI * Spmath.sqr(DEFAULT_RESOLUTION));

	public static final OpticsHelper instance = new OpticsHelper();

	private final Random randomTwinkle = new Random(3L);

	private ConfigPropertyDouble propTurb;

	private double twinkleAmount;

	public OpticsHelper() {
		this.propTurb = new ConfigPropertyDouble("Twinkling(Turbulance)", "", 1.0);

		this.addConfigProperty(this.propTurb);
	}

	@Override
	public void setupConfig(Configuration config, String category) {
		config.setCategoryLanguageKey(category, "config.category.optics");
		config.setCategoryComment(category, "Configuration for Optical settings.");
		config.setCategoryRequiresMcRestart(category, false);

		super.setupConfig(config, category);

		propTurb.setComment("Relative strength of atmospheric star twinkling.\n"
				+ "1.0 matches Stellarium's reference effect: up to 30% dimming at "
				+ "the horizon and 3% at the zenith. Set to 0.0 to disable.");
		propTurb.setRequiresMcRestart(false);
		propTurb.setLanguageKey("config.property.client.turbulance");
		propTurb.setMinValue(0.0);
		propTurb.setMaxValue(2.0);
	}

	@Override
	public void loadFromConfig(Configuration config, String category) {
		super.loadFromConfig(config, category);
		// Preserve the historic 0..2 setting while making 1.0 match
		// Stellarium's reference horizon attenuation of 0.3.
		this.twinkleAmount = propTurb.getDouble() * REFERENCE_TWINKLE_AMOUNT;
	}

	@Override
	public void saveToConfig(Configuration config, String category) {
		// Simple configuration, saves nothing
	}

	public static float twinkleAmount() {
		return (float) instance.twinkleAmount;
	}

	/**
	 * Stellarium-style altitude response: full at the horizon and 10% at
	 * zenith. The input is the sine of apparent altitude.
	 */
	public static float twinkleAltitudeFactor(double sinAltitude) {
		return (float) Math.max(0.1, Math.min(1.0, 1.0 - 0.9 * sinAltitude));
	}

	/** Per-update luminance multiplier for the legacy star renderer. */
	public static float twinkleBrightness(double sinAltitude) {
		float attenuation = twinkleAltitudeFactor(sinAltitude)
				* twinkleAmount() * instance.randomTwinkle.nextFloat();
		return Math.max(0.0f, 1.0f - attenuation);
	}

	public static float getBrightnessFromMag(double magnitude) {
		return (float) Math.pow(MAG_BASE, MAG_UPPER_LIMIT - magnitude);
	}

	/** Gets multiplier for an object spread in area((rad)^2) */
	public static float getMultFromArea(double angularArea) {
		return SURF_MULTIPLIER / (float)angularArea;
	}

	public static float getDominationFromMag(double magnitude) {
		return (float) Math.pow(MAG_BASE, MAG_SUN - magnitude);
	}

	public static double getMultFromMag(double magnitude) {
		return Math.pow(MAG_BASE, -magnitude);
	}

	public static double getMagFromMult(double multiplier) {
		return - 2.5 * Math.log10(multiplier);
	}
}
