package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PreviewIdentityAuthorityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path path(String name) { return temporary.getRoot().toPath().resolve(name); }
    private byte[] fingerprint(int value) { var bytes = new byte[32]; bytes[0] = (byte)value; return bytes; }
    @Test public void restartPreservesAllIdsAndChangedInputsRotateOnlyGeneration() throws Exception {
        UUID server = PreviewIdentityAuthority.persistentId(path("server"));
        var first = PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(1));
        assertEquals(server, PreviewIdentityAuthority.persistentId(path("server")));
        assertEquals(first, PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(1)));
        var changed = PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(2));
        assertEquals(first.worldId(), changed.worldId()); assertNotEquals(first.generationId(), changed.generationId());
        var version = PreviewIdentityAuthority.issue(path("world"), server, 0, 2, fingerprint(2));
        assertNotEquals(changed.generationId(), version.generationId());
        assertEquals(version, PreviewIdentityAuthority.issue(path("world"), server, 0, 2, fingerprint(2)));
    }
    @Test public void dimensionsWorldsAndInstallationsRemainSeparate() throws Exception {
        UUID server = PreviewIdentityAuthority.persistentId(path("server"));
        var first = PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(1));
        var otherDimension = PreviewIdentityAuthority.issue(path("world"), server, -1, 1, fingerprint(2));
        assertEquals(first.worldId(), otherDimension.worldId());
        assertNotEquals(first.generationId(), otherDimension.generationId());
        assertEquals(first, PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(1)));
        assertNotEquals(first.worldId(), PreviewIdentityAuthority.issue(path("world2"), server, 0, 1, fingerprint(1)).worldId());
        UUID otherServer = PreviewIdentityAuthority.persistentId(path("server2"));
        var moved = PreviewIdentityAuthority.issue(path("world"), otherServer, 0, 1, fingerprint(1));
        assertNotEquals(first, moved); assertEquals(first.generationId(), moved.generationId());
    }
    @Test public void corruptIdentityAndGenerationArePreservedAndRejected() throws Exception {
        UUID server = UUID.randomUUID();
        PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(1));
        var generation = path("world").resolve("generation-0.bin");
        byte[] damaged = Files.readAllBytes(generation); damaged[30] ^= 1; Files.write(generation, damaged);
        assertThrows(IOException.class, () -> PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(2)));
        assertArrayEquals(damaged, Files.readAllBytes(generation));
        var identity = path("world").resolve("identity.bin"); Files.write(identity, new byte[]{1});
        assertThrows(IOException.class, () -> PreviewIdentityAuthority.persistentId(path("world")));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(identity));
    }
    @Test public void foreignDimensionRecordIsNotOverwritten() throws Exception {
        UUID server = UUID.randomUUID();
        PreviewIdentityAuthority.issue(path("world"), server, 0, 1, fingerprint(1));
        var original = path("world").resolve("generation-0.bin");
        var foreign = path("world").resolve("generation-1.bin"); Files.copy(original, foreign);
        assertThrows(IOException.class, () -> PreviewIdentityAuthority.issue(path("world"), server, 1, 1, fingerprint(1)));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(foreign));
    }
    @Test public void concurrentOwnerCannotIssueAndInvalidInputDoesNotCreateDirectory() throws Exception {
        Path root = path("world"); Files.createDirectories(root);
        try (var channel = FileChannel.open(root.resolve("authority.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(OverlappingFileLockException.class, () -> PreviewIdentityAuthority.persistentId(root));
            assertFalse(Files.exists(root.resolve("identity.bin")));
        }
        assertThrows(IllegalArgumentException.class, () -> PreviewIdentityAuthority.issue(path("invalid"), UUID.randomUUID(), 0, 0, fingerprint(1)));
        assertFalse(Files.exists(path("invalid")));
    }
    @Test public void missingIdentityDoesNotReinitializeExistingWorldMetadata() throws Exception {
        PreviewIdentityAuthority.issue(path("world"), UUID.randomUUID(), 0, 1, fingerprint(1));
        Files.delete(path("world").resolve("identity.bin"));
        assertThrows(IOException.class, () -> PreviewIdentityAuthority.persistentId(path("world")));
        assertFalse(Files.exists(path("world").resolve("identity.bin")));
        assertTrue(Files.exists(path("world").resolve("generation-0.bin")));
    }
}
