package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RingworldGenerationPolicyStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void firstSaveIsReadableBeforeGeneratorFactoryRuns() throws Exception {
        Path root = temporary.newFolder().toPath();
        var called = new AtomicBoolean();
        String result = RingworldGenerationBootstrap.create(root, 0, false, true, settings -> {
            called.set(true);
            assertTrue(settings.enabled());
            assertTrue(Files.isRegularFile(RingworldGenerationPolicyStore.pathFor(root, 0)));
            return "generator";
        });
        assertEquals("generator", result);
        assertTrue(called.get());
        assertTrue(RingworldGenerationPolicyStore.loadOrCreate(root, 0, true, false).enabled());
    }

    @Test public void existingWorldMissingPolicyStaysDisabled() throws Exception {
        Path root = temporary.newFolder().toPath();
        assertFalse(RingworldGenerationPolicyStore.loadOrCreate(root, 0, true, true).enabled());
        assertFalse(RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true).enabled());
    }

    @Test public void dimensionsHaveIndependentFilesAndFrozenChoices() throws Exception {
        Path root = temporary.newFolder().toPath();
        assertTrue(RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true).enabled());
        assertFalse(RingworldGenerationPolicyStore.loadOrCreate(root, -1, true, true).enabled());
        assertNotEquals(RingworldGenerationPolicyStore.pathFor(root, 0),
                RingworldGenerationPolicyStore.pathFor(root, -1));
        assertTrue(RingworldGenerationPolicyStore.loadOrCreate(root, 0, true, false).enabled());
    }

    @Test public void corruptFileIsPreservedAndCannotAdmitGenerator() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path file = RingworldGenerationPolicyStore.pathFor(root, 0);
        Files.createDirectories(file.getParent());
        byte[] corrupt = {1, 2, 3};
        Files.write(file, corrupt);
        var called = new AtomicBoolean();
        assertThrows(IOException.class, () -> RingworldGenerationBootstrap.create(root, 0, false, true,
                settings -> { called.set(true); return settings; }));
        assertFalse(called.get());
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @Test public void unwritableDestinationDoesNotAdmitGenerator() throws Exception {
        Path root = temporary.newFolder().toPath();
        // A regular file blocks the parent directory on every supported OS, even as Administrator.
        Files.write(root.resolve("data"), new byte[] {7});
        var called = new AtomicBoolean();
        assertThrows(IOException.class, () -> RingworldGenerationBootstrap.create(root, 0, false, true,
                settings -> { called.set(true); return settings; }));
        assertFalse(called.get());
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(root.resolve("data")));
    }

    @Test public void validExistingPolicyIsNeverRewritten() throws Exception {
        Path root = temporary.newFolder().toPath();
        RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true);
        Path file = RingworldGenerationPolicyStore.pathFor(root, 0);
        byte[] before = Files.readAllBytes(file);
        var time = Files.getLastModifiedTime(file);
        RingworldGenerationPolicyStore.loadOrCreate(root, 0, true, false);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test public void wrongDimensionOrFutureSchemaIsNotAbsence() throws Exception {
        Path root = temporary.newFolder().toPath();
        RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true);
        Path source = RingworldGenerationPolicyStore.pathFor(root, 0);
        Path other = RingworldGenerationPolicyStore.pathFor(root, 1);
        Files.copy(source, other);
        assertThrows(IOException.class, () -> RingworldGenerationPolicyStore.loadOrCreate(root, 1, false, true));
        NBTTagCompound envelope;
        try (var input = Files.newInputStream(source)) {
            envelope = CompressedStreamTools.readCompressed(input);
        }
        envelope.getCompoundTag("policy").setInteger("schema", 42);
        try (var output = Files.newOutputStream(source)) {
            CompressedStreamTools.writeCompressed(envelope, output);
        }
        byte[] before = Files.readAllBytes(source);
        IOException error = assertThrows(IOException.class,
                () -> RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test public void oversizedRecordAndDirectoryAreRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path file = RingworldGenerationPolicyStore.pathFor(root, 0);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[65537]);
        assertThrows(IOException.class, () -> RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true));
        Files.createDirectory(RingworldGenerationPolicyStore.pathFor(root, 1));
        assertThrows(IOException.class, () -> RingworldGenerationPolicyStore.loadOrCreate(root, 1, false, true));
    }

    @Test public void concurrentRequestsCannotChooseDifferentPoliciesForOneDimension() throws Exception {
        Path root = temporary.newFolder().toPath();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> {
                start.await();
                return RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, true);
            });
            var second = workers.submit(() -> {
                start.await();
                return RingworldGenerationPolicyStore.loadOrCreate(root, 0, false, false);
            });
            start.countDown();
            var chosen = first.get(10, TimeUnit.SECONDS);
            assertEquals(chosen, second.get(10, TimeUnit.SECONDS));
            assertEquals(chosen, RingworldGenerationPolicyStore.loadOrCreate(root, 0, true, !chosen.enabled()));
        }
    }

    @Test public void factoryFailureIsPreservedAndCannotChangeFrozenPolicy() throws Exception {
        Path root = temporary.newFolder().toPath();
        var cause = new IllegalStateException("generator construction failed");
        var thrown = assertThrows(IllegalStateException.class,
                () -> RingworldGenerationBootstrap.create(root, 0, false, true, settings -> { throw cause; }));
        assertSame(cause, thrown);
        assertTrue(RingworldGenerationPolicyStore.loadOrCreate(root, 0, true, false).enabled());
    }

    @Test public void emptyInterruptedFirstWriteCannotBeTreatedAsMissing() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path file = RingworldGenerationPolicyStore.pathFor(root, 0);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
        var called = new AtomicBoolean();
        assertThrows(IOException.class, () -> RingworldGenerationBootstrap.create(root, 0, false, true,
                settings -> { called.set(true); return settings; }));
        assertFalse(called.get());
        assertEquals(0, Files.size(file));
    }
}
