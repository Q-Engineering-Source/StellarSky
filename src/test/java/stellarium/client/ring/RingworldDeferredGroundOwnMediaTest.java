package stellarium.client.ring;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;
import stellarium.world.ring.RingworldClockMirror;
import stellarium.world.ring.RingworldClockSample;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;

/**
 * The deferred ground colour consumer's own-media contract.
 *
 * <p>The late settle draws the ring ground after the early opaque board/cloud
 * media, so it must not cover a nearer own-media fragment. That requires three
 * things the headless suite can actually pin: the production model program must
 * bind the own-media uniform contract, a missing uniform must stay fatal, and
 * the attachment the consumer samples may only ever be this pass's own frame and
 * scope.</p>
 *
 * <p>These assertions never open a GL context and never run Mixin weaving; they
 * do not prove the discard itself, which needs real client evidence.</p>
 */
public class RingworldDeferredGroundOwnMediaTest {
    private static final String ACTIVE = "uSSOwnMediaDepthActive";
    private static final String DEPTH = "uSSOwnMediaDepth";
    private static final String PIXEL_OFFSET = "uSSOwnMediaDepthPixelOffset";

    @Test
    public void modelConsumerBindsTheOwnMediaDepthContract() {
        Set<String> requested = new LinkedHashSet<>();
        new ProceduralRingModelProgram().bindUniforms(name -> {
            requested.add(name);
            return 0;
        });
        assertTrue("model program must bind " + ACTIVE + ": " + requested, requested.contains(ACTIVE));
        assertTrue("model program must bind " + DEPTH + ": " + requested, requested.contains(DEPTH));
        assertTrue("model program must bind " + PIXEL_OFFSET + ": " + requested, requested.contains(PIXEL_OFFSET));
    }

    @Test
    public void missingOwnMediaDepthUniformStaysFatal() {
        for (String missing : List.of(ACTIVE, DEPTH, PIXEL_OFFSET)) {
            assertThrows(missing, IllegalStateException.class,
                    () -> new ProceduralRingModelProgram().bindUniforms(name -> name.equals(missing) ? -1 : 0));
        }
    }

    @Test
    public void ownMediaAttachmentIsOnlyVisibleForItsOwnFrameAndScope() {
        Object world = new Object(), scene = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            // Nothing may be sampled before this pass publishes its own capture.
            assertNull(RingworldOwnMediaOcclusion.current());
            RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
            var captured = ownMedia(frame);
            RingworldRenderSnapshots.captureOwnMediaOcclusion(captured);
            assertSame(captured, RingworldOwnMediaOcclusion.current());
            // A new eye/viewport pass must not inherit the previous attachment.
            RingworldRenderSnapshots.clearCurvatureFrame();
            assertNull(RingworldOwnMediaOcclusion.current());
        });
        assertNull(RingworldOwnMediaOcclusion.current());
    }

    @Test
    public void nestedScopeCannotReadTheOuterOwnMediaAttachment() {
        Object world = new Object(), scene = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
            RingworldRenderSnapshots.captureOwnMediaOcclusion(ownMedia(frame));
            assertNotNull(RingworldOwnMediaOcclusion.current());
            RingworldRenderSnapshots.withSnapshot(() -> null, () -> assertNull(RingworldOwnMediaOcclusion.current()));
            assertNotNull(RingworldOwnMediaOcclusion.current());
        });
    }

    /** An attachment bound to this frame but an older scope depth is not evidence for this pass. */
    @Test
    public void ownMediaAttachmentBoundToAnotherScopeIsRejected() {
        Object world = new Object(), scene = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
            RingworldRenderSnapshots.captureOwnMediaOcclusion(ownMedia(frame, RingworldRenderSnapshots.scopeDepth() + 1));
            assertNull(RingworldOwnMediaOcclusion.current());
        });
    }

    private static RingworldOwnMediaOcclusion.Snapshot ownMedia(RingworldCurvatureFrame frame) {
        return ownMedia(frame, RingworldRenderSnapshots.scopeDepth());
    }

    private static RingworldOwnMediaOcclusion.Snapshot ownMedia(RingworldCurvatureFrame frame, int scopeDepth) {
        return new RingworldOwnMediaOcclusion.Snapshot(frame, 1, 0, 0, 800, 600, 800, 600, scopeDepth);
    }

    private static RingworldDisplaySnapshot snapshot(Object world, Object scene) {
        RingworldClockSample sample = new RingworldClockSample(0, UUID.randomUUID(), 1L, 0L, true);
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        return new RingworldDisplaySnapshot(world, scene,
                new RingworldClockMirror.DisplayTime(sample, sample, 0.0), sunshade,
                sunshade.phase(0L, 0L, 0.0), 512, 8,
                new RingworldRenderObserver(0.0, 64.0, 0.0), 1.0, 0.2);
    }

    private static RingworldCurvatureFrame frame(Object world, Object scene, RingworldDisplaySnapshot snapshot) {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new RingworldCurvatureFrame(world, scene, snapshot.observer(),
                149597870700.0, identity, identity, 0, 0, 800, 600);
    }
}
