package stellarium.client.ring;

import java.nio.FloatBuffer;
import java.util.Arrays;

/** Immutable CPU snapshot of one camera/projection frame shared by cloud query consumers. */
final class CloudCameraFrame {
    private final float[] inverseProjection;
    private final float[] inverseModelView;
    private final float[] projection;
    private final float[] modelView;
    private final float cameraX;
    private final float cameraY;
    private final float cameraZ;
    private final double pixelAngularSize;
    private final int viewportX, viewportY, viewportWidth, viewportHeight;

    private CloudCameraFrame(float[] projection, float[] modelView, float[] inverseProjection, float[] inverseModelView,
                             float cameraX, float cameraY, float cameraZ, double pixelAngularSize,
                             int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        this.inverseProjection = inverseProjection;
        this.inverseModelView = inverseModelView;
        this.projection = projection.clone();
        this.modelView = modelView.clone();
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.pixelAngularSize = pixelAngularSize;
        this.viewportX = viewportX;
        this.viewportY = viewportY;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    static CloudCameraFrame from(float[] projection, float[] modelView, int viewportX, int viewportY,
                                 int width, int height) {
        requireMatrix("projection", projection);
        requireMatrix("modelView", modelView);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("cloud viewport must have positive dimensions");
        if (!Float.isFinite(projection[5]) || projection[5] == 0.0F) throw new IllegalArgumentException("cloud projection cannot define pixel angular size");
        double pixelAngularSize = 2.0D / (height * Math.abs((double) projection[5]));
        if (!Double.isFinite(pixelAngularSize) || pixelAngularSize <= 0.0D) throw new IllegalArgumentException("cloud pixel angular size is invalid");
        float[] inverseProjection = invert(projection);
        float[] inverseModelView = invert(modelView);
        float w = inverseModelView[15];
        if (!Float.isFinite(w) || w == 0.0F) throw new IllegalArgumentException("cloud inverse model view has no finite eye origin");
        float cameraX = inverseModelView[12] / w;
        float cameraY = inverseModelView[13] / w;
        float cameraZ = inverseModelView[14] / w;
        if (!Float.isFinite(cameraX) || !Float.isFinite(cameraY) || !Float.isFinite(cameraZ)) {
            throw new IllegalArgumentException("cloud eye origin is not finite");
        }
        return new CloudCameraFrame(projection, modelView, inverseProjection, inverseModelView, cameraX, cameraY, cameraZ,
                pixelAngularSize, viewportX, viewportY, width, height);
    }

    double pixelAngularSize() { return pixelAngularSize; }
    float cameraX() { return cameraX; }
    float cameraY() { return cameraY; }
    float cameraZ() { return cameraZ; }
    int viewportX() { return viewportX; }
    int viewportY() { return viewportY; }
    int viewportWidth() { return viewportWidth; }
    int viewportHeight() { return viewportHeight; }

    /** Exact pixel/ray identity, including viewport origin for the shared Bayer pattern. */
    boolean sameView(CloudCameraFrame other) {
        return other != null && other.matchesViewport(viewportX, viewportY, viewportWidth, viewportHeight)
                && Arrays.equals(projection, other.projection) && Arrays.equals(modelView, other.modelView);
    }

    /**
     * Conservative world-displacement allowance for an ordinary perspective view.
     * The factor four bounds projection near the viewport edge and reserves room for
     * the displaced point approaching the eye plane. Unsupported projections get no hold.
     */
    double displacementForQuarterPixel(double minimumDistance) {
        if (!Double.isFinite(minimumDistance) || minimumDistance <= 0.0) return 0.0;
        if (projection[3] != 0 || projection[7] != 0 || projection[11] != -1 || projection[15] != 0
                || projection[1] != 0 || projection[4] != 0 || projection[12] != 0 || projection[13] != 0
                || projection[0] == 0 || projection[5] == 0) return 0.0;
        // Arbitrary camera scales/shears do not have the rigid-view metric used below.
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
            double dot = 0.0;
            for (int k = 0; k < 3; k++) dot += (double) modelView[a * 4 + k] * modelView[b * 4 + k];
            if (Math.abs(dot - (a == b ? 1.0 : 0.0)) > 1.0e-5) return 0.0;
        }
        if (modelView[3] != 0 || modelView[7] != 0 || modelView[11] != 0 || modelView[15] != 1) return 0.0;
        double tx = (1.0 + Math.abs(projection[8])) / Math.abs(projection[0]);
        double ty = (1.0 + Math.abs(projection[9])) / Math.abs(projection[5]);
        double cone = 1.0 + tx * tx + ty * ty;
        double focal = Math.max(viewportWidth * Math.abs((double) projection[0]),
                viewportHeight * Math.abs((double) projection[5])) * 0.5;
        return Math.min(minimumDistance * 0.25 / (4.0 * focal * cone),
                minimumDistance / (4.0 * Math.sqrt(cone)));
    }
    boolean matchesViewport(int x, int y, int width, int height) {
        return viewportX == x && viewportY == y && viewportWidth == width && viewportHeight == height;
    }

    void copyInverseProjection(FloatBuffer target) { copy(inverseProjection, target); }
    void copyInverseModelView(FloatBuffer target) { copy(inverseModelView, target); }

    private static void copy(float[] source, FloatBuffer target) {
        if (target == null || target.capacity() < 16) throw new IllegalArgumentException("cloud inverse target requires 16 floats");
        target.clear();
        target.put(source);
        target.flip();
    }

    private static void requireMatrix(String name, float[] matrix) {
        if (matrix == null || matrix.length != 16) throw new IllegalArgumentException(name + " matrix requires exactly 16 floats");
        for (float value : matrix) if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " matrix contains non-finite input");
    }

    /** Column-major 4x4 inverse using partial pivoting; singularity is an exact finite failure, not an arbitrary zoom epsilon. */
    private static float[] invert(float[] source) {
        double[][] work = new double[4][8];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            work[row][column] = source[column * 4 + row];
            work[row][column + 4] = row == column ? 1.0D : 0.0D;
        }
        for (int pivot = 0; pivot < 4; pivot++) {
            int selected = pivot;
            double largest = Math.abs(work[pivot][pivot]);
            for (int row = pivot + 1; row < 4; row++) if (Math.abs(work[row][pivot]) > largest) { largest = Math.abs(work[row][pivot]); selected = row; }
            if (!Double.isFinite(largest) || largest == 0.0D) throw new IllegalArgumentException("cloud camera matrix is singular");
            if (selected != pivot) { double[] temporary = work[pivot]; work[pivot] = work[selected]; work[selected] = temporary; }
            double divisor = work[pivot][pivot];
            for (int column = 0; column < 8; column++) work[pivot][column] /= divisor;
            for (int row = 0; row < 4; row++) if (row != pivot) {
                double factor = work[row][pivot];
                for (int column = 0; column < 8; column++) work[row][column] -= factor * work[pivot][column];
            }
        }
        float[] inverse = new float[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            double value = work[row][column + 4];
            if (!Double.isFinite(value) || value > Float.MAX_VALUE || value < -Float.MAX_VALUE) throw new IllegalArgumentException("cloud camera inverse is not finite");
            inverse[column * 4 + row] = (float) value;
        }
        return inverse;
    }
}
