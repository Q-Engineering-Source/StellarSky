package stellarium.world.ring.terrain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded serial disk owner. Callbacks may run on the IO thread; marshal gameplay back to its owner. */
public final class SeedPreviewIo {
    private final ThreadPoolExecutor worker;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private SeedPreviewDiskCache cache;
    private Throwable initializationFailure;
    private boolean closing;

    public SeedPreviewIo(Path directory, PreviewCacheIdentity identity, long epoch, int tileCapacity, int pendingCapacity) {
        if (pendingCapacity < 1 || pendingCapacity > 256) throw new IllegalArgumentException("Invalid IO queue capacity");
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(pendingCapacity), runnable -> {
                    var thread = new Thread(() -> {
                        try {
                            try { cache = new SeedPreviewDiskCache(directory, identity, epoch, tileCapacity); }
                            catch (IOException | RuntimeException failure) { initializationFailure = failure; }
                            runnable.run();
                        } finally {
                            try {
                                if (cache != null) cache.close();
                                if (initializationFailure != null) stopped.completeExceptionally(initializationFailure);
                                else stopped.complete(null);
                            } catch (IOException | RuntimeException failure) { stopped.completeExceptionally(failure); }
                        }
                    }, "StellarSky-preview-IO");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        worker.prestartCoreThread();
    }

    public CompletionStage<Optional<SeedTerrainTile>> read(TerrainTileKey key) {
        return submit(() -> cache.read(key));
    }
    public CompletionStage<SeedPreviewAdmission.Coverage> persistedCoverage(AnvilPreviewCoverage regions, TerrainTileKey key) {
        return submit(() -> regions.query(key));
    }
    public CompletionStage<Optional<SeedPreviewDiskCache.Ticket>> beginWrite(TerrainTileKey key) {
        return submit(() -> cache.beginWrite(key));
    }
    public CompletionStage<Boolean> write(SeedPreviewDiskCache.Ticket ticket, SeedTerrainTile tile) {
        return submit(() -> cache.write(ticket, tile));
    }
    public CompletionStage<Boolean> cancelWrite(SeedPreviewDiskCache.Ticket ticket) {
        return submit(() -> cache.cancelWrite(ticket));
    }
    public CompletionStage<Boolean> cancelPending(TerrainTileKey key) {
        return submit(() -> cache.cancelPending(key));
    }
    public CompletionStage<Integer> invalidateRegion(long minX, long minZ, long maxX, long maxZ) {
        return submit(() -> cache.invalidateRegion(minX, minZ, maxX, maxZ));
    }

    /** Drain accepted work, close the file lock on its owner, then complete. Never blocks the caller. */
    public synchronized CompletionStage<Void> closeAsync() {
        closing = true;
        worker.shutdown();
        return stopped.minimalCompletionStage();
    }
    synchronized <T> CompletionStage<T> submit(Operation<T> operation) {
        var result = new CompletableFuture<T>();
        if (closing) {
            result.completeExceptionally(new RejectedExecutionException("Preview IO is closing"));
        } else {
            try {
                worker.execute(() -> {
                    if (initializationFailure != null) { result.completeExceptionally(initializationFailure); return; }
                    try { result.complete(operation.run()); }
                    catch (IOException | RuntimeException failure) { result.completeExceptionally(failure); }
                });
            } catch (RejectedExecutionException full) { result.completeExceptionally(full); }
        }
        return result.minimalCompletionStage();
    }
    @FunctionalInterface interface Operation<T> { T run() throws IOException; }
}
