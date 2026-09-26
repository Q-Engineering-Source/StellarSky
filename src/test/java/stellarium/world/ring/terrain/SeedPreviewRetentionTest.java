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

public class SeedPreviewRetentionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final UUID server=UUID.randomUUID(),world=UUID.randomUUID();
    private PreviewCacheIdentity identity(){return new PreviewCacheIdentity(server,world,0,UUID.randomUUID(),1);}
    private Path root(){return temporary.getRoot().toPath().resolve("dim-0");}
    private Path path(PreviewCacheIdentity id){return root().resolve(id.generationId().toString());}
    private TerrainTileKey key(){return new TerrainTileKey(1,0,0,0);}
    private void populate(PreviewCacheIdentity id) throws Exception {
        try(var cache=new SeedPreviewDiskCache(path(id),id,1,2)) {
            cache.write(cache.beginWrite(key()).orElseThrow(),new SeedTerrainTile(key(),Collections.nCopies(4096,SeedTerrainTile.Column.EMPTY)));
        }
    }
    @Test public void obsoletePayloadsRetireButCurrentDataAndOwnershipStubsRemain() throws Exception {
        var old=identity();var current=identity();populate(old);populate(current);
        SeedPreviewRetention.prepare(root(),current);
        try(var cache=new SeedPreviewDiskCache(path(old),old,1,2)){assertTrue(cache.read(key()).isEmpty());}
        try(var cache=new SeedPreviewDiskCache(path(current),current,1,2)){assertTrue(cache.read(key()).isPresent());}
        try(var files=Files.list(path(old))){assertEquals(2,files.count());}
        SeedPreviewRetention.prepare(root(),current);
        assertTrue(Files.exists(path(old).resolve("owner.lock")));
    }
    @Test public void foreignWorldIsRejectedBeforeAnyNamespaceIsRetired() throws Exception {
        var old=identity();populate(old);
        var foreign=new PreviewCacheIdentity(server,UUID.randomUUID(),0,UUID.randomUUID(),1);populate(foreign);
        assertThrows(IOException.class,()->SeedPreviewRetention.prepare(root(),identity()));
        try(var cache=new SeedPreviewDiskCache(path(old),old,1,2)){assertTrue(cache.read(key()).isPresent());}
        try(var cache=new SeedPreviewDiskCache(path(foreign),foreign,1,2)){assertTrue(cache.read(key()).isPresent());}
    }
    @Test public void activeOldNamespaceCannotBeRetired() throws Exception {
        var old=identity();populate(old);
        try(var cache=new SeedPreviewDiskCache(path(old),old,1,2)) {
            assertThrows(IOException.class,()->SeedPreviewRetention.prepare(root(),identity()));
            assertTrue(cache.read(key()).isPresent());
        }
    }
    @Test public void unrelatedFilesArePreservedAndPreventCleanup() throws Exception {
        var old=identity();populate(old);
        Files.writeString(root().resolve("notes.txt"),"keep");
        assertThrows(IOException.class,()->SeedPreviewRetention.prepare(root(),identity()));
        assertEquals("keep",Files.readString(root().resolve("notes.txt")));
        try(var cache=new SeedPreviewDiskCache(path(old),old,1,2)){assertTrue(cache.read(key()).isPresent());}
    }
    @Test public void interruptedCurrentInitializationCanResumeOnlyWhenEmpty() throws Exception {
        var current=identity();Files.createDirectories(path(current));Files.write(path(current).resolve("owner.lock"),new byte[0]);
        SeedPreviewRetention.prepare(root(),current);
        try(var cache=new SeedPreviewDiskCache(path(current),current,1,2)){assertTrue(cache.read(key()).isEmpty());}
        Files.delete(path(current).resolve("index.bin"));Files.writeString(path(current).resolve("notes.txt"),"keep");
        assertThrows(IOException.class,()->SeedPreviewRetention.prepare(root(),current));
        assertEquals("keep",Files.readString(path(current).resolve("notes.txt")));
    }
}
