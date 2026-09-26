package stellarium.client.ring.dh;

import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldSpatialAirFrameOptics;

/** Pure gate: remove DH's flat fog only when the frozen own-air replacement will run. */
public final class DistantHorizonsFogPolicy {
    private DistantHorizonsFogPolicy() {
    }

    public static boolean shouldSkipNativeFog(RingworldCurvatureFrame frame,
                                              RingworldSpatialAirFrameOptics optics) {
        return frame != null && optics != null && optics.usesSpatialAir();
    }
}
