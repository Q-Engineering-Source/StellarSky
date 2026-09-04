package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class RingworldClockMirrorTest {
    private static final UUID GENERATION = UUID.fromString("2f4ce7e4-6192-4c7c-b3cb-b6da6399b0a9");

    @Test
    public void laterDiscontinuitySnapsEvenWhenTheTimeChangeIsOnlyOneTick() {
        assertDisplayPair(100L, 101L, true, true);
    }

    @Test
    public void anUnrepresentableWorldTimeSpanSnapsInsteadOfWrapping() {
        assertDisplayPair(Long.MIN_VALUE, Long.MAX_VALUE, false, true);
        assertDisplayPair(Long.MAX_VALUE, Long.MIN_VALUE, false, true);
    }

    @Test
    public void verifiedReverseAndPausedIntervalsUseOnlyTheirAcceptedEndpoints() {
        assertDisplayPair(100L, 99L, false, false);
        assertDisplayPair(100L, 100L, false, false);
    }

    private static void assertDisplayPair(long from, long to, boolean discontinuous, boolean snaps) {
        Object handler = new Object();
        Object connection = new Object();
        Object world = new Object();
        Object scene = new Object();
        AtomicLong nanos = new AtomicLong();
        RingworldClockClientState.onClientConnect(handler, connection);
        try {
            RingworldClockClientState.onClientSceneCommitted(world, scene);
            RingworldClockClientState.Receipt receipt = RingworldClockClientState.captureReceipt(handler);
            RingworldClockMirror mirror = new RingworldClockMirror(nanos::get);
            RingworldClockSample first = new RingworldClockSample(7, GENERATION, 1L, from, true);
            RingworldClockSample second = new RingworldClockSample(7, GENERATION, 2L, to, discontinuous);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, first, world, scene, receipt));
            nanos.set(50_000_000L);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, second, world, scene, receipt));
            nanos.set(75_000_000L);
            assertEquals(new RingworldClockMirror.DisplayTime(snaps ? second : first, second, snaps ? 0.0 : 0.5),
                    mirror.displayTimeFor(world, scene));
            RingworldClockClientState.onClientDisconnect(handler, connection);
            assertNull(mirror.displayTimeFor(world, scene));
        } finally {
            RingworldClockClientState.onClientDisconnect(handler, connection);
        }
    }

    @Test
    public void displayTimeInterpolatesOnlyOneAdjacentContinuousCommittedIntervalAndThenHolds() {
        Object handler = new Object();
        Object connection = new Object();
        Object world = new Object();
        Object scene = new Object();
        AtomicLong nanos = new AtomicLong(0L);
        RingworldClockClientState.onClientConnect(handler, connection);
        try {
            RingworldClockClientState.onClientSceneCommitted(world, scene);
            RingworldClockClientState.Receipt receipt = RingworldClockClientState.captureReceipt(handler);
            RingworldClockMirror mirror = new RingworldClockMirror(nanos::get);
            RingworldClockSample first = new RingworldClockSample(7, GENERATION, 1L, 100L, true);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, first, world, scene, receipt));
            assertEquals(new RingworldClockMirror.DisplayTime(first, first, 0.0),
                    mirror.displayTimeFor(world, scene));

            nanos.set(50_000_000L);
            RingworldClockSample second = new RingworldClockSample(7, GENERATION, 2L, 101L, false);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, second, world, scene, receipt));
            nanos.set(75_000_000L);
            assertEquals(new RingworldClockMirror.DisplayTime(first, second, 0.5),
                    mirror.displayTimeFor(world, scene));
            nanos.set(200_000_000L);
            assertEquals(new RingworldClockMirror.DisplayTime(first, second, 1.0),
                    mirror.displayTimeFor(world, scene));

            RingworldClockSample gap = new RingworldClockSample(7, GENERATION, 4L, 200L, false);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, gap, world, scene, receipt));
            assertEquals(new RingworldClockMirror.DisplayTime(gap, gap, 0.0),
                    mirror.displayTimeFor(world, scene));
        } finally {
            RingworldClockClientState.onClientDisconnect(handler, connection);
        }
    }

    @Test
    public void displayTimeUsesNanoTimeSubtractionAcrossSignedWrapAndStillHoldsAtTheEndpoint() {
        Object handler = new Object();
        Object connection = new Object();
        Object world = new Object();
        Object scene = new Object();
        AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - 10_000_000L);
        RingworldClockClientState.onClientConnect(handler, connection);
        try {
            RingworldClockClientState.onClientSceneCommitted(world, scene);
            RingworldClockClientState.Receipt receipt = RingworldClockClientState.captureReceipt(handler);
            RingworldClockMirror mirror = new RingworldClockMirror(nanos::get);
            RingworldClockSample first = new RingworldClockSample(7, GENERATION, 1L, 100L, true);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, first, world, scene, receipt));
            RingworldClockSample second = new RingworldClockSample(7, GENERATION, 2L, 101L, false);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, second, world, scene, receipt));
            nanos.set(Long.MIN_VALUE + 15_000_000L);
            assertEquals(0.5, mirror.displayTimeFor(world, scene).fraction(), 1.0E-6);
            nanos.set(Long.MIN_VALUE + 90_000_000L);
            assertEquals(1.0, mirror.displayTimeFor(world, scene).fraction(), 0.0);
        } finally {
            RingworldClockClientState.onClientDisconnect(handler, connection);
        }
    }

    @Test
    public void publicAdmissionRequiresMatchingContextGenerationDimensionAndIncreasingSequence() {
        Object handler = new Object();
        Object connection = new Object();
        Object world = new Object();
        Object scene = new Object();
        RingworldClockClientState.onClientConnect(handler, connection);
        try {
            assertNull(RingworldClockClientState.captureReceipt(handler));
            RingworldClockClientState.onClientSceneCommitted(world, scene);
            RingworldClockClientState.Receipt receipt = RingworldClockClientState.captureReceipt(handler);
            RingworldClockMirror mirror = new RingworldClockMirror();
            RingworldClockSample first = new RingworldClockSample(7, GENERATION, 1L, 12_345L);

            assertFalse(mirror.acceptForCommittedScene(8, GENERATION, first, world, scene, receipt));
            assertFalse(mirror.acceptForCommittedScene(7, UUID.randomUUID(), first, world, scene, receipt));
            assertNull(mirror.currentSampleFor(world, scene));

            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, first, world, scene, receipt));
            assertEquals(first, mirror.currentSampleFor(world, scene));
            assertFalse(mirror.acceptForCommittedScene(7, GENERATION,
                    new RingworldClockSample(7, GENERATION, 1L, 99_999L), world, scene, receipt));

            RingworldClockSample next = new RingworldClockSample(7, GENERATION, 2L, 1L);
            assertTrue(mirror.acceptForCommittedScene(7, GENERATION, next, world, scene, receipt));
            assertEquals(next, mirror.currentSampleFor(world, scene));
        } finally {
            RingworldClockClientState.onClientDisconnect(handler, connection);
        }
    }

    @Test
    public void replacedWorldRejectsAReceiptAndLateAUnloadCannotClearB() {
        Object handler = new Object();
        Object connection = new Object();
        Object worldA = new Object();
        Object sceneA = new Object();
        Object worldB = new Object();
        Object sceneB = new Object();
        RingworldClockClientState.onClientConnect(handler, connection);
        try {
            RingworldClockClientState.onClientSceneCommitted(worldA, sceneA);
            RingworldClockClientState.Receipt receiptA = RingworldClockClientState.captureReceipt(handler);
            RingworldClockMirror mirrorA = new RingworldClockMirror();
            RingworldClockSample sampleA = new RingworldClockSample(7, GENERATION, 1L, 12_345L);
            assertTrue(mirrorA.acceptForCommittedScene(7, GENERATION, sampleA, worldA, sceneA, receiptA));

            RingworldClockClientState.onClientSceneCommitted(worldB, sceneB);
            assertNull(mirrorA.displayTimeFor(worldA, sceneA));
            RingworldClockClientState.Receipt receiptB = RingworldClockClientState.captureReceipt(handler);
            RingworldClockMirror mirrorB = new RingworldClockMirror();
            RingworldClockSample sampleB = new RingworldClockSample(7, GENERATION, 1L, 12_345L);
            assertFalse(RingworldClockClientState.isCurrent(receiptA));
            assertFalse(mirrorA.acceptForCommittedScene(7, GENERATION, sampleA, worldA, sceneA, receiptA));
            assertTrue(mirrorB.acceptForCommittedScene(7, GENERATION, sampleB, worldB, sceneB, receiptB));

            RingworldClockClientState.onClientWorldUnloaded(worldA);
            assertTrue(RingworldClockClientState.isCurrent(receiptB));
            assertEquals(sampleB, mirrorB.currentSampleFor(worldB, sceneB));
        } finally {
            RingworldClockClientState.onClientDisconnect(handler, connection);
        }
    }

    @Test
    public void lateADisconnectCannotClearBAndCurrentDisconnectHidesMirror() {
        Object handlerA = new Object();
        Object connectionA = new Object();
        Object handlerB = new Object();
        Object connectionB = new Object();
        Object worldB = new Object();
        Object sceneB = new Object();
        RingworldClockClientState.onClientConnect(handlerA, connectionA);
        RingworldClockClientState.onClientSceneCommitted(new Object(), new Object());
        RingworldClockClientState.onClientConnect(handlerB, connectionB);
        try {
            RingworldClockClientState.onClientSceneCommitted(worldB, sceneB);
            RingworldClockClientState.Receipt receiptB = RingworldClockClientState.captureReceipt(handlerB);
            RingworldClockMirror mirrorB = new RingworldClockMirror();
            RingworldClockSample sampleB = new RingworldClockSample(7, GENERATION, 1L, 12_345L);
            assertTrue(mirrorB.acceptForCommittedScene(7, GENERATION, sampleB, worldB, sceneB, receiptB));

            RingworldClockClientState.onClientDisconnect(handlerA, connectionA);
            assertTrue(RingworldClockClientState.isCurrent(receiptB));
            assertEquals(sampleB, mirrorB.currentSampleFor(worldB, sceneB));

            RingworldClockClientState.onClientDisconnect(handlerB, connectionB);
            assertNull(mirrorB.currentSampleFor(worldB, sceneB));
        } finally {
            RingworldClockClientState.onClientDisconnect(handlerB, connectionB);
        }
    }
}
