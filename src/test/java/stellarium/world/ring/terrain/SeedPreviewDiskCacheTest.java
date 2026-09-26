package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SeedPreviewDiskCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final PreviewCacheIdentity identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID(),1);
    private static TerrainTileKey key(long epoch,int level,long x,long z){return new TerrainTileKey(epoch,level,x,z);}
    private static SeedTerrainTile tile(TerrainTileKey key){return new SeedTerrainTile(key,Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.OCEAN,40,63)));}
    private Path root(){return temporary.getRoot().toPath().resolve("preview");}

    @Test public void restartRebindsSessionEpochWithoutChangingPersistentIdentity() throws Exception {
        var first=key(1,0,-1,2);
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,2)) {
            assertTrue(cache.write(cache.beginWrite(first).orElseThrow(),tile(first)));
            assertEquals(63,cache.read(first).orElseThrow().columns().getFirst().visibleTop());
        }
        try(var cache=new SeedPreviewDiskCache(root(),identity,2,2)) {
            var next=key(2,0,-1,2);
            assertEquals(next,cache.read(next).orElseThrow().key());
            assertThrows(IllegalArgumentException.class,()->cache.read(first));
        }
    }
    @Test public void realRegionInvalidatesChildrenParentsAndLateWritesButNotNeighbours() throws Exception {
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,4)) {
            var child=key(1,0,-1,-1); var parent=key(1,1,-1,-1); var other=key(1,0,0,0);
            for(var k:new TerrainTileKey[]{child,parent,other})cache.write(cache.beginWrite(k).orElseThrow(),tile(k));
            var stale=cache.beginWrite(child).orElseThrow();
            assertEquals(2,cache.invalidateRegion(-16,-16,0,0));
            assertFalse(cache.write(stale,tile(child)));
            assertTrue(cache.read(child).isEmpty()); assertTrue(cache.read(parent).isEmpty()); assertTrue(cache.read(other).isPresent());
        }
        try(var cache=new SeedPreviewDiskCache(root(),identity,2,4)) {
            assertTrue(cache.read(key(2,1,-1,-1)).isEmpty());
            assertTrue(cache.read(key(2,0,0,0)).isPresent());
        }
    }
    @Test public void budgetIncludesPendingNewTilesAndNewTicketSupersedesOld() throws Exception {
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,1)) {
            var k=key(1,0,0,0); var old=cache.beginWrite(k).orElseThrow();
            assertTrue(cache.beginWrite(key(1,0,1,0)).isEmpty());
            var latest=cache.beginWrite(k).orElseThrow();
            assertFalse(cache.write(old,tile(k))); assertTrue(cache.write(latest,tile(k)));
            assertTrue(cache.beginWrite(key(1,0,1,0)).isPresent());
            assertTrue(cache.read(k).isEmpty());
        }
    }
    @Test public void fullCacheEvictsLeastRecentlyReadTileAndPersistsTheReplacement() throws Exception {
        var a=key(1,0,0,0);var b=key(1,0,1,0);var c=key(1,0,2,0);
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,2)) {
            for(var k:new TerrainTileKey[]{a,b})cache.write(cache.beginWrite(k).orElseThrow(),tile(k));
            assertTrue(cache.read(a).isPresent());
            assertTrue(cache.write(cache.beginWrite(c).orElseThrow(),tile(c)));
            assertTrue(cache.read(b).isEmpty());assertTrue(cache.read(a).isPresent());
            try(var files=Files.list(root())){assertEquals(2,files.filter(p->p.getFileName().toString().startsWith("tile-")).count());}
        }
        try(var cache=new SeedPreviewDiskCache(root(),identity,2,2)) {
            assertTrue(cache.read(key(2,0,0,0)).isPresent());assertTrue(cache.read(key(2,0,1,0)).isEmpty());
            assertTrue(cache.read(key(2,0,2,0)).isPresent());
        }
    }
    @Test public void evictionNeverStealsAnInFlightReservation() throws Exception {
        var a=key(1,0,0,0);var b=key(1,0,1,0);var c=key(1,0,2,0);
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,2)) {
            for(var k:new TerrainTileKey[]{a,b})cache.write(cache.beginWrite(k).orElseThrow(),tile(k));
            var updatingA=cache.beginWrite(a).orElseThrow();var updatingB=cache.beginWrite(b).orElseThrow();
            assertTrue(cache.beginWrite(c).isEmpty());
            cache.cancelWrite(updatingB);
            assertTrue(cache.write(cache.beginWrite(c).orElseThrow(),tile(c)));
            assertTrue(cache.write(updatingA,tile(a)));assertTrue(cache.read(b).isEmpty());
        }
    }
    @Test public void wrongNamespaceCorruptionAndConcurrentOwnerFailExplicitly() throws Exception {
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,2)) {
            assertThrows(IOException.class,()->new SeedPreviewDiskCache(root(),identity,1,2));
            var k=key(1,0,0,0);cache.write(cache.beginWrite(k).orElseThrow(),tile(k));
            try(var files=Files.list(root())) {
                var file=files.filter(p->p.getFileName().toString().startsWith("tile-")).findFirst().orElseThrow();
                var bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
            }
            assertThrows(IOException.class,()->cache.read(k));
        }
        var changed=new PreviewCacheIdentity(identity.serverId(),identity.worldId(),0,UUID.randomUUID(),1);
        assertThrows(IOException.class,()->new SeedPreviewDiskCache(root(),changed,2,2));
    }
    @Test public void unrelatedDirectoryIsNeverAdoptedOrCleaned() throws Exception {
        Files.createDirectories(root());var unrelated=root().resolve("notes.txt");Files.writeString(unrelated,"keep");
        assertThrows(IOException.class,()->new SeedPreviewDiskCache(root(),identity,1,2));
        assertEquals("keep",Files.readString(unrelated));
    }
    @Test public void codecRejectsForeignIdentityAndTruncationButOmitsSessionEpoch() throws Exception {
        var bytes=SeedPreviewCodec.encode(identity,tile(key(1,0,0,0)));
        assertArrayEquals(bytes,SeedPreviewCodec.encode(identity,tile(key(2,0,0,0))));
        assertEquals(key(5,0,0,0),SeedPreviewCodec.decode(identity,5,bytes).key());
        assertThrows(IOException.class,()->SeedPreviewCodec.decode(identity,1,new byte[5]));
        var foreign=new PreviewCacheIdentity(UUID.randomUUID(),identity.worldId(),0,identity.generationId(),1);
        assertThrows(IOException.class,()->SeedPreviewCodec.decode(foreign,1,bytes));
    }
    @Test public void failedIndexCommitDoesNotPublishOrphanPayloadOnRestart() throws Exception {
        var good=key(1,0,0,0);var pending=key(1,0,1,0);byte[] savedIndex;
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,2)) {
            cache.write(cache.beginWrite(good).orElseThrow(),tile(good));
            var ticket=cache.beginWrite(pending).orElseThrow();
            var index=root().resolve("index.bin"); savedIndex=Files.readAllBytes(index);
            Files.delete(index); Files.createDirectory(index);
            assertThrows(IOException.class,()->cache.write(ticket,tile(pending)));
            assertThrows(IllegalStateException.class,()->cache.read(good));
        }
        // Restore the old committed index, modelling a failed replacement rather than a successful commit.
        Files.delete(root().resolve("index.bin")); Files.write(root().resolve("index.bin"),savedIndex);
        try(var cache=new SeedPreviewDiskCache(root(),identity,2,2)) {
            assertTrue(cache.read(key(2,0,0,0)).isPresent());assertTrue(cache.read(key(2,0,1,0)).isEmpty());
            try(var files=Files.list(root())){assertEquals(1,files.filter(p->p.getFileName().toString().startsWith("tile-")).count());}
        }
    }
    @Test public void committedInvalidationIgnoresPayloadLeftBeforeCleanup() throws Exception {
        var k=key(1,0,0,0);Path payload;byte[] old;
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,2)) {
            cache.write(cache.beginWrite(k).orElseThrow(),tile(k));
            try(var files=Files.list(root())){payload=files.filter(p->p.getFileName().toString().startsWith("tile-")).findFirst().orElseThrow();}
            old=Files.readAllBytes(payload);
            cache.invalidateRegion(0,0,16,16);
            Files.write(payload,old); // Replay the on-disk state after index commit but before payload deletion.
        }
        try(var cache=new SeedPreviewDiskCache(root(),identity,2,2)) {
            assertTrue(cache.read(key(2,0,0,0)).isEmpty()); assertFalse(Files.exists(payload));
        }
    }
    @Test public void invalidationCancelsAnUnwrittenReservationAndReleasesBudget() throws Exception {
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,1)) {
            var k=key(1,0,0,0);var ticket=cache.beginWrite(k).orElseThrow();
            assertEquals(0,cache.invalidateRegion(0,0,16,16));
            assertFalse(cache.write(ticket,tile(k)));
            var next=cache.beginWrite(key(1,0,1,0)).orElseThrow();
            assertTrue(cache.cancelWrite(next));
            assertTrue(cache.beginWrite(k).isPresent());
        }
    }
    @Test public void corruptIndexIsPreservedInsteadOfReinitialized() throws Exception {
        try(var cache=new SeedPreviewDiskCache(root(),identity,1,1)) {}
        var bytes=new byte[]{1,2,3}; Files.write(root().resolve("index.bin"),bytes);
        assertThrows(IOException.class,()->new SeedPreviewDiskCache(root(),identity,2,1));
        assertArrayEquals(bytes,Files.readAllBytes(root().resolve("index.bin")));
    }
}
