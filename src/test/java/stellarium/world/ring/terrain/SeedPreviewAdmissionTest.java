package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static stellarium.world.ring.terrain.SeedPreviewAdmission.Coverage.*;

public class SeedPreviewAdmissionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private TerrainTileKey key(long epoch,int level,long x,long z) { return new TerrainTileKey(epoch,level,x,z); }
    private SeedTerrainTile tile(TerrainTileKey key) { return new SeedTerrainTile(key,Collections.nCopies(4096,SeedTerrainTile.Column.EMPTY)); }
    private SeedPreviewAdmission.Preview admit(SeedPreviewAdmission gate,TerrainTileKey key) {
        assertTrue(gate.coverage(gate.beginCoverage(key).orElseThrow(),NO_REAL_DATA));
        return gate.beginPreview(key).orElseThrow();
    }
    @Test public void absentAndUnknownCoverageNeverAdmitAndRealEvidenceRemainsSticky() {
        var gate=new SeedPreviewAdmission(1,2); var key=key(1,0,0,0);
        assertTrue(gate.beginPreview(key).isEmpty());
        gate.coverage(gate.beginCoverage(key).orElseThrow(),UNKNOWN);
        assertTrue(gate.beginPreview(key).isEmpty());
        gate.coverage(gate.beginCoverage(key).orElseThrow(),REAL_DATA);
        gate.coverage(gate.beginCoverage(key).orElseThrow(),NO_REAL_DATA);
        assertTrue(gate.beginPreview(key).isEmpty());
    }
    @Test public void realArrivalRevokesPendingQueryPendingPreviewAndPublishedParents() {
        var gate=new SeedPreviewAdmission(1,4);
        var child=key(1,0,-1,-1); var parent=key(1,1,-1,-1); var querying=key(1,2,-1,-1);
        var pending=admit(gate,child);
        var published=gate.publish(admit(gate,parent),tile(parent)).orElseThrow();
        var query=gate.beginCoverage(querying).orElseThrow();
        var neighbour=key(1,0,0,0); var unaffected=gate.publish(admit(gate,neighbour),tile(neighbour)).orElseThrow();
        assertEquals(3,gate.realRegion(-16,-16,0,0));
        assertFalse(gate.coverage(query,NO_REAL_DATA)); assertTrue(gate.publish(pending,tile(child)).isEmpty());
        assertFalse(gate.current(published)); assertTrue(gate.current(unaffected));
        assertTrue(gate.beginPreview(parent).isEmpty());
    }
    @Test public void newestTicketWinsAndConsumedTicketCannotPublishTwice() {
        var gate=new SeedPreviewAdmission(1,1); var key=key(1,0,0,0);
        var old=gate.beginCoverage(key).orElseThrow(); var query=gate.beginCoverage(key).orElseThrow();
        assertFalse(gate.coverage(old,NO_REAL_DATA)); assertTrue(gate.coverage(query,NO_REAL_DATA));
        var first=gate.beginPreview(key).orElseThrow(); var second=gate.beginPreview(key).orElseThrow();
        assertTrue(gate.publish(first,tile(key)).isEmpty());
        var publication=gate.publish(second,tile(key)).orElseThrow();
        assertTrue(gate.publish(second,tile(key)).isEmpty()); assertTrue(gate.current(publication));
        gate.beginCoverage(key); assertFalse(gate.current(publication));
    }
    @Test public void residencyCapacityReleaseAndWorldSwitchRequireFreshEvidence() {
        var gate=new SeedPreviewAdmission(1,1); var key=key(1,0,0,0);
        var old=admit(gate,key);
        assertTrue(gate.beginCoverage(key(1,0,1,0)).isEmpty());
        gate.release(key); assertTrue(gate.beginPreview(key).isEmpty());
        var next=admit(gate,key); assertTrue(gate.publish(old,tile(key)).isEmpty());
        gate.switchWorld(2); assertTrue(gate.publish(next,tile(key)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->gate.beginCoverage(key));
        assertTrue(gate.beginPreview(key(2,0,0,0)).isEmpty());
        gate.close(); assertThrows(IllegalStateException.class,()->gate.beginCoverage(key(2,0,0,0)));
    }
    @Test public void completedDiskReadCannotPublishAfterRealArrival() throws Exception {
        var identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID(),1);
        var io=new SeedPreviewIo(temporary.getRoot().toPath().resolve("tiles"),identity,1,1,8);
        var gate=new SeedPreviewAdmission(1,1); var key=key(1,0,0,0);
        try {
            var write=io.beginWrite(key).toCompletableFuture().get(10,TimeUnit.SECONDS).orElseThrow();
            assertTrue(io.write(write,tile(key)).toCompletableFuture().get(10,TimeUnit.SECONDS));
            var preview=admit(gate,key);
            var read=io.read(key).toCompletableFuture().get(10,TimeUnit.SECONDS).orElseThrow();
            // Completion has arrived, but its gameplay publication is still pending.
            gate.realRegion(0,0,16,16);
            var invalidated=io.invalidateRegion(0,0,16,16);
            assertTrue(gate.publish(preview,read).isEmpty());
            assertTrue(gate.beginPreview(key).isEmpty());
            assertEquals(1,(int)invalidated.toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertTrue(io.read(key).toCompletableFuture().get(10,TimeUnit.SECONDS).isEmpty());
        } finally { gate.close(); io.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS); }
    }
}
