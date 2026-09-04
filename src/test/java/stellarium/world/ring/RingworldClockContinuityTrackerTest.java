package stellarium.world.ring;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldClockContinuityTrackerTest {
    @Test
    public void verifiedNormalPlusMinusAndZeroCadenceAreContinuousAfterFirstPublication() {
        assertVerifiedCadence(40L, 41L);
        assertVerifiedCadence(40L, 39L);
        assertVerifiedCadence(40L, 40L);
    }

    private static void assertVerifiedCadence(long beforeTime, long targetTime) {
        RingworldClockContinuityTracker tracker = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation first =
                new RingworldClockContinuityTracker.Observation(beforeTime, 0L, false);
        RingworldClockContinuityTracker.Publication initial = tracker.previewPublication(first);
        assertTrue(initial.discontinuousBefore());
        tracker.commitPublication(initial);

        RingworldClockContinuityTracker.KnownWrite write = tracker.beginKnownWrite(first);
        tracker.finishKnownWrite(write, targetTime,
                new RingworldClockContinuityTracker.Observation(targetTime, 1L, false), false);
        RingworldClockContinuityTracker.Publication next = tracker.previewPublication(
                new RingworldClockContinuityTracker.Observation(targetTime, 1L, false));
        assertFalse(next.discontinuousBefore());
        tracker.commitPublication(next);
    }

    @Test
    public void discontinuityIsConsumedOnlyAfterPublishingAndThenNormalCadenceRecovers() {
        RingworldClockContinuityTracker tracker = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation first =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        tracker.commitPublication(tracker.previewPublication(first));

        RingworldClockContinuityTracker.Observation unknown =
                new RingworldClockContinuityTracker.Observation(900L, 1L, false);
        RingworldClockContinuityTracker.KnownWrite normal = tracker.beginKnownWrite(unknown);
        tracker.finishKnownWrite(normal, 901L,
                new RingworldClockContinuityTracker.Observation(901L, 2L, false), false);
        RingworldClockContinuityTracker.Publication broken = tracker.previewPublication(
                new RingworldClockContinuityTracker.Observation(901L, 2L, false));
        assertTrue(broken.discontinuousBefore());
        tracker.commitPublication(broken);

        RingworldClockContinuityTracker.KnownWrite recovered = tracker.beginKnownWrite(broken.observation());
        tracker.finishKnownWrite(recovered, 902L,
                new RingworldClockContinuityTracker.Observation(902L, 3L, false), false);
        assertFalse(tracker.previewPublication(
                new RingworldClockContinuityTracker.Observation(902L, 3L, false)).discontinuousBefore());
    }

    @Test
    public void multipleVerifiedWritesBeforePublicationFormOneContinuousChain() {
        RingworldClockContinuityTracker tracker = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation first =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        tracker.commitPublication(tracker.previewPublication(first));

        RingworldClockContinuityTracker.KnownWrite firstWrite = tracker.beginKnownWrite(first);
        RingworldClockContinuityTracker.Observation second =
                new RingworldClockContinuityTracker.Observation(41L, 1L, false);
        tracker.finishKnownWrite(firstWrite, 41L, second, false);
        RingworldClockContinuityTracker.KnownWrite secondWrite = tracker.beginKnownWrite(second);
        RingworldClockContinuityTracker.Observation third =
                new RingworldClockContinuityTracker.Observation(42L, 2L, false);
        tracker.finishKnownWrite(secondWrite, 42L, third, false);

        assertFalse(tracker.previewPublication(third).discontinuousBefore());
    }

    @Test
    public void unprovenFrameWindowCannotBeConsumedAsAContinuousBaseline() {
        RingworldClockContinuityTracker tracker = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation first =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        tracker.commitPublication(tracker.previewPublication(first));

        RingworldClockContinuityTracker.Observation observedOtherEndpoint =
                new RingworldClockContinuityTracker.Observation(41L, 1L, false);
        RingworldClockContinuityTracker.Publication mismatchedFrame = tracker.previewPublication(observedOtherEndpoint);
        tracker.markUnproven();
        tracker.commitPublication(mismatchedFrame);

        assertTrue(tracker.previewPublication(observedOtherEndpoint).discontinuousBefore());
    }

    @Test
    public void foreignWriteCannotProveAnUnknownJumpOrConsumeTheActiveScope() {
        RingworldClockContinuityTracker tracker = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation first =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        tracker.commitPublication(tracker.previewPublication(first));

        RingworldClockContinuityTracker.KnownWrite legitimate = tracker.beginKnownWrite(first);
        RingworldClockContinuityTracker foreign = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation unknown =
                new RingworldClockContinuityTracker.Observation(900L, 1L, false);
        foreign.commitPublication(foreign.previewPublication(unknown));
        RingworldClockContinuityTracker.KnownWrite forged = foreign.beginKnownWrite(unknown);
        RingworldClockContinuityTracker.Observation after =
                new RingworldClockContinuityTracker.Observation(901L, 2L, false);
        tracker.finishKnownWrite(forged, 901L, after, false);
        // Assert before another invalid operation can mask a falsely trusted span.
        assertTrue(tracker.previewPublication(after).discontinuousBefore());
        tracker.finishKnownWrite(legitimate, 901L, after, false);

        assertTrue(tracker.previewPublication(after).discontinuousBefore());
    }

    @Test
    public void foreignAndReusedWriteTokensFailClosed() {
        RingworldClockContinuityTracker tracker = initializedTracker();
        RingworldClockContinuityTracker foreign = initializedTracker();
        RingworldClockContinuityTracker.Observation before =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        RingworldClockContinuityTracker.Observation after =
                new RingworldClockContinuityTracker.Observation(41L, 1L, false);

        RingworldClockContinuityTracker.KnownWrite local = tracker.beginKnownWrite(before);
        RingworldClockContinuityTracker.KnownWrite foreignToken = foreign.beginKnownWrite(before);
        tracker.finishKnownWrite(foreignToken, 41L, after, false);
        tracker.finishKnownWrite(local, 41L, after, false);
        tracker.finishKnownWrite(local, 41L, after, false);

        assertTrue(tracker.previewPublication(after).discontinuousBefore());
    }

    @Test
    public void rejectedCommitInvalidatesAPreviouslyContinuousPreview() {
        RingworldClockContinuityTracker tracker = initializedTracker();
        RingworldClockContinuityTracker.Observation same =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        RingworldClockContinuityTracker.Publication preview = tracker.previewPublication(same);
        assertFalse(preview.discontinuousBefore());

        tracker.commitPublication(null);
        tracker.commitPublication(preview);

        RingworldClockContinuityTracker.Publication broken = tracker.previewPublication(same);
        assertTrue(broken.discontinuousBefore());
        tracker.commitPublication(broken);
        assertFalse(tracker.previewPublication(same).discontinuousBefore());
    }

    @Test
    public void staleOrReusedPublicationCannotCommitTheCurrentWindow() {
        RingworldClockContinuityTracker tracker = initializedTracker();
        RingworldClockContinuityTracker.Observation same =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        RingworldClockContinuityTracker.Publication stale = tracker.previewPublication(same);
        RingworldClockContinuityTracker.Publication current = tracker.previewPublication(same);
        tracker.commitPublication(stale);
        tracker.commitPublication(current);
        assertTrue(tracker.previewPublication(same).discontinuousBefore());
    }

    @Test
    public void replayedPublicationCannotEraseTheBreakBeforeItsReplacementCommits() {
        RingworldClockContinuityTracker tracker = initializedTracker();
        RingworldClockContinuityTracker.Observation same =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        RingworldClockContinuityTracker.Publication consumed = tracker.previewPublication(same);
        tracker.commitPublication(consumed);
        RingworldClockContinuityTracker.Publication replacement = tracker.previewPublication(same);
        assertFalse(replacement.discontinuousBefore());
        tracker.commitPublication(consumed);
        tracker.commitPublication(replacement);
        assertTrue(tracker.previewPublication(same).discontinuousBefore());
    }

    @Test
    public void failedNestedOrUnobservedWritesCannotAssertContinuity() {
        for (int failure = 0; failure < 5; failure++) {
            RingworldClockContinuityTracker tracker = initializedTracker();
            RingworldClockContinuityTracker.Observation before =
                    new RingworldClockContinuityTracker.Observation(40L, 0L, false);
            RingworldClockContinuityTracker.KnownWrite write = tracker.beginKnownWrite(before);
            RingworldClockContinuityTracker.Observation after =
                    new RingworldClockContinuityTracker.Observation(41L,
                            failure == 1 ? 2L : 1L, failure == 2);
            if (failure == 0) {
                tracker.abortKnownWrite(write);
            } else {
                if (failure == 3) {
                    tracker.abortKnownWrite(tracker.beginKnownWrite(before));
                }
                tracker.finishKnownWrite(write, 41L, after, failure == 4);
            }
            RingworldClockContinuityTracker.Publication broken = tracker.previewPublication(after);
            assertTrue("failure case " + failure, broken.discontinuousBefore());
            tracker.commitPublication(broken);
            RingworldClockContinuityTracker.KnownWrite next = tracker.beginKnownWrite(after);
            RingworldClockContinuityTracker.Observation recovered =
                    new RingworldClockContinuityTracker.Observation(42L, after.revision() + 1L, after.saturated());
            tracker.finishKnownWrite(next, 42L, recovered, false);
            // Saturated provenance remains unavailable; other failed scopes can recover.
            if (failure == 2) assertTrue(tracker.previewPublication(recovered).discontinuousBefore());
            else assertFalse(tracker.previewPublication(recovered).discontinuousBefore());
        }
    }

    private static RingworldClockContinuityTracker initializedTracker() {
        RingworldClockContinuityTracker tracker = new RingworldClockContinuityTracker();
        RingworldClockContinuityTracker.Observation first =
                new RingworldClockContinuityTracker.Observation(40L, 0L, false);
        tracker.commitPublication(tracker.previewPublication(first));
        return tracker;
    }
}
