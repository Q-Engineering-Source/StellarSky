package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The diagnostics state machine, exercised without any GL context.
 *
 * <p>These assertions cover request ownership, the per-submission slots and the settle
 * handshake only. They never open a query, never read GL state and therefore say nothing
 * about GPU behaviour, sample counts or the picture on screen.</p>
 */
public class RingworldDrawDiagnosticsStateTest {
    @Before
    public void resetState() {
        // GL-free reset: these tests never own a query, and the reset asserts that before it
        // drops the records, so no GL entry point is reached from the suite.
        RingworldDrawDiagnostics.resetWithoutQueries();
    }

    @Test
    public void busyRequestIsRefusedWithoutResettingTheInflightRequest() {
        String first = RingworldDrawDiagnostics.request(null);
        assertTrue(first, first.startsWith("queued #"));
        RingworldDrawDiagnostics.frameBegun();
        int slot = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.HIGH);
        assertTrue("the early high submission must get its own slot", slot >= 0);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.HIGH, 3L, 900L);
        String inFlight = RingworldDrawDiagnostics.statusLine();

        String refused = RingworldDrawDiagnostics.request(null);
        assertTrue(refused, refused.startsWith("busy: request="));
        assertEquals("a refused command must not change the state",
                RingworldDrawDiagnostics.State.SAMPLING, RingworldDrawDiagnostics.state());
        assertEquals("a refused command must not restart the request window",
                inFlight, RingworldDrawDiagnostics.statusLine());
        assertTrue("the in-flight record must survive the refusal",
                RingworldDrawDiagnostics.reportLines().stream()
                        .anyMatch(line -> line.contains("phase=EARLY stage=HIGH draws=3 verts=900")));
    }

    @Test
    public void stageSlotsKeepEarlyAndLateSubmissionsSeparate() {
        RingworldDrawDiagnostics.request(null);
        RingworldDrawDiagnostics.frameBegun();
        int earlyHigh = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.HIGH);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.HIGH, 1L, 100L);
        int earlyLow = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.LOW);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.LOW, 1L, 100L);

        RingworldDrawDiagnostics.phaseLate();
        int lateHigh = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.HIGH);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.HIGH, 2L, 250L);
        int lateColor = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.COLOR);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.COLOR, 4L, 6400L);

        assertNotEquals("a late high must not reuse the early high slot", earlyHigh, lateHigh);
        assertNotEquals("a late high must not reuse the early low slot", earlyLow, lateHigh);
        assertNotEquals("the colour stage is its own slot", lateHigh, lateColor);
        var lines = RingworldDrawDiagnostics.reportLines();
        assertTrue(lines.toString(),
                lines.stream().anyMatch(line -> line.contains("phase=EARLY stage=HIGH draws=1 verts=100")));
        assertTrue(lines.toString(),
                lines.stream().anyMatch(line -> line.contains("phase=LATE stage=HIGH draws=2 verts=250")));
        assertTrue(lines.toString(),
                lines.stream().anyMatch(line -> line.contains("phase=LATE stage=COLOR draws=4 verts=6400")));
        assertTrue("every slot is reported as its own submission",
                lines.stream().filter(line -> line.startsWith("SS DRAW DIAG submit[")).count() == 4L);
    }

    @Test
    public void frameFinishedWaitsForEverySlotBeforeReportingReadiness() {
        RingworldDrawDiagnostics.request(null);
        RingworldDrawDiagnostics.frameBegun();
        int high = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.HIGH);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.HIGH, 1L, 60L);
        RingworldDrawDiagnostics.frameFinished();
        assertEquals(RingworldDrawDiagnostics.State.AWAITING_RESULTS, RingworldDrawDiagnostics.state());
        assertFalse("a pending slot keeps the request open", RingworldDrawDiagnostics.readyToReport());

        RingworldDrawDiagnostics.recordResult(high, 4_096L);
        assertTrue("every slot terminal is what makes the request readable",
                RingworldDrawDiagnostics.readyToReport());
        var done = RingworldDrawDiagnostics.complete(2_000_000L);
        assertTrue(done.toString(), done.stream().anyMatch(line -> line.contains("samplesPassed=4096")));
        assertTrue(done.toString(), done.stream().anyMatch(line -> line.contains("elapsedMs=2")));
        assertEquals(RingworldDrawDiagnostics.State.COMPLETED, RingworldDrawDiagnostics.state());
    }

    @Test
    public void skippedAndTimedOutSlotsAreReportedInsteadOfWaiting() {
        // A target another subsystem already owns is recorded as terminal (skipped) at once.
        RingworldDrawDiagnostics.request(null);
        RingworldDrawDiagnostics.frameBegun();
        int low = RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.LOW);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.LOW, 1L, 20L);
        RingworldDrawDiagnostics.skipSlot(low, "queryTargetBusy");
        RingworldDrawDiagnostics.frameFinished();
        assertTrue("a skipped slot never waits for a driver result",
                RingworldDrawDiagnostics.readyToReport());
        assertTrue(RingworldDrawDiagnostics.reportLines().stream()
                .anyMatch(line -> line.contains("stage=LOW") && line.contains("samplesPassed=SKIPPED")));

        // An armed request is reported and stays bounded: a pass that never consumes it is
        // refused a second command (covered above) and expires on its own deadline, so the
        // operator can always see the state instead of an invisible pending request.
        RingworldDrawDiagnostics.clear();
        RingworldDrawDiagnostics.request(null);
        assertEquals(RingworldDrawDiagnostics.State.ARMED, RingworldDrawDiagnostics.state());
        RingworldDrawDiagnostics.tick();
        assertTrue("the armed window must stay visible in status",
                RingworldDrawDiagnostics.statusLine().contains("ARMED"));
        assertTrue("an armed request is still subject to its deadline",
                RingworldDrawDiagnostics.statusLine().contains("request=#"));
        assertTrue("a second command while armed is refused, not queued",
                RingworldDrawDiagnostics.request(null).startsWith("busy: request="));

        // And the result window itself is bounded.
        RingworldDrawDiagnostics.clear();
        RingworldDrawDiagnostics.request(null);
        RingworldDrawDiagnostics.frameBegun();
        RingworldDrawDiagnostics.reserveSlot(RingworldDrawDiagnostics.COLOR);
        RingworldDrawDiagnostics.endStage(RingworldDrawDiagnostics.COLOR, 1L, 10L);
        RingworldDrawDiagnostics.frameFinished();
        assertFalse(RingworldDrawDiagnostics.readyToReport());
        boolean expired = false;
        for (int frame = 0; frame <= RingworldDrawDiagnostics.MAX_POLL_FRAMES + 1; frame++) {
            if (RingworldDrawDiagnostics.pollTimedOut()) { expired = true; break; }
        }
        assertTrue("the poll window must end even if the driver never reports", expired);
    }
}
