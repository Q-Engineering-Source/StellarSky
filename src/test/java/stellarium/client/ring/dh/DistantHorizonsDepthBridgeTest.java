package stellarium.client.ring.dh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.world.ring.RingworldRenderObserver;

/** The bridge is metadata-only: callers cannot mutate a captured matrix or replace a GL resource. */
public class DistantHorizonsDepthBridgeTest {
    @Test public void captureDefensivelyCopiesTheOpenGlColumnMajorMatrixAndKeepsTheFrameIdentity() {
        RingworldCurvatureFrame frame = frame();
        float[] inverse = identity();
        DistantHorizonsDepthBridge.Snapshot snapshot = DistantHorizonsDepthBridge.capture(
                frame, 71, inverse, 4, 8, 1600, 900, 1.5, -2.0, 3.25);
        inverse[0] = 99.0F;

        // `current()` adds the production current-frame identity gate; this checks the independent
        // captured payload is immutable and has no GL deletion behavior.
        assertSame(frame, snapshot.frame());
        assertEquals(71, snapshot.textureId());
        assertEquals(1.0F, snapshot.inverseViewProjection()[0], 0.0F);
        float[] copied = snapshot.inverseViewProjection();
        assertNotSame(copied, snapshot.inverseViewProjection());
        copied[5] = 42.0F;
        assertEquals(1.0F, snapshot.inverseViewProjection()[5], 0.0F);
        assertEquals(4, snapshot.viewportX());
        assertEquals(8, snapshot.viewportY());
        assertEquals(1600, snapshot.viewportWidth());
        assertEquals(900, snapshot.viewportHeight());
        assertEquals(1.5, snapshot.cameraOffsetX(), 0.0);
        DistantHorizonsDepthBridge.clear();
    }

    @Test public void missingTextureOrInvalidViewportCannotBecomeADepthClamp() {
        RingworldCurvatureFrame frame = frame();
        assertThrows(IllegalArgumentException.class, () -> DistantHorizonsDepthBridge.capture(
                frame, 0, identity(), 0, 0, 1, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DistantHorizonsDepthBridge.capture(
                frame, 1, identity(), 0, 0, 0, 1, 0, 0, 0));
    }

    private static RingworldCurvatureFrame frame() {
        float[] identity = identity();
        return new RingworldCurvatureFrame(new Object(), new Object(), new RingworldRenderObserver(10, 64, 20),
                149_597_870_700.0, identity, identity, 0, 0, 1600, 900);
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }
}
