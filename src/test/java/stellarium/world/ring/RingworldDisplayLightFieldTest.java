package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.UUID;
import org.junit.Test;

/** Behavioral contract for entity, hand, and item display light during one render snapshot. */
public class RingworldDisplayLightFieldTest {
    private static final int ORIGINAL = 0x12F000D7;

    @Test
    public void phaseMovesTheReceiverFieldWithoutChangingThePackedBlockNibble() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        RingworldDisplayLightField before = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(0L, 0L, 0.0)));
        RingworldDisplayLightField after = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(50L, 50L, 0.0)));

        assertEquals(0x120000D7, before.shadedPackedLight(ORIGINAL, 0.5, 511, 0.5));
        assertEquals(ORIGINAL, after.shadedPackedLight(ORIGINAL, 0.5, 511, 0.5));
        assertEquals(0x120000D7, after.shadedPackedLight(ORIGINAL, 5.5, 511, 0.5));
    }

    @Test
    public void boardSlabAndTheSunFacingSideUseTheSnapshotGeometry() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        RingworldDisplayLightField field = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(0L, 0L, 0.0)));

        assertEquals(0x120000D7, field.shadedPackedLight(ORIGINAL, 0.5, 511, 0.5));
        assertEquals(0x120000D7, field.shadedPackedLight(ORIGINAL, 0.5, 512, 0.5));
        assertEquals(0x120000D7, field.shadedPackedLight(ORIGINAL, 0.5, 519, 0.5));
        assertEquals(ORIGINAL, field.shadedPackedLight(ORIGINAL, 0.5, 520, 0.5));
    }

    @Test
    public void subtractionSeparatesBoardGapSoftShadowStripAndAllHeights() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 2.0);
        RingworldDisplayLightField field = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(0L, 0L, 0.0)));

        // The board itself is opaque, its gap and the exterior strip are neutral,
        // and the pre-board receiver uses the shared soft transmittance profile.
        assertEquals(15, field.skySubtraction(0.0, 512.0, 0.0));
        assertEquals(0, field.skySubtraction(4.0, 512.0, 0.0));
        assertEquals(7, field.skySubtraction(1.0, 511.0, 0.0));
        assertEquals(0, field.skySubtraction(0.0, 511.0, 8_192.0));
        assertEquals(0, field.skySubtraction(0.0, 520.0, 0.0));
        assertEquals(15, field.skySubtraction(0.0, -1.0, 0.0));
    }

    @Test
	public void subtractionMovesWithTheFrozenPhaseAndMissingPhaseIsDarkOnlyBelowInside() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        RingworldDisplayLightField before = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(0L, 0L, 0.0)));
        RingworldDisplayLightField after = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(50L, 50L, 0.0)));
        RingworldDisplayLightField waiting = new RingworldDisplayLightField(snapshot(sunshade, null));

        assertEquals(15, before.skySubtraction(0.5, 511.0, 0.5));
        assertEquals(0, after.skySubtraction(0.5, 511.0, 0.5));
		assertEquals(15, waiting.skySubtraction(0.5, 511.0, 0.5));
		assertEquals(0, waiting.skySubtraction(0.5, 520.0, 0.5));
		assertEquals(0, waiting.skySubtraction(0.5, 511.0, 8192.0));
    }

    @Test
    public void scalarPhaseQueriesRetainTheEdgeSampleEndpointsWithoutAllocatingSamples() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 2.0);
        RingworldSunshade.Phase phase = sunshade.phase(0L, 0L, 0.0);

        assertEquals(sunshade.sample(phase, 0.0, 0.0).materialOccupied(),
                sunshade.materialOccupied(phase, 0.0, 0.0));
        assertEquals(sunshade.sample(phase, 1.0, 0.0).transmittance(),
                sunshade.transmittance(phase, 1.0, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(phase, 0.0, 8_192.0), 0.0);
        assertEquals(false, sunshade.materialOccupied(phase, 0.0, 8_192.0));
    }

    @Test
    public void stripExteriorStaysNeutralWhileMissingPhaseInsideFailsDark() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);
        RingworldDisplayLightField field = new RingworldDisplayLightField(snapshot(sunshade,
                sunshade.phase(0L, 0L, 0.0)));
        RingworldDisplayLightField waiting = new RingworldDisplayLightField(snapshot(sunshade, null));

        assertEquals(ORIGINAL, field.shadedPackedLight(ORIGINAL, 0.5, 511, 8_192.0));
        assertEquals(0x120000D7, waiting.shadedPackedLight(ORIGINAL, 0.5, 511, 0.5));
    }

	@Test
	public void receiverCoordinatesMustRemainFiniteAcrossNeutralFastPaths() {
		RingworldSunshade empty = new RingworldSunshade(10.0, 0.0, 100L, 0.0, 0.0, 0.0);
		RingworldDisplayLightField field = new RingworldDisplayLightField(snapshot(empty, null));

		assertThrows(IllegalArgumentException.class,
				() -> field.skySubtraction(Double.NaN, 520.0, 0.0));
		assertThrows(IllegalArgumentException.class,
				() -> field.skySubtraction(0.0, Double.POSITIVE_INFINITY, 0.0));
		assertThrows(IllegalArgumentException.class,
				() -> field.skySubtraction(0.0, 520.0, Double.NaN));
	}

    private static RingworldDisplaySnapshot snapshot(RingworldSunshade sunshade, RingworldSunshade.Phase phase) {
        RingworldClockMirror.DisplayTime displayTime = null;
        if (phase != null) {
            UUID generation = UUID.randomUUID();
            RingworldClockSample previous = new RingworldClockSample(0, generation, 1L, phase.previousTime(), true);
            RingworldClockSample current = new RingworldClockSample(0, generation, 2L, phase.currentTime(), false);
            displayTime = new RingworldClockMirror.DisplayTime(previous, current, phase.fraction());
        }
        return new RingworldDisplaySnapshot(new Object(), new Object(), displayTime, sunshade, phase, 512, 8,
                new RingworldRenderObserver(0.0, 64.0, 0.0), 1.0, 0.0);
    }
}
