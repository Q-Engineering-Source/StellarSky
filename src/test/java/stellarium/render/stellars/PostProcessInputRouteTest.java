package stellarium.render.stellars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import stellarium.world.ring.RingworldThinAtmosphere;

/**
 * CPU contract for selecting the framebuffer representation before the GPU
 * post-process passes begin. This does not emulate RGBE or OpenGL output.
 */
public class PostProcessInputRouteTest {

	@Test
	public void positiveThinAtmosphereFadeKeepsTheAtmosphereRgbEInput() {
		double fadeBelowThePhysicalTop = RingworldThinAtmosphere.fadeAt(255.999_999, 0.0, 192.0);
		assertTrue(fadeBelowThePhysicalTop > 0.0);

		assertEquals(PostProcessInputRoute.ATMOSPHERE_RGBE_FRAME1,
				PostProcessInputRoute.select(true, fadeBelowThePhysicalTop));
	}

	@Test
	public void physicalTopAndExteriorZRouteEnabledAtmosphereToTheVacuumLinearScene() {
		double fadeAtPhysicalTop = RingworldThinAtmosphere.fadeAt(256.0, 0.0, 192.0);
		double fadeOutsideTheRingworldStrip = RingworldThinAtmosphere.fadeAt(64.0, 8_192.0, 192.0);

		assertEquals(PostProcessInputRoute.VACUUM_LINEAR_SCENE,
				PostProcessInputRoute.select(true, fadeAtPhysicalTop));
		assertEquals(PostProcessInputRoute.VACUUM_LINEAR_SCENE,
				PostProcessInputRoute.select(true, fadeOutsideTheRingworldStrip));
	}

	@Test
	public void disabledAtmosphereNeverSelectsTheAtmosphereRgbEInput() {
		assertEquals(PostProcessInputRoute.VACUUM_LINEAR_SCENE,
				PostProcessInputRoute.select(false, 1.0));
	}

	@Test
	public void spatialAirKeepsTheExistingVacuumLinearToRgbEPostProcessPath() {
		assertEquals(PostProcessInputRoute.VACUUM_LINEAR_SCENE,
				PostProcessInputRoute.select(true, 1.0, true));
	}

	@Test
	public void nonFiniteFadeFailsBeforeSelectingAGpuInputContract() {
		assertThrows(IllegalArgumentException.class,
				() -> PostProcessInputRoute.select(true, Double.NaN));
	}
}
