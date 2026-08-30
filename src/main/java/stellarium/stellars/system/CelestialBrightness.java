package stellarium.stellars.system;

import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.optics.Wavelength;
import stellarium.stellars.OpticsHelper;
import stellarium.view.ViewerInfo;

final class CelestialBrightness {
	private static final double MAX_AIRMASS = 40.0;

	private CelestialBrightness() { }

	static float atmosphericTransmission(ViewerInfo info, SpCoord horizontal,
			Wavelength wavelength) {
		double horizonVisibility = Math.max(0.0, Math.min(1.0, horizontal.y + 1.0));
		if(horizonVisibility <= 0.0)
			return 0.0f;

		double airmass = info.sky.calculateAirmass(horizontal);
		if(!Double.isFinite(airmass) || airmass < 0.0)
			airmass = 0.0;
		airmass = Math.min(airmass, MAX_AIRMASS);

		double extinctionMagnitude =
				info.sky.getExtinctionRate(wavelength) * airmass;
		return (float) (horizonVisibility
				* OpticsHelper.getMultFromMag(extinctionMagnitude));
	}
}
