package stellarium.client.ring.dh;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import org.junit.Test;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldSpatialAirFrameOptics;
import stellarium.world.ring.RingworldClockMirror;
import stellarium.world.ring.RingworldClockSample;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;

/** DH fog delegation is decided from immutable optics, never a live client setting. */
public class DistantHorizonsFogPolicyTest {
    @Test public void skipsFlatDhFogOnlyForTheFrozenSpatialAirReplacement() {
        RingworldDisplaySnapshot snapshot = snapshot();
        RingworldCurvatureFrame frame = frame(snapshot);
        assertTrue(DistantHorizonsFogPolicy.shouldSkipNativeFog(frame,
                RingworldSpatialAirFrameOptics.freeze(snapshot, true, false, true)));
        assertFalse(DistantHorizonsFogPolicy.shouldSkipNativeFog(frame,
                RingworldSpatialAirFrameOptics.freeze(snapshot, false, false, true)));
        assertFalse(DistantHorizonsFogPolicy.shouldSkipNativeFog(frame,
                RingworldSpatialAirFrameOptics.freeze(snapshot, true, true, true)));
        assertFalse(DistantHorizonsFogPolicy.shouldSkipNativeFog(frame,
                RingworldSpatialAirFrameOptics.freeze(snapshot, true, false, false)));
        assertFalse(DistantHorizonsFogPolicy.shouldSkipNativeFog(null,
                RingworldSpatialAirFrameOptics.freeze(snapshot, true, false, true)));
    }

    private static RingworldDisplaySnapshot snapshot() {
        Object world = new Object(), scene = new Object();
        RingworldClockSample clock = new RingworldClockSample(0, UUID.randomUUID(), 1L, 0L, true);
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        return new RingworldDisplaySnapshot(world, scene, new RingworldClockMirror.DisplayTime(clock, clock, 0.0),
                sunshade, sunshade.phase(0L, 0L, 0.0), 512, 8,
                new RingworldRenderObserver(0.0, 64.0, 0.0), 1.0, 0.2);
    }

    private static RingworldCurvatureFrame frame(RingworldDisplaySnapshot snapshot) {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new RingworldCurvatureFrame(snapshot.world(), snapshot.scene(), snapshot.observer(),
                149_597_870_700.0, identity, identity, 0, 0, 800, 600);
    }
}
