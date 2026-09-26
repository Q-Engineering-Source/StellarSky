package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static stellarium.world.ring.terrain.SeedPreviewAdmission.Coverage.*;

public class SeedPreviewSessionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final PreviewCacheIdentity identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID(),1);
    private TerrainTileKey key(int x){return new TerrainTileKey(1,0,x,0);}
    private void pump(SeedPreviewSession session,SeedPreviewSession.Request request) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!request.result().toCompletableFuture().isDone()) {
            if(System.nanoTime()>deadline)throw new AssertionError("Session stalled");
            session.tick(4096,10_000_000); Thread.sleep(1);
        }
    }
    @Test public void samplePersistReopenAndCacheHitAvoidsResampling() throws Exception {
        var root=temporary.getRoot().toPath().resolve("cache"); var calls=new AtomicInteger();
        for(int run=0;run<2;run++) {
            var io=new SeedPreviewIo(root,identity,1,4,32);
            var session=new SeedPreviewSession(1,(x,z)->{calls.incrementAndGet();return new SeedTerrainChunk.Empty(x,z);},io,
                    key->CompletableFuture.completedFuture(NO_REAL_DATA),4);
            try {
                var request=session.request(key(0)).orElseThrow();
                assertSame(request,session.request(key(0)).orElseThrow());
                pump(session,request);
                var published=request.result().toCompletableFuture().get().orElseThrow();
                assertTrue(session.current(published)); assertEquals(4096,published.tile().columns().size());
            } finally {session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);}
            assertEquals(16,calls.get());
        }
    }
    @Test public void realEventCancelsPendingQueryAndDoesNotPermitLatePublication() throws Exception {
        var coverage=new CompletableFuture<SeedPreviewAdmission.Coverage>(); var calls=new AtomicInteger();
        var io=new SeedPreviewIo(temporary.getRoot().toPath().resolve("cancel"),identity,1,4,32);
        var session=new SeedPreviewSession(1,(x,z)->{calls.incrementAndGet();return new SeedTerrainChunk.Empty(x,z);},io,k->coverage,4);
        try {
            var request=session.request(key(0)).orElseThrow(); session.realRegion(0,0,16,16);
            coverage.complete(NO_REAL_DATA); session.tick(1,1000);
            assertTrue(request.result().toCompletableFuture().get().isEmpty()); assertEquals(0,calls.get());
            var retry=session.request(key(0)).orElseThrow(); pump(session,retry);
            assertTrue(retry.result().toCompletableFuture().get().isEmpty());
        } finally {session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);}
    }
    @Test public void unknownAndFailedCoverageNeverCallSamplerAndErrorsStayVisible() throws Exception {
        var io=new SeedPreviewIo(temporary.getRoot().toPath().resolve("unknown"),identity,1,4,32);
        var failure=new IllegalStateException("coverage source unavailable");
        var session=new SeedPreviewSession(1,(x,z)->{throw new AssertionError("No sampling expected");},io,
                key->key.x()==0?CompletableFuture.completedFuture(UNKNOWN):CompletableFuture.failedFuture(failure),4);
        try {
            var unknown=session.request(key(0)).orElseThrow(); pump(session,unknown);
            assertTrue(unknown.result().toCompletableFuture().get().isEmpty());
            var failed=session.request(key(1)).orElseThrow(); pump(session,failed);
            var error=assertThrows(ExecutionException.class,()->failed.result().toCompletableFuture().get());
            assertSame(failure,error.getCause());
        } finally {session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);}
    }
    @Test public void closeCompletesPendingRequestsAndNoNewWorkIsAdmitted() throws Exception {
        var io=new SeedPreviewIo(temporary.getRoot().toPath().resolve("close"),identity,1,1,8);
        var session=new SeedPreviewSession(1,SeedTerrainChunk.Empty::new,io,k->new CompletableFuture<>(),1);
        var request=session.request(key(0)).orElseThrow();
        assertTrue(session.request(key(1)).isEmpty());
        session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertTrue(request.result().toCompletableFuture().get().isEmpty());
        assertThrows(IllegalStateException.class,()->session.request(key(1)));
    }
    @Test public void diskBudgetRotatesWithoutRevokingAnAlreadyPublishedMesh() throws Exception {
        var calls=new AtomicInteger();
        var io=new SeedPreviewIo(temporary.newFolder().toPath(),identity,1,1,32);
        var session=new SeedPreviewSession(1,(x,z)->{calls.incrementAndGet();return new SeedTerrainChunk.Empty(x,z);},io,
                key->CompletableFuture.completedFuture(NO_REAL_DATA),4);
        try {
            var first=session.request(key(0)).orElseThrow();pump(session,first);
            var published=first.result().toCompletableFuture().get().orElseThrow();
            var second=session.request(key(1)).orElseThrow();pump(session,second);
            assertTrue(second.result().toCompletableFuture().get().isPresent());
            assertTrue(session.current(published));
            assertTrue(io.read(key(0)).toCompletableFuture().get(10,TimeUnit.SECONDS).isEmpty());
            session.release(key(0));
            var reload=session.request(key(0)).orElseThrow();pump(session,reload);
            assertTrue(reload.result().toCompletableFuture().get().isPresent());assertEquals(48,calls.get());
        } finally {session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);}
    }
}
