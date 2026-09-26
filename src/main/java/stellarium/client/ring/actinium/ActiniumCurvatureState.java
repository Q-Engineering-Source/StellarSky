package stellarium.client.ring.actinium;

import stellarium.client.ring.RingworldRenderSnapshots;

/** Uses only the frame already admitted by the SS world-pass scope. Never enables terrain alone. */
public final class ActiniumCurvatureState {
    private ActiniumCurvatureState() { }

    public static boolean active() {
        var snapshot = RingworldRenderSnapshots.current();
        return snapshot != null && RingworldRenderSnapshots.currentCurvatureFrameFor(
                snapshot.world(), snapshot.scene()) != null;
    }
}
