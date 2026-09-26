package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Test;

/** Tests scheduling at the production builder seam, including movement DURING a cold build. */
public class CloudWindowStreamingProgressTest {
    private static final CloudFieldSettings CLEAR = new CloudFieldSettings(0, 0, false, .2);
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12, 4, 64);
    private static final CloudClipBounds CLIP = new CloudClipBounds(0, 512, -8192, 8192);

    @Test
    public void movementDuringColdBuildRetainsWorkAndReusesItInsteadOfStartingColdForever() {
        QueueExecutor queue = new QueueExecutor();
        CloudWindowStreamer[] owner = new CloudWindowStreamer[1];
        CloudWindowStreamer.Ready[] first = new CloudWindowStreamer.Ready[1];
        int[] builds = {0};
        var origin = target(0, 1);
        var advanced = target(64, 1); // Four anchors pass during the measured ~1.67s cold build.
        owner[0] = new CloudWindowStreamer(queue, (serial, request, previous) -> {
            if (builds[0]++ == 0) {
                assertNull(previous);
                owner[0].request(advanced);
                return first[0] = CloudWindowStreamer.buildPackage(serial, request, null);
            }
            assertSame(first[0], owner[0].ready());
            assertSame(first[0].cache(), previous);
            assertFalse(first[0].covers(advanced, 16)); // Old mesh is not drawn at a distant camera.
            return CloudWindowStreamer.buildPackage(serial, request, previous);
        });
        owner[0].request(origin);
        assertEquals(1, queue.size());
        queue.runOne();
        assertEquals(2, builds[0]);
        assertEquals(0, queue.size());
        assertTrue(owner[0].poll(advanced).covers(advanced, 16));
        owner[0].close();
    }

    @Test
    public void completedPrefetchCanServeTheNextAnchorButNeverANewSpecification() {
        QueueExecutor queue = new QueueExecutor();
        CloudWindowStreamer stream = new CloudWindowStreamer(queue);
        stream.request(target(0, 1));
        queue.runOne();
        var ready = stream.ready();
        stream.request(target(16, 1)); // Pending B does not prevent use of completed A.
        assertTrue(ready.covers(target(16, 1), 16));
        assertFalse(ready.covers(target(32, 1), 16));
        assertFalse(ready.covers(target(0, 2), 16));
        var changedGeometry = new CloudWindowStreamer.Target(CLEAR,
                new CloudGeometrySettings(24, 4, 64), 0, 0, 128, CLIP, false, 1);
        assertFalse(ready.covers(changedGeometry, 16));
        var changedHeight = new CloudWindowStreamer.Target(CLEAR, GEOMETRY, 0, 0, 256, CLIP, false, 1);
        assertFalse(ready.covers(changedHeight, 16));
        var changedClip = new CloudWindowStreamer.Target(CLEAR, GEOMETRY, 0, 0, 128,
                new CloudClipBounds(0, 256, -8192, 8192), false, 1);
        assertFalse(ready.covers(changedClip, 16));
        var changedMode = new CloudWindowStreamer.Target(CLEAR, GEOMETRY, 0, 0, 128, CLIP, true, 1);
        assertFalse(ready.covers(changedMode, 16));
        stream.close();
        queue.runOne();
        assertNull(stream.ready());
    }

    @Test
    public void invalidationDuringBuildCannotPublishEvenIfTheNewTargetHasIdenticalValues() {
        QueueExecutor queue = new QueueExecutor();
        CloudWindowStreamer[] owner = new CloudWindowStreamer[1];
        int[] builds = {0};
        var request = target(0, 1);
        owner[0] = new CloudWindowStreamer(queue, (serial, target, previous) -> {
            if (builds[0]++ == 0) {
                owner[0].invalidate();
                owner[0].request(request);
            } else {
                assertNull(owner[0].ready());
                assertNull(previous);
            }
            return CloudWindowStreamer.buildPackage(serial, target, previous);
        });
        owner[0].request(request);
        queue.runOne();
        assertEquals(2, builds[0]);
        assertNotNull(owner[0].poll(request));
        owner[0].close();
    }

    @Test
    public void errorIsObservableAndCannotPermanentlyOccupyTheWorkerSlot() {
        QueueExecutor queue = new QueueExecutor();
        AssertionError failure = new AssertionError("injected worker allocation failure");
        int[] attempts = {0};
        CloudWindowStreamer stream = new CloudWindowStreamer(queue, (serial, target, previous) -> {
            if (attempts[0]++ == 0) throw failure;
            return CloudWindowStreamer.buildPackage(serial, target, previous);
        });
        var first = target(0, 1);
        stream.request(first);
        assertSame(failure, assertThrows(AssertionError.class, queue::runOne));
        assertSame(failure, stream.failure(first));
        var second = target(16, 1);
        stream.request(second);
        assertNull(stream.failure(second));
        queue.runOne();
        assertNotNull(stream.poll(second));
        stream.close();
    }

    @Test
    public void executorRejectionIsReportedAndADifferentRequestCanStart() {
        QueueExecutor queue = new QueueExecutor();
        int[] launches = {0};
        Executor rejectOnce = task -> {
            if (launches[0]++ == 0) throw new RejectedExecutionException("injected rejection");
            queue.execute(task);
        };
        CloudWindowStreamer stream = new CloudWindowStreamer(rejectOnce);
        var first = target(0, 1);
        stream.request(first);
        assertTrue(stream.failure(first) instanceof RejectedExecutionException);
        var second = target(16, 1);
        stream.request(second);
        queue.runOne();
        assertNotNull(stream.poll(second));
        stream.close();
    }

    @Test
    public void fatalErrorDuringObsoleteBuildIsReportedToTheLiveTargetWithoutAnAutomaticRetry() {
        QueueExecutor queue = new QueueExecutor();
        CloudWindowStreamer[] owner = new CloudWindowStreamer[1];
        AssertionError error = new AssertionError("injected fatal error");
        var newer = target(64, 1);
        owner[0] = new CloudWindowStreamer(queue, (serial, target, previous) -> {
            owner[0].request(newer);
            throw error;
        });
        owner[0].request(target(0, 1));
        assertSame(error, assertThrows(AssertionError.class, queue::runOne));
        assertSame(error, owner[0].failure(newer));
        assertNull(owner[0].ready());
        assertEquals(0, queue.size());
        owner[0].close();
    }

    private static CloudWindowStreamer.Target target(long x, long generation) {
        return new CloudWindowStreamer.Target(CLEAR, GEOMETRY, x, 0, 128, CLIP, false, generation);
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable runnable) { queue.add(runnable); }
        void runOne() { queue.remove().run(); }
        int size() { return queue.size(); }
    }
}
