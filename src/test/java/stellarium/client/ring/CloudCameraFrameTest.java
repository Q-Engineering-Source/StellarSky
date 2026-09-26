package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import org.junit.Test;

public class CloudCameraFrameTest {
    @Test public void standardProjectionAndTranslatedEyeAreFrozenTogether() {
        float[] projection = diagonal(1.0F, 2.0F, -1.0F, 1.0F);
        float[] modelView = identity(); modelView[12] = -10.0F; modelView[13] = 3.0F; modelView[14] = -7.0F;
        CloudCameraFrame frame = CloudCameraFrame.from(projection, modelView, 4, 5, 1920, 1000);
        assertEquals(.001D, frame.pixelAngularSize(), 1.0E-12D);
        assertEquals(10.0F, frame.cameraX(), 0.0F); assertEquals(-3.0F, frame.cameraY(), 0.0F); assertEquals(7.0F, frame.cameraZ(), 0.0F);
        assertTrue(frame.matchesViewport(4, 5, 1920, 1000)); assertFalse(frame.matchesViewport(0, 5, 1920, 1000));
    }

    @Test public void zoomProjectionChangesOnlyTheFrozenSubpixelMetric() {
        CloudCameraFrame normal = CloudCameraFrame.from(diagonal(1, 1, -1, 1), identity(), 0, 0, 800, 600);
        CloudCameraFrame zoom = CloudCameraFrame.from(diagonal(1, 4, -1, 1), identity(), 0, 0, 800, 600);
        assertEquals(2.0D / 600.0D, normal.pixelAngularSize(), 0.0D);
        assertEquals(normal.pixelAngularSize() / 4.0D, zoom.pixelAngularSize(), 0.0D);
    }

    @Test public void arbitraryRotationUsesInverseTranslationRatherThanAssumingFeetOrigin() {
        float[] modelView = identity();
        // OpenGL column-major 90-degree Z rotation then translated camera transform.
        modelView[0] = 0; modelView[1] = 1; modelView[4] = -1; modelView[5] = 0;
        modelView[12] = -4; modelView[13] = -9;
        CloudCameraFrame frame = CloudCameraFrame.from(diagonal(1, 1, -1, 1), modelView, 0, 0, 1, 1);
        assertEquals(9.0F, frame.cameraX(), 0.0F); assertEquals(-4.0F, frame.cameraY(), 0.0F);
    }

    @Test public void inverseCopiesUseCallerBufferAndDoNotExposeMutableStorage() {
        float[] projection = diagonal(2, 4, -1, 1);
        CloudCameraFrame frame = CloudCameraFrame.from(projection, identity(), 0, 0, 1, 2);
        projection[0] = 99.0F;
        FloatBuffer first = FloatBuffer.allocate(16), second = FloatBuffer.allocate(16);
        frame.copyInverseProjection(first); first.put(0, 99.0F); frame.copyInverseProjection(second);
        assertEquals(.5F, second.get(0), 0.0F); assertEquals(16, second.remaining());
    }

    @Test public void genuinePerspectiveProjectionRoundTripsThroughTheFrozenInverse() {
        float[] perspective = {
                1.5F,0,0,0,
                0,2.0F,0,0,
                0,0,-1.002002F,-1,
                0,0,-0.2002002F,0
        };
        CloudCameraFrame frame = CloudCameraFrame.from(perspective, identity(), 0, 0, 1600, 900);
        FloatBuffer inverse = FloatBuffer.allocate(16);
        frame.copyInverseProjection(inverse);
        float[] product = multiply(perspective, inverse.array());
        for (int index = 0; index < 16; index++) assertEquals(index % 5 == 0 ? 1.0F : 0.0F, product[index], 2.0E-5F);
        assertEquals(2.0D / (900.0D * 2.0D), frame.pixelAngularSize(), 0.0D);
    }

    @Test public void rejectsSingularNonfiniteAndEmptyInputs() {
        assertThrows(IllegalArgumentException.class, () -> CloudCameraFrame.from(identity(), identity(), 0, 0, 0, 1));
        float[] singular = identity(); singular[0] = 0; assertThrows(IllegalArgumentException.class, () -> CloudCameraFrame.from(singular, identity(), 0, 0, 1, 1));
        float[] nonfinite = identity(); nonfinite[5] = Float.NaN; assertThrows(IllegalArgumentException.class, () -> CloudCameraFrame.from(nonfinite, identity(), 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CloudCameraFrame.from(new float[15], identity(), 0, 0, 1, 1));
    }

    private static float[] identity() { return diagonal(1, 1, 1, 1); }
    private static float[] diagonal(float a, float b, float c, float d) { return new float[]{a,0,0,0, 0,b,0,0, 0,0,c,0, 0,0,0,d}; }
    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) for (int index = 0; index < 4; index++) result[column * 4 + row] += left[index * 4 + row] * right[column * 4 + index];
        return result;
    }
}
