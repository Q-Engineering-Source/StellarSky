package stellarium.world.ring.terrain;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Owner-thread incremental sampling. No scheduler, networking, disk IO or chunk generation. */
public final class SeedPreviewQueue implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final int capacity;
    private final LongSupplier clock;
    private final ArrayDeque<Job> jobs=new ArrayDeque<>();
    private final HashMap<TerrainTileKey,Job> byKey=new HashMap<>();
    private long epoch;
    private SeedTerrainSource source;
    private SeedPreviewColumnSource columnSource;
    private boolean ticking,closed;

    public SeedPreviewQueue(long epoch,SeedTerrainSource source,int capacity) {
        this(epoch,source,null,capacity,System::nanoTime);
    }
    SeedPreviewQueue(long epoch,SeedTerrainSource source,int capacity,LongSupplier clock) {
        this(epoch,source,null,capacity,clock);
    }
    public SeedPreviewQueue(long epoch,SeedTerrainSource source,SeedPreviewColumnSource columnSource,int capacity) {
        this(epoch,source,columnSource,capacity,System::nanoTime);
    }
    SeedPreviewQueue(long epoch,SeedTerrainSource source,SeedPreviewColumnSource columnSource,int capacity,LongSupplier clock) {
        if(epoch<=0 || capacity<=0 || capacity>256) throw new IllegalArgumentException("Invalid preview queue bounds");
        this.epoch=epoch; this.source=Objects.requireNonNull(source);
        this.columnSource=columnSource;
        this.capacity=capacity; this.clock=Objects.requireNonNull(clock);
    }
    public Optional<Ticket> request(TerrainTileKey key) {
        checkOpen();
        if(key.worldEpoch()!=epoch) throw new IllegalArgumentException("Foreign world epoch");
        long step=1L<<key.level();
        if(key.minBlockX()<Integer.MIN_VALUE || key.minBlockZ()<Integer.MIN_VALUE
                || Math.addExact(key.minBlockX(),63*step)>Integer.MAX_VALUE
                || Math.addExact(key.minBlockZ(),63*step)>Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Preview outside supported block coordinates");
        }
        var existing=byKey.get(key);
        if(existing!=null) return Optional.of(existing.ticket);
        if(jobs.size()==capacity) return Optional.empty();
        var job=new Job(key); jobs.addLast(job); byKey.put(key,job);
        return Optional.of(job.ticket);
    }
    public int pending() { checkThread(); return jobs.size(); }
    public boolean cancel(Ticket ticket) {
        checkThread();
        if(ticket.queue!=this) throw new IllegalArgumentException("Foreign preview ticket");
        var job=byKey.get(ticket.key);
        if(job==null || job.ticket!=ticket) return false;
        remove(job); ticket.future.cancel(false); return true;
    }
    public void switchWorld(long newEpoch,SeedTerrainSource newSource) {
        checkOpen();
        if(newEpoch<=epoch) throw new IllegalArgumentException("World epoch must increase");
        source=Objects.requireNonNull(newSource); epoch=newEpoch;
        cancelAll();
        // A new world must explicitly create its own private noise worker, never reuse the old one.
        columnSource=null;
    }
    /** Time limit is checked between samples; one synchronous density call cannot be preempted. */
    public int tick(int maxColumns,long maxNanos) {
        checkOpen();
        if(maxColumns<=0 || maxNanos<=0) throw new IllegalArgumentException("Invalid tick budget");
        if(ticking) throw new IllegalStateException("Reentrant preview tick");
        ticking=true;
        int work=0; long start=clock.getAsLong();
        try {
            while(!jobs.isEmpty() && work<maxColumns && clock.getAsLong()-start<maxNanos) {
                var job=jobs.getFirst(); work++;
                try {
                    if(job.columns==null) job.columns=new SeedTerrainTile.Column[4096];
                    if(columnSource!=null && job.key.level()>=4) {
                        if(!tickAsync(job)) { work--; break; }
                        continue;
                    }
                    long step=1L<<job.key.level();
                    int stride=SeedTerrainTile.sampleStride(job.key);
                    int strideZ=SeedTerrainTile.sampleStrideZ(job.key);
                    int x=Math.toIntExact(job.key.minBlockX()+((job.next/64)/stride*stride)*step);
                    int z=Math.toIntExact(job.key.minBlockZ()+((job.next%64)/strideZ*strideZ)*step);
                    int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16);
                    long chunkKey=((long)cx<<32)^(cz&0xffffffffL);
                    var chunk=job.chunks.get(chunkKey);
                    if(chunk==null) {
                        chunk=Objects.requireNonNull(source.sample(cx,cz),"Preview source returned null");
                        if(byKey.get(job.key)!=job) continue;
                        if(chunk.chunkX()!=cx || chunk.chunkZ()!=cz) throw new IllegalStateException("Preview source returned another chunk");
                        if(job.chunks.size()==16) job.chunks.remove(job.chunks.keySet().iterator().next());
                        job.chunks.put(chunkKey,chunk);
                    }
                    job.columns[job.next++]=Objects.requireNonNull(chunk.column(Math.floorMod(x,16),Math.floorMod(z,16)));
                    if(job.next==4096) {
                        var tile=new SeedTerrainTile(job.key,Arrays.asList(job.columns));
                        remove(job); job.ticket.future.complete(tile);
                    }
                } catch(RuntimeException failure) {
                    remove(job); job.ticket.future.completeExceptionally(failure);
                }
            }
            return work;
        } finally { ticking=false; }
    }
    /** Returns false when the bounded worker window is full and no ordered result is ready. */
    private boolean tickAsync(Job job) {
        var first=job.pendingColumns.peekFirst();
        if(first!=null && first.isDone()) {
            job.columns[job.next++]=Objects.requireNonNull(first.join(),"Preview worker returned null");
            job.pendingColumns.removeFirst();
            if(job.next==4096) {
                var tile=new SeedTerrainTile(job.key,Arrays.asList(job.columns));
                remove(job); job.ticket.future.complete(tile);
            }
            return true;
        }
        if(job.nextSubmitted==4096 || job.pendingColumns.size()==64) return false;
        long step=1L<<job.key.level();
        int stride=SeedTerrainTile.sampleStride(job.key);
        int strideZ=SeedTerrainTile.sampleStrideZ(job.key);
        int x=Math.toIntExact(job.key.minBlockX()+((job.nextSubmitted/64)/stride*stride)*step);
        int z=Math.toIntExact(job.key.minBlockZ()+((job.nextSubmitted%64)/strideZ*strideZ)*step);
        int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16);
        long chunkKey=((long)cx<<32)^(cz&0xffffffffL);
        var future=job.columnCache.get(chunkKey);
        if(future==null) {
            future=Objects.requireNonNull(columnSource.sample(cx,cz),"Preview column source returned null");
            if(byKey.get(job.key)!=job) return true;
            if(job.columnCache.size()==64) job.columnCache.remove(job.columnCache.keySet().iterator().next());
            job.columnCache.put(chunkKey,future);
        }
        job.pendingColumns.addLast(future); job.nextSubmitted++;
        return true;
    }
    @Override public void close() { checkThread(); closed=true; source=null; cancelAll(); columnSource=null; }
    private void cancelAll() {
        var old=List.copyOf(jobs); jobs.clear(); byKey.clear();
        for(var job:old) { job.release(); job.ticket.future.cancel(false); }
    }
    private void remove(Job job) { byKey.remove(job.key,job); jobs.remove(job); job.release(); }
    private void checkOpen() { checkThread(); if(closed) throw new IllegalStateException("Preview queue closed"); }
    private void checkThread() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Preview queue used off owner thread"); }
    private final class Job {
        private final TerrainTileKey key;
        private final Ticket ticket;
        private final LinkedHashMap<Long,SeedTerrainChunk> chunks=new LinkedHashMap<>(16,0.75f,true);
        private final LinkedHashMap<Long,CompletableFuture<SeedTerrainTile.Column>> columnCache=new LinkedHashMap<>(64,0.75f,true);
        private final ArrayDeque<CompletableFuture<SeedTerrainTile.Column>> pendingColumns=new ArrayDeque<>();
        private SeedTerrainTile.Column[] columns;
        private int next,nextSubmitted;
        private Job(TerrainTileKey key) { this.key=key; ticket=new Ticket(key); }
        private void release() {
            columns=null; chunks.clear();
            for(var future:pendingColumns) future.cancel(false);
            pendingColumns.clear(); columnCache.clear();
        }
    }
    public final class Ticket {
        private final SeedPreviewQueue queue=SeedPreviewQueue.this;
        private final TerrainTileKey key;
        private final CompletableFuture<SeedTerrainTile> future=new CompletableFuture<>();
        private Ticket(TerrainTileKey key) { this.key=key; }
        public TerrainTileKey key() { return key; }
        public CompletionStage<SeedTerrainTile> result() { return future.minimalCompletionStage(); }
    }
}
