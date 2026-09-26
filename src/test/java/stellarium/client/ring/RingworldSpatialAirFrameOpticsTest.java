package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

import stellarium.world.ring.RingworldAirProfile;
import stellarium.world.ring.RingworldClockMirror;
import stellarium.world.ring.RingworldClockSample;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;

/** Mode choice is CPU-only: construction must not initialize Minecraft or query OpenGL. */
public class RingworldSpatialAirFrameOpticsTest {
    @Test
    public void phaseReadyFullRendererOwnsOldAtmosphereOnlyOnce() {
        RingworldSpatialAirFrameOptics optics = RingworldSpatialAirFrameOptics.freeze(snapshot(true), true, false);
        assertTrue(optics.usesSpatialAir());
        assertEquals(0.0, optics.legacyAtmosphereFade(), 0.0);
        assertNotNull(optics.queries());
    }

    @Test
    public void lowPowerDisabledAtmosphereAndMissingPhasePreserveLegacyMode() {
        assertFalse(RingworldSpatialAirFrameOptics.freeze(snapshot(true), true, true).usesSpatialAir());
        assertFalse(RingworldSpatialAirFrameOptics.freeze(snapshot(true), false, false).usesSpatialAir());
        RingworldSpatialAirFrameOptics waiting = RingworldSpatialAirFrameOptics.freeze(snapshot(false), true, false);
        assertFalse(waiting.usesSpatialAir());
        assertEquals(0.4, waiting.legacyAtmosphereFade(), 0.0);
		assertFalse(RingworldSpatialAirFrameOptics.freeze(snapshot(true), true, false, false).usesSpatialAir());
    }

    private static RingworldDisplaySnapshot snapshot(boolean phaseReady) {
        RingworldClockSample sample = new RingworldClockSample(0, UUID.randomUUID(), 1L, 0L, true);
        RingworldSunshade sunshade = new RingworldSunshade(100.0, 40.0, 100L, 0.0, 0.0, 0.0, 16.0);
        return new RingworldDisplaySnapshot(new Object(), new Object(), phaseReady
                ? new RingworldClockMirror.DisplayTime(sample, sample, 0.0) : null, sunshade,
                phaseReady ? sunshade.phase(0L, 0L, 0.0) : null, 512, 8,
                new RingworldRenderObserver(0.0, 64.0, 0.0), 0.4, 0.2,
                new RingworldAirProfile(0.0, 192.0, 256.0));
    }
}
