package stellarium.world.ring.terrain;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import stellarium.world.ring.generation.RingworldChunkGenerator;

/** One private noise owner per world. The gameplay thread captures biome scalars before submitting work. */
public final class OverworldAsyncColumnSource implements SeedPreviewColumnSource, AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final RingworldChunkGenerator generator;
    private final OverworldColumnSampler.Context context;
    private final ThreadPoolExecutor worker;
    private boolean closed;

    public OverworldAsyncColumnSource(RingworldChunkGenerator generator) {
        this.generator=Objects.requireNonNull(generator);
        context=generator.createPreviewColumnContext();
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->{
            var thread=new Thread(r,"StellarSky-preview-column");
            thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public CompletableFuture<SeedTerrainTile.Column> sample(int chunkX,int chunkZ) {
        checkOwner();
        if(closed) throw new IllegalStateException("Column worker closed");
        var biomes=generator.capturePreviewColumnBiomes(chunkX,chunkZ);
        if(biomes==null) return CompletableFuture.completedFuture(SeedTerrainTile.Column.EMPTY);
        return CompletableFuture.supplyAsync(()->context.sample(chunkX,chunkZ,biomes),worker);
    }

    @Override public void close() {
        checkOwner();
        if(closed)return;
        closed=true;
        worker.shutdownNow();
    }

    private void checkOwner() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Column source used off server owner");
    }
}
