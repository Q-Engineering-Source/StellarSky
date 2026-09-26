package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** CPU-only culling contracts for the reusable far-ring model mesh. */
public class ProceduralRingModelCullingTest {
    @Test
    public void defaultMeshHasTheBoundedSixtyFourBatchCullingWorkload() {
        var model = new ProceduralRingModelGeometry(10_000.0D, 100.0D, -32.0D, 32.0D);
        assertEquals(ProceduralRingModelCulling.DEFAULT_BATCH_COUNT, model.mesh().batchCount());
        boolean[] visible = ProceduralRingModelCulling.visible(model.mesh(), model.pose(0.0D, 100.0D, 0.0D, 0.0D),
                identity(), identity());
        assertEquals(ProceduralRingModelCulling.DEFAULT_BATCH_COUNT, visible.length);
    }

    @Test
    public void translatedAndRotatedPoseKeepsEveryBatchContainingAVisibleVertex() {
        var model = new ProceduralRingModelGeometry(100.0D, 10.0D, -2.0D, 2.0D, 128);
        var pose = model.pose(23.0D, 10.0D, 0.0D, 0.0D);
        float[] projection = perspective(1.0F, 1.0F, 1.0F, 500.0F);
        float[] modelView = multiply(translation(0.0F, 0.0F, -120.0F), rotationZ(0.10F));
        boolean[] selected = ProceduralRingModelCulling.visible(model.mesh(), pose, projection, modelView);

        boolean anyVisibleVertex = false;
        for (int batchIndex = 0; batchIndex < model.mesh().batchCount(); batchIndex++) {
            boolean batchHasVisibleVertex = false;
            var batch = model.mesh().batch(batchIndex);
            for (int vertex = batch.firstVertex(); vertex < batch.firstVertex() + batch.vertexCount(); vertex++) {
                var global = point(model.mesh(), vertex);
                var positioned = pose.transformPoint(global);
                double[] clip = project(positioned, projection, modelView);
                if (insideRetainedFrustum(clip)) {
                    batchHasVisibleVertex = true;
                    anyVisibleVertex = true;
                }
            }
            if (batchHasVisibleVertex) assertTrue("Culling rejected a batch containing a projected visible vertex", selected[batchIndex]);
        }
        assertTrue("fixture must exercise at least one visible projected vertex", anyVisibleVertex);
    }

    @Test
    public void deliberatelyOmitsFarPlaneRejection() {
        var model = new ProceduralRingModelGeometry(5.0D, 1.0D, -0.1D, 0.1D, 128);
        float[] projection = perspective(1.0F, 1.0F, 1.0F, 10.0F);
        boolean[] selected = ProceduralRingModelCulling.visible(model.mesh(), model.pose(0.0D, 1.0D, 0.0D, 0.0D),
                projection, translation(0.0F, 0.0F, -100.0F));
        for (boolean visible : selected) assertTrue("beyond an ordinary far plane remains retained", visible);
    }

    @Test
    public void rejectsBatchesOutsideRetainedSidesButDoesNotRequireFarPlane() {
        var model = new ProceduralRingModelGeometry(100.0D, 10.0D, -2.0D, 2.0D, 128);
        boolean[] selected = ProceduralRingModelCulling.visible(model.mesh(), model.pose(0.0D, 10.0D, 0.0D, 0.0D),
                perspective(1.0F, 1.0F, 1.0F, 500.0F), translation(150.0F, 0.0F, -120.0F));
        assertTrue(selected[0]);
        assertFalse(selected[1]);
    }

    @Test
    public void invalidMatricesFailFast() {
        var model = new ProceduralRingModelGeometry(100.0D, 10.0D, -2.0D, 2.0D, 32);
        var pose = model.pose(0.0D, 10.0D, 0.0D, 0.0D);
        assertThrows(IllegalArgumentException.class,
                () -> ProceduralRingModelCulling.visible(model.mesh(), pose, new float[15], identity()));
        float[] nan = identity();
        nan[3] = Float.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> ProceduralRingModelCulling.visible(model.mesh(), pose, nan, identity()));
        assertThrows(NullPointerException.class,
                () -> ProceduralRingModelCulling.visible(model.mesh(), pose, null, identity()));
    }

    private static ProceduralRingModelGeometry.Point point(ProceduralRingModelGeometry.Mesh mesh, int vertex) {
        return new ProceduralRingModelGeometry.Point((double) mesh.component(vertex, 0) + mesh.component(vertex, 3),
                (double) mesh.component(vertex, 1) + mesh.component(vertex, 4),
                (double) mesh.component(vertex, 2) + mesh.component(vertex, 5));
    }

    private static boolean insideRetainedFrustum(double[] clip) {
        return clip[0] >= -clip[3] && clip[0] <= clip[3]
                && clip[1] >= -clip[3] && clip[1] <= clip[3] && clip[2] >= -clip[3];
    }

    private static double[] project(ProceduralRingModelGeometry.Point point, float[] projection, float[] modelView) {
        double[] view = multiply(modelView, point.x(), point.y(), point.z(), 1.0D);
        return multiply(projection, view[0], view[1], view[2], view[3]);
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] translation(float x, float y, float z) {
        float[] matrix = identity();
        matrix[12] = x;
        matrix[13] = y;
        matrix[14] = z;
        return matrix;
    }

    private static float[] rotationZ(float radians) {
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        return new float[] {cosine, sine, 0, 0, -sine, cosine, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] perspective(float horizontalScale, float verticalScale, float near, float far) {
        float a = -(far + near) / (far - near);
        float b = -(2.0F * far * near) / (far - near);
        return new float[] {horizontalScale, 0, 0, 0, 0, verticalScale, 0, 0, 0, 0, a, -1, 0, 0, b, 0};
    }

    /** Column-major {@code left * right}. */
    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                result[column * 4 + row] = left[row] * right[column * 4]
                        + left[4 + row] * right[column * 4 + 1]
                        + left[8 + row] * right[column * 4 + 2]
                        + left[12 + row] * right[column * 4 + 3];
            }
        }
        return result;
    }

    private static double[] multiply(float[] matrix, double x, double y, double z, double w) {
        return new double[] {
                matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
                matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
                matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
                matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
        };
    }
}
