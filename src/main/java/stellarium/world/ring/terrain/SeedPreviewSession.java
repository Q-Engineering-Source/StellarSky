package stellarium.world.ring.terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/** Gameplay-owned bounded pipeline. Tick polls completions; it never waits for disk or coverage IO. */
public final class SeedPreviewSession {
    @FunctionalInterface public interface CoverageProbe {
        CompletionStage<SeedPreviewAdmission.Coverage> query(TerrainTileKey key);
    }
    private enum Phase { COVERAGE, READ, RESERVE, SAMPLE, WRITE }
    private final Thread owner = Thread.currentThread();
    private final long epoch;
    private final int capacity;
    private final SeedPreviewQueue queue;
    private final SeedPreviewIo io;
    private final SeedPreviewAdmission admission;
    private final CoverageProbe coverage;
    private final Map<TerrainTileKey, Job> jobs = new LinkedHashMap<>();
    private final ArrayList<CompletableFuture<Integer>> invalidations = new ArrayList<>();
    private boolean closed;
    private Throwable failed;

    public SeedPreviewSession(long epoch, SeedTerrainSource source, SeedPreviewIo io,
                              CoverageProbe coverage, int capacity) {
        this(epoch,source,null,io,coverage,capacity);
    }
    public SeedPreviewSession(long epoch, SeedTerrainSource source, SeedPreviewColumnSource columnSource,
                              SeedPreviewIo io, CoverageProbe coverage, int capacity) {
        if (capacity < 1 || capacity > 256) throw new IllegalArgumentException("Invalid session capacity");
        this.epoch = epoch; this.capacity = capacity; this.io = Objects.requireNonNull(io);
        this.coverage = Objects.requireNonNull(coverage);
        queue = new SeedPreviewQueue(epoch, source, columnSource, capacity);
        admission = new SeedPreviewAdmission(epoch, capacity);
    }
    public Optional<Request> request(TerrainTileKey key) {
        checkOpen();
        if (key.worldEpoch() != epoch) throw new IllegalArgumentException("Foreign session epoch");
        var previous = jobs.get(key);
        if (previous != null) return Optional.of(previous.request);
        var query = admission.beginCoverage(key);
        if (query.isEmpty()) return Optional.empty();
        var job = new Job(key, query.get()); jobs.put(key, job);
        try { job.pending = Objects.requireNonNull(coverage.query(key)).toCompletableFuture(); }
        catch (RuntimeException failure) { finish(job, failure); }
        return Optional.of(job.request);
    }
    /** All mutation/completion publication occurs here on the gameplay owner. */
    public void tick(int maxColumns, long maxSampleNanos) {
        checkOpen();
        try {
            for (var future : invalidations) if (future.isDone()) future.join();
            invalidations.removeIf(CompletableFuture::isDone);
        } catch (CompletionException failure) { failSession(failure.getCause()); return; }
        queue.tick(maxColumns, maxSampleNanos);
        for (var job : new ArrayList<>(jobs.values())) {
            if (jobs.get(job.key) != job || !job.pending.isDone()) continue;
            try { advance(job); }
            catch (CompletionException failure) { finish(job, failure.getCause()); }
            catch (RuntimeException failure) { finish(job, failure); }
        }
        int cov=0,read=0,reserve=0,sample=0,write=0;
        for (var job : jobs.values())
            switch (job.phase) {
                case COVERAGE -> cov++;
                case READ -> read++;
                case RESERVE -> reserve++;
                case SAMPLE -> sample++;
                case WRITE -> write++;
            }
        TerrainPreviewTrace.serverJobs(cov,read,reserve,sample,write);
    }
    private void advance(Job job) {
        switch (job.phase) {
            case COVERAGE -> {
                var result = (SeedPreviewAdmission.Coverage) job.pending.join();
                TerrainPreviewTrace.serverCoverageVerdict(true,result);
                if (!admission.coverage(job.query, result)) {
                    TerrainPreviewTrace.serverBeginPreviewEmpty(true,false);finishEmpty(job); return;
                }
                var ticket = admission.beginPreview(job.key);
                if (ticket.isEmpty()) {
                    TerrainPreviewTrace.serverBeginPreviewEmpty(false,true);finishEmpty(job); return;
                }
                job.preview = ticket.get(); job.phase = Phase.READ;
                job.pending = io.read(job.key).toCompletableFuture();
            }
            case READ -> {
                @SuppressWarnings("unchecked") var cached = (Optional<SeedTerrainTile>) job.pending.join();
                if (cached.isPresent()) { publish(job, cached.get()); return; }
                job.phase = Phase.RESERVE; job.pending = io.beginWrite(job.key).toCompletableFuture();
            }
            case RESERVE -> {
                @SuppressWarnings("unchecked") var reservation = (Optional<SeedPreviewDiskCache.Ticket>) job.pending.join();
                if (reservation.isEmpty()) throw new RejectedExecutionException("Preview disk capacity exhausted");
                job.write = reservation.get();
                job.sample = queue.request(job.key).orElseThrow(() -> new RejectedExecutionException("Preview sample capacity exhausted"));
                job.phase = Phase.SAMPLE; job.pending = job.sample.result().toCompletableFuture();
            }
            case SAMPLE -> {
                job.tile = (SeedTerrainTile) job.pending.join(); job.phase = Phase.WRITE;
                job.pending = io.write(job.write, job.tile).toCompletableFuture();
            }
            case WRITE -> {
                if (!(Boolean) job.pending.join()) { finishEmpty(job); return; }
                publish(job, job.tile);
            }
        }
    }
    private void publish(Job job, SeedTerrainTile tile) {
        var publication = admission.publish(job.preview, tile);
        jobs.remove(job.key, job); job.request.future.complete(publication);
    }
    public boolean usable() { checkThread(); return !closed && failed == null; }
    public boolean current(SeedPreviewAdmission.Publication publication) { checkOpen(); return admission.current(publication); }
    /** Revoke gameplay eligibility first. IO invalidation failure disables further use of the session. */
    public void realRegion(long minX, long minZ, long maxX, long maxZ) {
        checkOpen(); admission.realRegion(minX, minZ, maxX, maxZ);
        for (var job : new ArrayList<>(jobs.values())) {
            long width = 64L << job.key.level();
            if (job.key.minBlockX() < maxX && job.key.minBlockZ() < maxZ
                    && job.key.minBlockX() + width > minX && job.key.minBlockZ() + width > minZ) finishEmpty(job);
        }
        if (invalidations.size() >= 2 * capacity + 1) {
            failSession(new RejectedExecutionException("Preview invalidation capacity exhausted")); return;
        }
        invalidations.add(io.invalidateRegion(minX, minZ, maxX, maxZ).toCompletableFuture());
    }
    public void release(TerrainTileKey key) {
        checkOpen(); var job = jobs.get(key); if (job != null) finishEmpty(job);
        admission.release(key);
    }
    public CompletionStage<Void> closeAsync() {
        checkThread();
        if (!closed) {
            closed = true;
            for (var job : new ArrayList<>(jobs.values())) finishEmpty(job);
            queue.close(); admission.close(); invalidations.clear();
        }
        return io.closeAsync();
    }
    private void finishEmpty(Job job) { cleanup(job); job.request.future.complete(Optional.empty()); }
    private void finish(Job job, Throwable failure) { cleanup(job); job.request.future.completeExceptionally(failure); }
    private void cleanup(Job job) {
        jobs.remove(job.key, job);
        if (job.sample != null) queue.cancel(job.sample);
        // A region invalidation retires pending reservations; ordinary cancellation also needs cleanup.
        if (!closed && failed == null) {
            if (invalidations.size() >= 2 * capacity + 1) {
                failSession(new RejectedExecutionException("Preview cleanup capacity exhausted")); return;
            }
            var cancellation = io.cancelPending(job.key).toCompletableFuture();
            // Retain the bounded completion so rejection/IO failures cannot disappear silently.
            invalidations.add(cancellation.thenApply(ignored -> 0));
        }
    }
    private void failSession(Throwable failure) {
        failed = failure;
        for (var job : new ArrayList<>(jobs.values())) finish(job, failure);
    }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Off session owner"); }
    private void checkOpen() {
        checkThread();
        if (closed) throw new IllegalStateException("Preview session closed");
        if (failed != null) throw new IllegalStateException("Preview session failed", failed);
    }
    private final class Job {
        final TerrainTileKey key; final SeedPreviewAdmission.Query query; final Request request = new Request();
        Phase phase = Phase.COVERAGE; CompletableFuture<?> pending;
        SeedPreviewAdmission.Preview preview; SeedPreviewQueue.Ticket sample;
        SeedPreviewDiskCache.Ticket write; SeedTerrainTile tile;
        Job(TerrainTileKey key, SeedPreviewAdmission.Query query) { this.key = key; this.query = query; }
    }
    public static final class Request {
        private final CompletableFuture<Optional<SeedPreviewAdmission.Publication>> future = new CompletableFuture<>();
        public CompletionStage<Optional<SeedPreviewAdmission.Publication>> result() { return future.minimalCompletionStage(); }
    }
}
