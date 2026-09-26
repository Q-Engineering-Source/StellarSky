package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class RingworldSkyIlluminationTest {

	private static final double EPSILON = 1.0e-12;

	@Test
	public void nullSnapshotRetainsNonRingLegacyConsumerInputs() {
		RingworldSkyIllumination illumination = RingworldSkyIllumination.from(null);

		assertEquals(RingworldSkyIllumination.Mode.NOT_RING, illumination.mode());
		assertEquals(0.0, illumination.directSunTransmission(), 0.0);
		assertEquals(0.0, illumination.atmosphereScatterTransmission(), 0.0);
		assertEquals(0.37, illumination.lowPowerDomeTransmission(0.37), 0.0);
		assertEquals(0.58, illumination.previousSkyDarkeningAlpha(0.58, 1.0), 0.0);
	}

	@Test
	public void unavailablePhaseDoesNotInventSolarLight() {
		RingworldSkyIllumination illumination = RingworldSkyIllumination.from(snapshot(
				shade(40.0, 10.0), null, 99.0, 0.0, 0.0, 0.8));

		assertEquals(RingworldSkyIllumination.Mode.PHASE_UNAVAILABLE, illumination.mode());
		assertEquals(0.0, illumination.directSunTransmission(), 0.0);
		assertEquals(0.0, illumination.atmosphereScatterTransmission(), 0.0);
		assertFalse(illumination.canRenderDirectSunScatter());
		assertEquals(0.0, illumination.lowPowerDomeTransmission(0.8), 0.0);
		assertEquals(1.0, illumination.previousSkyDarkeningAlpha(0.58, 0.8), 0.0);
	}

	@Test
	public void unavailablePhaseUsesOnlyKnownGeometryForDirectAndOpaqueDecisions() {
		RingworldSunshade full = shade(100.0, 0.0);
		RingworldSunshade empty = shade(0.0, 0.0);
		RingworldSkyIllumination below = RingworldSkyIllumination.from(snapshot(full, null, 99, 0, 0, 0.5));
		RingworldSkyIllumination above = RingworldSkyIllumination.from(snapshot(full, null, 120, 0, 0, 0.5));
		RingworldSkyIllumination outside = RingworldSkyIllumination.from(snapshot(full, null, 99, 0, 8192, 0.5));
		RingworldSkyIllumination emptyField = RingworldSkyIllumination.from(snapshot(empty, null, 99, 0, 0, 0.5));
		assertEquals(0.0, below.directSunTransmission(), 0.0); assertFalse(below.canRenderOpaqueSun());
		assertEquals(1.0, above.directSunTransmission(), 0.0); assertTrue(above.canRenderOpaqueSun());
		assertEquals(1.0, outside.directSunTransmission(), 0.0); assertTrue(outside.canRenderDirectSunScatter());
		assertEquals(1.0, emptyField.directSunTransmission(), 0.0); assertTrue(emptyField.canRenderOpaqueSun());
		assertFalse(below.canRenderPreviousSky(0.5)); assertTrue(outside.canRenderPreviousSky(0.5));
	}

	@Test
	public void belowTheSunshadeUsesSoftPhaseTransmissionAtEndpointsAndIntermediate() {
		RingworldSunshade shade = shade(40.0, 10.0);
		RingworldSunshade.Phase phase = phase(shade);

		assertDirect(shade, phase, 99.0, 0.0, 0.0, 0.0);
		assertDirect(shade, phase, 99.0, 15.0, 0.0, 0.5);
		assertDirect(shade, phase, 99.0, 30.0, 0.0, 1.0);
	}

	@Test
	public void slabUsesMaterialOccupancyButAboveTheUpperFaceIsUnblocked() {
		RingworldSunshade shade = shade(40.0, 10.0);
		RingworldSunshade.Phase phase = phase(shade);

		assertDirect(shade, phase, 100.0, 0.0, 0.0, 0.0);
		assertDirect(shade, phase, 100.0, 30.0, 0.0, 1.0);
		assertDirect(shade, phase, 120.0, 0.0, 0.0, 1.0);
	}

	@Test
	public void fullAndEmptySunshadesAndExteriorStripStayFinite() {
		RingworldSunshade full = shade(100.0, 0.0);
		RingworldSunshade empty = shade(0.0, 0.0);
		assertDirect(full, phase(full), 99.0, 0.0, 0.0, 0.0);
		assertDirect(empty, phase(empty), 99.0, 0.0, 0.0, 1.0);

		RingworldSkyIllumination exterior = RingworldSkyIllumination.from(snapshot(
				full, phase(full), 99.0, 0.0, RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE, 0.0));
		assertEquals(1.0, exterior.directSunTransmission(), 0.0);
		assertEquals(0.0, exterior.atmosphereScatterTransmission(), 0.0);
		assertTrue(Double.isFinite(exterior.directSunTransmission()));
		assertTrue(Double.isFinite(exterior.atmosphereScatterTransmission()));
	}

	@Test
	public void consumerSelectionsSeparateDirectLightAtmosphereAndPreviousSkyAlpha() {
		RingworldSunshade shade = shade(40.0, 10.0);
		RingworldSkyIllumination gap = RingworldSkyIllumination.from(snapshot(
				shade, phase(shade), 99.0, 30.0, 0.0, 0.25));
		RingworldSkyIllumination partial = RingworldSkyIllumination.from(snapshot(
				shade, phase(shade), 99.0, 15.0, 0.0, 0.25));
		RingworldSkyIllumination shadow = RingworldSkyIllumination.from(snapshot(
				shade, phase(shade), 99.0, 0.0, 0.0, 0.25));

		assertEquals(1.0, gap.directSunTransmission(), 0.0);
		assertEquals(0.25, gap.atmosphereScatterTransmission(), 0.0);
		assertEquals(1.0, gap.directScatterLightColor(0.0), 0.0);
		assertEquals(0.25, gap.lowPowerDomeTransmission(0.9), 0.0);
		assertEquals(0.9, gap.previousSkyDarkeningAlpha(0.6, 0.9), EPSILON);

		assertEquals(0.5, partial.directSunTransmission(), EPSILON);
		assertEquals(0.125, partial.atmosphereScatterTransmission(), EPSILON);
		assertEquals(0.5, partial.directScatterLightColor(0.0), EPSILON);
		assertEquals(0.125, partial.lowPowerDomeTransmission(0.9), EPSILON);
		assertEquals(0.95, partial.previousSkyDarkeningAlpha(0.6, 0.9), EPSILON);

		assertEquals(0.0, shadow.directSunTransmission(), 0.0);
		assertEquals(0.0, shadow.atmosphereScatterTransmission(), 0.0);
		assertEquals(0.0, shadow.directScatterLightColor(1.0), 0.0);
		assertFalse(shadow.canRenderDirectSunScatter());
		assertEquals(1.0, shadow.previousSkyDarkeningAlpha(0.6, 0.9), 0.0);
	}

	@Test
	public void consumerInputsMustBeFiniteUnitValues() {
		RingworldSkyIllumination nonRing = RingworldSkyIllumination.from(null);
		assertThrows(IllegalArgumentException.class,
				() -> nonRing.directScatterLightColor(Double.NaN));
		assertThrows(IllegalArgumentException.class,
				() -> nonRing.lowPowerDomeTransmission(1.01));
		assertThrows(IllegalArgumentException.class,
				() -> nonRing.canRenderPreviousSky(-0.01));
		assertThrows(IllegalArgumentException.class,
				() -> nonRing.previousSkyDarkeningAlpha(Double.POSITIVE_INFINITY, 1.0));
	}

	private static void assertDirect(RingworldSunshade shade, RingworldSunshade.Phase phase,
			double observerY, double observerX, double observerZ, double expected) {
		RingworldSkyIllumination illumination = RingworldSkyIllumination.from(snapshot(
				shade, phase, observerY, observerX, observerZ, 0.8));
		assertEquals(RingworldSkyIllumination.Mode.READY, illumination.mode());
		assertEquals(expected, illumination.directSunTransmission(), EPSILON);
		assertEquals(expected * 0.8, illumination.atmosphereScatterTransmission(), EPSILON);
		assertTrue(Double.isFinite(illumination.directSunTransmission()));
		assertTrue(Double.isFinite(illumination.atmosphereScatterTransmission()));
	}

	private static RingworldSunshade shade(double width, double feather) {
		return new RingworldSunshade(100.0, width, 100L, 0.0, 0.0, feather);
	}

	private static RingworldSunshade.Phase phase(RingworldSunshade shade) {
		return shade.phase(0L, 0L, 0.0);
	}

	private static RingworldDisplaySnapshot snapshot(RingworldSunshade shade,
			RingworldSunshade.Phase phase, double y, double x, double z, double atmosphereFade) {
		return new RingworldDisplaySnapshot(new Object(), new Object(),
				phase == null ? null : displayTime(), shade, phase,
				100, 20, new RingworldRenderObserver(x, y, z), atmosphereFade, 0.3);
	}

	private static RingworldClockMirror.DisplayTime displayTime() {
		RingworldClockSample sample = new RingworldClockSample(0, UUID.randomUUID(), 1L, 0L, false);
		return new RingworldClockMirror.DisplayTime(sample, sample, 0.0);
	}
}
