package stellarium.world.ring.terrain;

import java.security.MessageDigest;

/** Exact admitted noise state, server-private and independent of sampled output buffers. */
public interface PreviewNoiseState {
    void stellarium$appendNoiseState(MessageDigest digest);
}
