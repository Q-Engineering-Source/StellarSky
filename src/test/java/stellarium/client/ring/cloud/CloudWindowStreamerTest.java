package stellarium.client.ring.cloud;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.Test;

/** Latest target wins even if a prior CPU cache finishes after it was superseded. */
public class CloudWindowStreamerTest {
    @Test public void rasterPublicationRequiresCompleteGeometryAndMatchingRadius() {
        var geometry=new CloudGeometrySettings(12,4,64);
        var clip=new CloudClipBounds(0,256,-8192,8192);
        var field=new CloudFieldSettings(0,0,false,0);
        var target=new CloudWindowStreamer.Target(field,geometry,0,0,224,clip,false,1,43_682_578);
        var draft=CloudWindowStreamer.buildPackage(1,target,null);
        assertFalse(draft.covers(target,16));
        QueueExecutor executor=new QueueExecutor();
        try (var streamer=new CloudWindowStreamer(executor)) {
            streamer.request(target); executor.runOne();
            var ready=streamer.poll(target);
            assertNotNull(ready.rasterGeometry());
            assertSame(ready.cache(),ready.rasterGeometry().cache());
            var changed=new CloudWindowStreamer.Target(field,geometry,0,0,224,clip,false,1,43_682_579);
            assertFalse(ready.covers(changed,16));
        }
    }
    @Test public void staleQueuedTargetCanNeverPublish() {
        QueueExecutor executor = new QueueExecutor();
        CloudWindowStreamer streamer = new CloudWindowStreamer(executor);
        CloudGeometrySettings geometry = new CloudGeometrySettings(12.0D, 4.0D, 64);
        CloudClipBounds clip = new CloudClipBounds(0.0D, 256.0D, -8192.0D, 8192.0D);
        CloudWindowStreamer.Target oldTarget = new CloudWindowStreamer.Target(CloudFieldSettings.DEFAULT, geometry, 0L, 0L, 128.0D, clip, false, 1L);
        CloudWindowStreamer.Target newTarget = new CloudWindowStreamer.Target(CloudFieldSettings.DEFAULT, geometry, 16L, 0L, 128.0D, clip, false, 1L);
        streamer.request(oldTarget); streamer.request(newTarget);
        executor.runOne();
        assertNull(streamer.poll(oldTarget));
        CloudWindowStreamer.Ready ready = streamer.poll(newTarget);
        assertSame(newTarget, ready.target());
        streamer.close();
    }
    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runOne() { tasks.remove().run(); }
    }
}
