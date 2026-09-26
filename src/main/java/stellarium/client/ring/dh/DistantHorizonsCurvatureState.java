package stellarium.client.ring.dh;

import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.client.ring.RingworldSpatialAirFrameOptics;

/**
 * The DH adapter may consume only the frame admitted by StellarSky's world-pass coordinator.
 * It never discovers a camera or enables curvature on its own.
 */
public final class DistantHorizonsCurvatureState {
    private DistantHorizonsCurvatureState() {
    }

    public static RingworldCurvatureFrame currentFrame() {
        return RingworldRenderSnapshots.currentDistantCurvatureFrame();
    }

    /** Reads only the render-scope's frozen snapshot and optics, never live client settings. */
    public static boolean replacesNativeFog() {
        RingworldCurvatureFrame frame = currentFrame();
        var snapshot = RingworldRenderSnapshots.current();
        if (frame == null || snapshot == null || !frame.belongsTo(snapshot.world(), snapshot.scene())) {
            return false;
        }
        RingworldSpatialAirFrameOptics optics = RingworldRenderSnapshots.currentFrameOpticsFor(
                snapshot.world(), snapshot.scene());
        return DistantHorizonsFogPolicy.shouldSkipNativeFog(frame, optics);
    }
}
