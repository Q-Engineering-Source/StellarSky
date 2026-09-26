package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SeedPreviewIoTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final PreviewCacheIdentity identity = new PreviewCacheIdentity(UUID.randomUUID(), UUID.randomUUID(),0,UUID.randomUUID(),1);
    private final TerrainTileKey key = new TerrainTileKey(1,0,0,0);
    private <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(10,TimeUnit.SECONDS); }
    private SeedTerrainTile tile() { return new SeedTerrainTile(key,Collections.nCopies(4096,SeedTerrainTile.Column.EMPTY)); }

    @Test public void invalidationRejectsLateWriteAndCloseReleasesLock() throws Exception {
        var root=temporary.getRoot().toPath().resolve("cache");
        var io=new SeedPreviewIo(root,identity,1,2,8);
        try {
            var ticket=await(io.beginWrite(key)).orElseThrow();
            await(io.invalidateRegion(0,0,16,16));
            assertFalse(await(io.write(ticket,tile())));
            assertTrue(await(io.read(key)).isEmpty());
            var next=await(io.beginWrite(key)).orElseThrow();
            assertTrue(await(io.write(next,tile())));
            var invalidation=io.invalidateRegion(0,0,16,16);
            var read=io.read(key);
            assertEquals(1,(int)await(invalidation)); assertTrue(await(read).isEmpty());
        } finally { await(io.closeAsync()); }
        try(var reopened=new SeedPreviewDiskCache(root,identity,2,2)) {
            assertTrue(reopened.read(new TerrainTileKey(2,0,0,0)).isEmpty());
        }
        var failure=assertThrows(ExecutionException.class,()->await(io.read(key)));
        assertTrue(failure.getCause() instanceof RejectedExecutionException);
    }
    @Test public void queueIsBoundedAndCloseDrainsAcceptedOperations() throws Exception {
        var io=new SeedPreviewIo(temporary.getRoot().toPath().resolve("bounded"),identity,1,2,1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var callback=io.submit(()->{
            entered.countDown();
            try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Test release timed out"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            return null;
        });
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var accepted=io.read(key);
            var rejected=assertThrows(ExecutionException.class,()->await(io.read(key)));
            assertTrue(rejected.getCause() instanceof RejectedExecutionException);
            var closed=io.closeAsync(); assertFalse(closed.toCompletableFuture().isDone());
            release.countDown(); await(callback); await(accepted); await(closed);
        } finally { release.countDown(); await(io.closeAsync()); }
    }
    @Test public void initializationFailureIsPropagatedWithoutReinitializingForeignDirectory() throws Exception {
        var root=temporary.getRoot().toPath(); Files.writeString(root.resolve("keep.txt"),"important");
        var io=new SeedPreviewIo(root,identity,1,2,2);
        assertThrows(ExecutionException.class,()->await(io.read(key)));
        assertThrows(ExecutionException.class,()->await(io.closeAsync()));
        assertEquals("important",Files.readString(root.resolve("keep.txt")));
    }
    @Test public void operationsRunOffCallerAndValidationFailureDoesNotLoseLaterWork() throws Exception {
        var caller=Thread.currentThread();
        var io=new SeedPreviewIo(temporary.getRoot().toPath().resolve("owner"),identity,1,2,4);
        try {
            assertNotSame(caller,await(io.submit(Thread::currentThread)));
            var invalid=io.read(new TerrainTileKey(2,0,0,0));
            var valid=io.read(key);
            var failure=assertThrows(ExecutionException.class,()->await(invalid));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertTrue(await(valid).isEmpty());
        } finally { await(io.closeAsync()); }
    }
}
