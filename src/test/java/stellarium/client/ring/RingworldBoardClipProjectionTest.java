package stellarium.client.ring;

import java.nio.FloatBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class RingworldBoardClipProjectionTest {
    @Test
    public void preservesNearClippingAndScreenPositionWhileAdmittingFarVertices() {
        float[] source = perspective(0.05, 512.0);
        FloatBuffer clipped = FloatBuffer.wrap(source.clone());
        RingworldBoardClipProjection.removeFarPlane(clipped);
        for (int index : new int[] {0,1,2,3,4,5,6,7,8,9,11,12,13,15}) {
            assertEquals(source[index], clipped.get(index), 0.0F);
        }
        for (double distance : new double[] {-8192, -1, 0, .025, .051, 1, 512, 8192, 87_365_156}) {
            double finiteNear = -source[10] * distance + source[14] + distance;
            double infiniteNear = -clipped.get(10) * distance + clipped.get(14) + distance;
            assertEquals("near/behind-eye ownership at " + distance, finiteNear > 0, infiniteNear > 0);
            if (distance > .05) assertTrue(-clipped.get(10) * distance + clipped.get(14) <= distance);
        }
        double near = source[14] / (source[10] - 1.0);
        assertEquals(near, clipped.get(14) / (clipped.get(10) - 1.0), 1.0e-8);
    }

    @Test
    public void alreadyInfiniteAndOffAxisMatricesRetainTheirOpticalNearPlane() {
        float[] source = perspective(.1, 1024);
        source[8] = .2F; source[9] = -.1F;
        FloatBuffer matrix = FloatBuffer.wrap(source);
        RingworldBoardClipProjection.removeFarPlane(matrix);
        float nearTerm = matrix.get(14);
        RingworldBoardClipProjection.removeFarPlane(matrix);
        assertEquals(nearTerm, matrix.get(14), 0.0F);
        assertEquals(.2F, matrix.get(8), 0.0F);
        assertEquals(-.1F, matrix.get(9), 0.0F);
    }

    @Test
    public void rejectsAnObliqueOrNonPerspectiveNearPlane() {
        float[] source = perspective(.05, 512);
        source[2] = .25F;
        assertFalse(RingworldBoardClipProjection.supports(FloatBuffer.wrap(source)));
        assertThrows(IllegalArgumentException.class,
                () -> RingworldBoardClipProjection.removeFarPlane(FloatBuffer.wrap(source)));
    }

    private static float[] perspective(double near, double far) {
        float[] matrix = new float[16];
        matrix[0] = 1.0F; matrix[5] = 1.5F;
        matrix[10] = (float) (-(far + near) / (far - near));
        matrix[11] = -1;
        matrix[14] = (float) (-2 * far * near / (far - near));
        return matrix;
    }
}
