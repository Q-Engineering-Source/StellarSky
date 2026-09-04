package stellarium.render.stellars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AtmosphericAppearanceTest {
	@Test
	public void blendRetainsExactLegacyAtmosphericEndpoint() {
		float atmospheric = Float.intBitsToFloat(0x3f123457);

		assertEquals(Float.floatToRawIntBits(atmospheric), Float.floatToRawIntBits(
				AtmosphericAppearance.blend(3.5f, atmospheric, 1.0)));
	}

	@Test
	public void blendRestoresIntrinsicBrightnessWhenThereIsNoAtmosphere() {
		assertEquals(3.5f, AtmosphericAppearance.blend(3.5f, 0.0f, 0.0), 0.0f);
		assertEquals(1.0f, AtmosphericAppearance.blend(1.0f, 0.2f, 0.0), 0.0f);
	}

	@Test
	public void blendInterpolatesPartialAtmosphereWithoutRecoveringTransmission() {
		assertEquals(2.0f, AtmosphericAppearance.blend(3.0f, 1.0f, 0.5), 0.0f);
	}

	@Test
	public void invalidFadeDoesNotSilentlyRestoreAtmosphere() {
		assertThrows(IllegalArgumentException.class,
				() -> AtmosphericAppearance.blend(3.5f, 0.2f, Double.NaN));
		assertThrows(IllegalArgumentException.class,
				() -> AtmosphericAppearance.scaleAtmosphericEffect(4.0, Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class,
				() -> AtmosphericAppearance.blend(3.5f, 0.2f, -0.1));
	}

	@Test
	public void atmosphereOnlyEffectsReachZeroInVacuumAndPreserveTheirLegacyEndpoint() {
		assertEquals(0.0, AtmosphericAppearance.scaleAtmosphericEffect(4.0, 0.0), 0.0);
		assertEquals(4.0, AtmosphericAppearance.scaleAtmosphericEffect(4.0, 1.0), 0.0);
		assertEquals(1.0, AtmosphericAppearance.scaleAtmosphericEffect(4.0, 0.25), 0.0);
	}
}
