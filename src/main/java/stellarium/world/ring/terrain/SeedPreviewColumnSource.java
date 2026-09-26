package stellarium.world.ring.terrain;

import java.util.concurrent.CompletableFuture;

/** Captures world inputs on the caller thread and computes one aligned chunk column asynchronously. */
@FunctionalInterface
public interface SeedPreviewColumnSource {
    CompletableFuture<SeedTerrainTile.Column> sample(int chunkX, int chunkZ);
}
