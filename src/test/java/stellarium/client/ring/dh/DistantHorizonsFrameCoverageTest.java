package stellarium.client.ring.dh;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import stellarium.world.ring.terrain.TerrainColumnState;

public class DistantHorizonsFrameCoverageTest {
    private final Object world = new Object(), frame = new Object(), view = new Object();
    private DistantHorizonsCoverageTransfer.Attachment buffer(TerrainColumnState state) {
        var build = new DistantHorizonsCoverageTransfer.Lease().begin(world, 12,
                new CompletableFuture<>(), new DistantHorizonsCoverageTransfer.Generation());
        build.capture(new DistantHorizonsColumnCoverage(12, 0, 256, false,
                Collections.nCopies(4096, state)), state != TerrainColumnState.REAL_AIR);
        var attachment = new DistantHorizonsCoverageTransfer.Attachment();
        assertTrue(attachment.attach(build));
        return attachment;
    }
    @Test public void requiresBothPassesEveryBufferAndMainComposite() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var opaque = new Object(); var transparent = new Object();
        var token = ledger.select(buffer(TerrainColumnState.REAL_SOLID), List.of(opaque), List.of(transparent));
        ledger.submitted(token, true, opaque);
        ledger.finishPass(true);
        ledger.finishPass(false);
        assertTrue(ledger.completed(frame, view).isEmpty());
        ledger.composited(frame, view);
        assertTrue(ledger.completed(frame, view).isEmpty());
        // A skipped transparent buffer cannot be repaired after final composition.
        assertThrows(IllegalStateException.class, () -> ledger.submitted(token, false, transparent));
    }
    @Test public void completeSubmissionIsScopedToExactFrameAndView() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var vbo = new Object();
        var token = ledger.select(buffer(TerrainColumnState.REAL_SOLID), List.of(vbo), List.of());
        ledger.submitted(token, true, vbo);
        ledger.finishPass(true); ledger.finishPass(false);
        ledger.composited(frame, view);
        assertEquals(1, ledger.completed(frame, view).size());
        assertTrue(ledger.completed(new Object(), view).isEmpty());
        assertTrue(ledger.completed(frame, new Object()).isEmpty());
    }
    @Test public void zeroGeometryRequiresExplicitCompleteAir() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        ledger.select(buffer(TerrainColumnState.REAL_AIR), List.of(), List.of());
        ledger.select(buffer(TerrainColumnState.UNKNOWN), List.of(), List.of());
        ledger.select(buffer(TerrainColumnState.REAL_SOLID), List.of(), List.of());
        ledger.finishPass(true); ledger.finishPass(false); ledger.composited(frame, view);
        assertEquals(1, ledger.completed(frame, view).size());
    }
    @Test public void closeAfterCompositionInvalidatesCoverage() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var attachment = buffer(TerrainColumnState.REAL_AIR);
        ledger.select(attachment, List.of(), List.of());
        ledger.finishPass(true); ledger.finishPass(false); ledger.composited(frame, view);
        attachment.close();
        assertTrue(ledger.completed(frame, view).isEmpty());
    }
    @Test public void rejectsForeignTokensAndUnselectedVertexBuffers() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var other = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var vbo = new Object();
        var token = ledger.select(buffer(TerrainColumnState.REAL_SOLID), List.of(vbo), List.of());
        assertThrows(IllegalArgumentException.class, () -> other.submitted(token, true, vbo));
        assertThrows(IllegalArgumentException.class, () -> ledger.submitted(token, true, new Object()));
    }
    @Test public void boundedLedgerFailsExplicitlyAndAbortClearsResults() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 1);
        ledger.select(buffer(TerrainColumnState.REAL_AIR), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> ledger.select(buffer(TerrainColumnState.REAL_AIR), List.of(), List.of()));
        ledger.abort();
        assertTrue(ledger.completed(frame, view).isEmpty());
    }
    @Test public void wrongCompositePermanentlyRejectsThisView() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        ledger.select(buffer(TerrainColumnState.REAL_AIR), List.of(), List.of());
        ledger.finishPass(true); ledger.finishPass(false);
        ledger.composited(frame, new Object());
        assertTrue(ledger.completed(frame, view).isEmpty());
        assertThrows(IllegalStateException.class, () -> ledger.composited(frame, view));
    }
    @Test public void missingPassCannotBeFilledAfterComposition() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        ledger.select(buffer(TerrainColumnState.REAL_AIR), List.of(), List.of());
        ledger.finishPass(true); ledger.composited(frame, view);
        assertTrue(ledger.completed(frame, view).isEmpty());
        assertThrows(IllegalStateException.class, () -> ledger.finishPass(false));
    }
    @Test public void otherThreadCannotMutateRenderFrame() {
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, ledger::abort)).join();
        ledger.select(buffer(TerrainColumnState.REAL_AIR), List.of(), List.of());
    }
}
