package stellarium.world.ring.terrain;

import java.util.Objects;
import java.util.UUID;

/** Opaque server-issued persistent namespace. Generation ID must change with generator/settings. */
public record PreviewCacheIdentity(UUID serverId, UUID worldId, int dimension, UUID generationId, int samplerVersion) {
    public PreviewCacheIdentity {
        Objects.requireNonNull(serverId); Objects.requireNonNull(worldId); Objects.requireNonNull(generationId);
        if (samplerVersion <= 0) throw new IllegalArgumentException("Invalid sampler version");
    }
}
