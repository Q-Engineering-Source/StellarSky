package stellarium.client.ring.dh;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import stellarium.world.ring.terrain.TerrainColumnState;

public class DistantHorizonsCoverageTransferTest {
    private final Object world = new Object();
    private final DistantHorizonsCoverageTransfer.Generation tree = new DistantHorizonsCoverageTransfer.Generation();
    private static DistantHorizonsColumnCoverage input(long pos) {
        return new DistantHorizonsColumnCoverage(pos, 0, 256, false,
                Collections.nCopies(4096, TerrainColumnState.REAL_AIR));
    }

    @Test public void copiesExactBuildToItsBufferAndRejectsWrongWorld() {
        var lease = new DistantHorizonsCoverageTransfer.Lease();
        var build = lease.begin(world, 12, new CompletableFuture<>(), tree);
        var coverage = input(12);
        build.capture(coverage, false);
        var buffer = new DistantHorizonsCoverageTransfer.Attachment();
        assertTrue(buffer.attach(build));
        assertSame(coverage, buffer.inputFor(world).orElseThrow().coverage());
        assertFalse(buffer.inputFor(world).orElseThrow().convertedNonEmpty());
        assertTrue(buffer.inputFor(new Object()).isEmpty());
    }

    @Test public void newerBuildDoesNotEraseAlreadyInstalledOldBuffer() {
        var lease = new DistantHorizonsCoverageTransfer.Lease();
        var old = lease.begin(world, 12, new CompletableFuture<>(), tree);
        old.capture(input(12), true);
        var oldBuffer = new DistantHorizonsCoverageTransfer.Attachment();
        assertTrue(oldBuffer.attach(old));
        var newer = lease.begin(world, 12, new CompletableFuture<>(), tree);
        newer.capture(input(12), true);
        assertTrue(oldBuffer.inputFor(world).isPresent());
        assertFalse(new DistantHorizonsCoverageTransfer.Attachment().attach(old));
        assertTrue(new DistantHorizonsCoverageTransfer.Attachment().attach(newer));
    }

    @Test public void sectionCloseRejectsLateUploadAndInvalidatesInstalledCoverage() {
        var lease = new DistantHorizonsCoverageTransfer.Lease();
        var build = lease.begin(world, 12, new CompletableFuture<>(), tree);
        build.capture(input(12), true);
        var buffer = new DistantHorizonsCoverageTransfer.Attachment();
        assertTrue(buffer.attach(build));
        lease.close();
        assertTrue(buffer.inputFor(world).isEmpty());
        assertFalse(new DistantHorizonsCoverageTransfer.Attachment().attach(build));
        var afterClose = lease.begin(world, 12, new CompletableFuture<>(), tree);
        assertFalse(afterClose.isCurrent());
    }

    @Test public void bufferCloseCannotBeUndoneByAnUploadCompletion() {
        var lease = new DistantHorizonsCoverageTransfer.Lease();
        var build = lease.begin(world, 12, new CompletableFuture<>(), tree);
        build.capture(input(12), true);
        var buffer = new DistantHorizonsCoverageTransfer.Attachment();
        buffer.close();
        assertFalse(buffer.attach(build));
        assertTrue(buffer.inputFor(world).isEmpty());
    }

    @Test public void cancellationAndMissingInputNeverAuthorizeCoverage() {
        var lease = new DistantHorizonsCoverageTransfer.Lease();
        var task = new CompletableFuture<Void>();
        var build = lease.begin(world, 12, task, tree);
        var buffer = new DistantHorizonsCoverageTransfer.Attachment();
        assertFalse(buffer.attach(build));
        build.capture(input(12), true);
        assertTrue(buffer.attach(build));
        task.cancel(false);
        assertTrue(buffer.inputFor(world).isEmpty());
        assertFalse(new DistantHorizonsCoverageTransfer.Attachment().attach(build));
    }

    @Test public void mismatchedOrRepeatedPrimaryCaptureFailsExplicitly() {
        var build = new DistantHorizonsCoverageTransfer.Lease().begin(world, 12, new CompletableFuture<>(), tree);
        assertThrows(IllegalArgumentException.class, () -> build.capture(input(13), true));
        build.capture(input(12), true);
        assertThrows(IllegalStateException.class, () -> build.capture(input(12), true));
    }

    @Test public void futureMetadataAttachmentHappensBeforeConsumerPublication() {
        var task = new CompletableFuture<Void>();
        var build = new DistantHorizonsCoverageTransfer.Lease().begin(world, 12, task, tree);
        build.capture(input(12), true);
        var upload = new CompletableFuture<DistantHorizonsCoverageTransfer.Attachment>();
        var tagged = upload.thenApply(buffer -> { buffer.attach(build); return buffer; });
        var publication = tagged.thenApply(buffer -> buffer.inputFor(world).orElseThrow());
        upload.complete(new DistantHorizonsCoverageTransfer.Attachment());
        assertSame(build, publication.join().build());
    }

    @Test public void treeReloadInvalidatesImmediatelyBeforeDeferredSectionCleanup() {
        var lease = new DistantHorizonsCoverageTransfer.Lease();
        var before = lease.begin(world, 12, new CompletableFuture<>(), tree);
        before.capture(input(12), true);
        var installed = new DistantHorizonsCoverageTransfer.Attachment();
        assertTrue(installed.attach(before));
        tree.refresh();
        assertTrue(installed.inputFor(world).isEmpty());
        assertFalse(new DistantHorizonsCoverageTransfer.Attachment().attach(before));
        var after = lease.begin(world, 12, new CompletableFuture<>(), tree);
        after.capture(input(12), true);
        assertTrue(new DistantHorizonsCoverageTransfer.Attachment().attach(after));
        tree.close();
        tree.refresh();
        assertFalse(after.isCurrent());
    }

    @Test public void failedTaskInvalidatesAnAlreadyAttachedRecord() {
        var task = new CompletableFuture<Void>();
        var build = new DistantHorizonsCoverageTransfer.Lease().begin(world, 12, task, tree);
        build.capture(input(12), true);
        var buffer = new DistantHorizonsCoverageTransfer.Attachment();
        assertTrue(buffer.attach(build));
        task.completeExceptionally(new IllegalStateException("injected upload failure"));
        assertTrue(buffer.inputFor(world).isEmpty());
    }
}
