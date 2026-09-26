package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.nio.FloatBuffer;
import org.junit.Test;
import stellarium.world.ring.RingworldDisplayGeometry;
import stellarium.world.ring.RingworldRenderObserver;

public class RingworldCurvatureFrameTest {
    @Test public void opticalEyeIsNotTheRenderOriginAndBothCoordinateDirectionsAgree() {
        Object world = new Object(), scene = new Object();
        float[] view = identity(); view[12] = -3; view[13] = -2; view[14] = 5;
        RingworldCurvatureFrame frame = new RingworldCurvatureFrame(world, scene,
                new RingworldRenderObserver(12000, 64, -80), 131072, identity(), view, 0, 0, 1920, 1080);
        assertEquals(new RingworldRenderObserver(12003, 66, -85), frame.opticalEye());
        RingworldDisplayGeometry.Point physical = new RingworldDisplayGeometry.Point(8192, 0, 20);
        RingworldDisplayGeometry.Point rendered = frame.displayRelative(physical);
        RingworldDisplayGeometry.Point restored = frame.physicalRelative(rendered);
        assertEquals(physical.x(), restored.x(), 1e-8);
        assertEquals(physical.y(), restored.y(), 1e-8);
        assertEquals(physical.z(), restored.z(), 1e-8);
        assertTrue(frame.belongsTo(world, scene));
        assertFalse(frame.belongsTo(new Object(), scene));
        assertFalse(frame.belongsTo(world, new Object()));
    }

    @Test public void matricesAreFrozenAndOutputBuffersDoNotOwnFrameStorage() {
        float[] projection = identity(), view = identity();
        view[13] = -2;
        RingworldCurvatureFrame frame = new RingworldCurvatureFrame(new Object(), new Object(),
                new RingworldRenderObserver(0, 64, 0), RingworldDisplayGeometry.DEFAULT_RADIUS_METERS,
                projection, view, 3, 4, 800, 600);
        projection[0] = 10; view[13] = -70;
        FloatBuffer matrix = FloatBuffer.allocate(16);
        frame.copyProjection(matrix); assertEquals(1, matrix.get(0), 0);
        frame.copyModelView(matrix); assertEquals(-2, matrix.get(13), 0);
        matrix.put(13, 80); frame.copyModelView(matrix); assertEquals(-2, matrix.get(13), 0);
        assertTrue(frame.matchesViewport(3, 4, 800, 600));
        assertFalse(frame.matchesViewport(3, 4, 801, 600));
        assertThrows(IllegalArgumentException.class, () -> frame.copyProjection(FloatBuffer.allocate(15)));
    }

    @Test public void rejectsAxisCameraBeforeFramePublication() {
        float[] view = identity(); view[13] = -2;
        assertThrows(IllegalArgumentException.class, () -> new RingworldCurvatureFrame(new Object(), new Object(),
                new RingworldRenderObserver(0, 98, 0), 100, identity(), view, 0, 0, 800, 600));
    }

    private static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }
}
