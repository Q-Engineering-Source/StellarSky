package stellarium.world.ring.terrain;

/** Server-thread capture from the live generator. The caller must admit the supplied pipeline revision. */
public interface OverworldFingerprintSource {
    byte[] stellarium$capturePreviewFingerprint(byte[] admittedPipelineRevision);
}
