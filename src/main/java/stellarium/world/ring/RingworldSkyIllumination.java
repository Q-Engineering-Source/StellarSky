package stellarium.world.ring;

import javax.annotation.Nullable;

/** Immutable render-time solar inputs derived only from one frozen ring snapshot. */
public final class RingworldSkyIllumination {
	public enum Mode {
		NOT_RING,
		PHASE_UNAVAILABLE,
		READY
	}

	private static final RingworldSkyIllumination NOT_RING = new RingworldSkyIllumination(Mode.NOT_RING, 0.0, 0.0);
	private final Mode mode;
	private final double directSunTransmission;
	private final double atmosphereScatterTransmission;

	private RingworldSkyIllumination(Mode mode, double direct, double scatter) {
		this.mode = mode;
		this.directSunTransmission = requireUnit("direct sun transmission", direct);
		this.atmosphereScatterTransmission = requireUnit("atmosphere scatter transmission", scatter);
	}

	public static RingworldSkyIllumination from(@Nullable RingworldDisplaySnapshot snapshot) {
		if(snapshot == null)
			return NOT_RING;
		if(snapshot.phase() == null) {
			double direct = unavailableDirect(snapshot);
			return new RingworldSkyIllumination(Mode.PHASE_UNAVAILABLE, direct, direct * snapshot.atmosphereFade());
		}
		double y = snapshot.observer().y();
		long upper = (long)snapshot.sunshadeHeightBlocks() + snapshot.sunshadeThicknessBlocks();
		double direct;
		if(y >= upper) {
			direct = 1.0;
		} else if(y >= snapshot.sunshadeHeightBlocks()) {
			direct = snapshot.sunshade().materialOccupied(snapshot.phase(),
					snapshot.observer().x(), snapshot.observer().z()) ? 0.0 : 1.0;
		} else {
			direct = snapshot.sunshade().transmittance(snapshot.phase(),
					snapshot.observer().x(), snapshot.observer().z());
		}
		return new RingworldSkyIllumination(Mode.READY, direct, direct * snapshot.atmosphereFade());
	}

	public Mode mode() { return mode; }
	public boolean isRing() { return mode != Mode.NOT_RING; }
	public boolean canRenderDirectSunScatter() { return directSunTransmission > 0.0; }
	public boolean canRenderOpaqueSun() { return mode != Mode.PHASE_UNAVAILABLE || directSunTransmission > 0.0; }
	public boolean canRenderPreviousSky(double legacyAtmosphereFade) {
		requireUnit("legacy atmosphere fade", legacyAtmosphereFade);
		return isRing() ? atmosphereScatterTransmission > 0.0 : legacyAtmosphereFade > 0.0;
	}
	public double directSunTransmission() { return directSunTransmission; }
	public double atmosphereScatterTransmission() { return atmosphereScatterTransmission; }
	public double directScatterLightColor(double legacyTwilight) {
		requireUnit("legacy twilight", legacyTwilight);
		return isRing() ? directSunTransmission : legacyTwilight;
	}
	public double lowPowerDomeTransmission(double legacyAtmosphereFade) {
		requireUnit("legacy atmosphere fade", legacyAtmosphereFade);
		return isRing() ? atmosphereScatterTransmission : legacyAtmosphereFade;
	}
	public double previousSkyDarkeningAlpha(double legacyClearAlpha, double legacyAtmosphereFade) {
		requireUnit("legacy clear alpha", legacyClearAlpha);
		requireUnit("legacy atmosphere fade", legacyAtmosphereFade);
		double alpha = isRing() ? 1.0 - atmosphereScatterTransmission * (1.0 - legacyClearAlpha)
				: 1.0 - (1.0 - legacyClearAlpha) * legacyAtmosphereFade;
		return requireUnit("previous sky darkening alpha", alpha);
	}

	private static double unavailableDirect(RingworldDisplaySnapshot snapshot) {
		long upper = (long)snapshot.sunshadeHeightBlocks() + snapshot.sunshadeThicknessBlocks();
		return snapshot.observer().y() >= upper
				|| !RingworldStripBounds.insideBoard(snapshot.observer().z())
				|| snapshot.sunshade().isEmpty() ? 1.0 : 0.0;
	}

	private static double requireUnit(String name, double value) {
		if(!Double.isFinite(value) || value < 0.0 || value > 1.0)
			throw new IllegalArgumentException(name + " must be finite and within [0,1]");
		return value;
	}
}
