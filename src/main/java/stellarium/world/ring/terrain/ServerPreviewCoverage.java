package stellarium.world.ring.terrain;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import stellarium.world.ring.terrain.TerrainPreviewTrace.CoverageUnknownCause;

/** Explicitly attached world scope. Never creates/loads chunks or uses a swallowed-IO-error existence API. */
public final class ServerPreviewCoverage implements SeedPreviewSession.CoverageProbe, AutoCloseable {
    private final WorldServer world;
    private final long epoch;
    private final SeedPreviewIo io;
    private final AnvilPreviewCoverage regions;
    private final AnvilPendingCoverage pendingWrites;
    private final Consumer<RuntimeException> failureHandler;
    private final ArrayList<Query> pending = new ArrayList<>();
    private SeedPreviewSession session;
    private long revision;
    private boolean closed;
    public ServerPreviewCoverage(WorldServer world, long epoch, SeedPreviewIo io) {
        this(world,epoch,io,failure->{throw failure;});
    }
    public ServerPreviewCoverage(WorldServer world, long epoch, SeedPreviewIo io,Consumer<RuntimeException> failureHandler) {
        this.world = world; this.epoch = epoch; this.io = io;
        this.failureHandler=Objects.requireNonNull(failureHandler);
        checkThread();
        if (epoch <= 0 || world.getChunkProvider().chunkLoader.getClass() != AnvilChunkLoader.class
                || !(world.getChunkProvider().chunkLoader instanceof AnvilPendingCoverage writes)) {
            throw new UnsupportedOperationException("Unadmitted preview chunk loader");
        }
        pendingWrites = writes;
        var loader = (AnvilChunkLoader)world.getChunkProvider().chunkLoader;
        regions = new AnvilPreviewCoverage(loader.chunkSaveLocation.toPath().resolve("region"),64);
        MinecraftForge.EVENT_BUS.register(this);
    }
    public void attach(SeedPreviewSession session) {
        checkOpen(); if (this.session != null) throw new IllegalStateException("Coverage already attached");
        this.session = Objects.requireNonNull(session);
    }
    @Override public CompletionStage<SeedPreviewAdmission.Coverage> query(TerrainTileKey key) {
        checkOpen();
        if (session == null || key.worldEpoch() != epoch) throw new IllegalStateException("Unattached/foreign preview query");
        TerrainPreviewTrace.serverCoveragePending(pending.size(),256);
        if (pending.size() >= 256) {
            TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.QUERY_CAPACITY);
            return CompletableFuture.failedFuture(new RejectedExecutionException("Coverage query capacity exhausted"));
        }
        var immediate = live(key);
        if (immediate != SeedPreviewAdmission.Coverage.NO_REAL_DATA) return CompletableFuture.completedFuture(immediate);
        var result = new CompletableFuture<SeedPreviewAdmission.Coverage>();
        pending.add(new Query(key,revision,io.persistedCoverage(regions,key).toCompletableFuture(),result));
        return result.minimalCompletionStage();
    }
    public void tick() {
        checkOpen();
        for (var query : new ArrayList<>(pending)) {
            if (!query.disk.isDone()) continue;
            pending.remove(query);
            try {
                var result = query.disk.join();
                var now = live(query.key);
                if (now != SeedPreviewAdmission.Coverage.NO_REAL_DATA) result = now;
                else if (query.revision != revision) {
                    TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.REVISION_CHANGED);
                    result = SeedPreviewAdmission.Coverage.UNKNOWN;
                }
                query.result.complete(result);
            } catch (CompletionException failure) { query.result.completeExceptionally(failure.getCause()); }
        }
    }
    private SeedPreviewAdmission.Coverage live(TerrainTileKey key) {
        long width=64L<<key.level(); int examined=0;
        for(var chunk:world.getChunkProvider().getLoadedChunks()) {
            if(++examined>65536) {
                TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.LIVE_EXAMINED);
                return SeedPreviewAdmission.Coverage.UNKNOWN;
            }
            long x=(long)chunk.x*16,z=(long)chunk.z*16;
            if(x<key.minBlockX()+width&&z<key.minBlockZ()+width&&x+16>key.minBlockX()&&z+16>key.minBlockZ())
                return SeedPreviewAdmission.Coverage.REAL_DATA;
        }
        var pendingCoverage=pendingWrites.stellarium$hasPendingPreviewCoverage();
        if(pendingCoverage)TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.LIVE_PENDING_WRITES);
        return pendingCoverage?SeedPreviewAdmission.Coverage.UNKNOWN:SeedPreviewAdmission.Coverage.NO_REAL_DATA;
    }
    @SubscribeEvent public void chunkLoaded(ChunkEvent.Load event) {
        if(event.getWorld()!=world || closed)return;
        checkThread(); revision=Math.incrementExact(revision);
        if(session!=null) {
            long x=(long)event.getChunk().x*16,z=(long)event.getChunk().z*16;
            try {session.realRegion(x,z,x+16,z+16);}
            catch(RuntimeException failure){close();failureHandler.accept(failure);}
        }
    }
    @Override public void close() {
        checkThread(); if(closed)return; closed=true;
        MinecraftForge.EVENT_BUS.unregister(this);
        for(var query:pending) {
            TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.CLOSED);
            query.result.complete(SeedPreviewAdmission.Coverage.UNKNOWN);
        }
        pending.clear(); session=null;
    }
    private void checkThread() {
        if(world.getMinecraftServer()==null||!world.getMinecraftServer().isCallingFromMinecraftThread())
            throw new IllegalStateException("Off server coverage owner");
    }
    private void checkOpen(){checkThread();if(closed)throw new IllegalStateException("Coverage closed");}
    private record Query(TerrainTileKey key,long revision,CompletableFuture<SeedPreviewAdmission.Coverage> disk,
                         CompletableFuture<SeedPreviewAdmission.Coverage> result) {}
}
