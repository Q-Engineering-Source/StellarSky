package stellarium.client.ring.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

/** Pure CPU guardrails for pre-submission cloud page rejection. */
public class CloudLodVisibilityTest {
    @Test
    public void highObserverRejectsFineAndMidButRetainsLowBand() {
        var frame = flatFrame(0.0D, 4_096.0D, 0.0D);
        var cloud = new CloudLodPatchMeshBuilder.Aabb(-32.0D, 32.0D, 224.0D, 256.0D, -32.0D, 32.0D);
        assertFalse(frame.visible(cloud, 0, 12.0D));
        assertFalse(frame.visible(cloud, 1, 12.0D));
        assertTrue(frame.visible(cloud, 2, 12.0D));
    }

    @Test
    public void boundaryAndEyeInsideAreKeptConservatively() {
        var frame = flatFrame(0.0D, 0.0D, 0.0D);
        var inside = new CloudLodPatchMeshBuilder.Aabb(-1.0D, 1.0D, -1.0D, 1.0D, -1.0D, 1.0D);
        assertTrue(frame.visible(inside, 0.0D, 1.0D));
        var atEnd = new CloudLodPatchMeshBuilder.Aabb(2.0D, 2.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        assertTrue("half-open LOD endpoint must not be falsely rejected by a conservative batch test",
                frame.visible(atEnd, 1.0D, 2.0D));
    }

    @Test
    public void nearPlaneStraddleAndBeyondTerrainFarRemainVisible() {
        var nearFrame = CloudLodVisibility.prepare(unitPose(), 0.0D, 0.0D, 0.0D, identity(), identity());
        var straddle = new CloudLodPatchMeshBuilder.Aabb(-0.25D, 0.25D, -0.25D, 0.25D, -1.25D, -0.75D);
        assertTrue("batch crossing retained near plane must remain", nearFrame.visible(straddle, 0.0D, 8.0D));

        var distant = flatFrame(0.0D, 0.0D, 0.0D);
        var beyondTerrain = new CloudLodPatchMeshBuilder.Aabb(-8.0D, 8.0D, -8.0D, 8.0D, 100_000.0D, 100_016.0D);
        assertTrue("five-plane culling deliberately omits the Minecraft far plane",
                distant.visible(beyondTerrain, 65_536.0D, 131_072.0D));
    }

    @Test
    public void rotationAndWindPoseUseTheInverseEyeForDistance() {
        double angle = 0.65D;
        var pose = new CloudLodVisibility.Pose(Math.cos(angle), Math.sin(angle), 43_682_578.0D,
                17.0D, -9.0D, 3.0D);
        // This encoded point is exactly the inverse pose of the supplied display eye.
        double encodedX = 2_000.0D;
        double encodedY = 240.0D;
        double encodedZ = 40.0D;
        double displayX = pose.cos() * encodedX + pose.sin() * (encodedY - pose.radius()) + pose.translateX();
        double displayY = -pose.sin() * encodedX + pose.cos() * (encodedY - pose.radius()) + pose.radius() + pose.translateY();
        double displayZ = encodedZ + pose.translateZ();
        var frame = CloudLodVisibility.prepare(pose, displayX, displayY, displayZ, flatClip(), identity());
        var point = new CloudLodPatchMeshBuilder.Aabb(encodedX, encodedX, encodedY, encodedY, encodedZ, encodedZ);
        assertTrue(frame.visible(point, 0.0D, 1.0D));
    }

    @Test
    public void randomizedContainedSamplesAreNeverCulled() {
        double angle = 0.31D;
        var pose = new CloudLodVisibility.Pose(Math.cos(angle), Math.sin(angle), 43_682_578.0D, 4.0D, -3.0D, 1.0D);
        // Choose a known embedded eye, then derive the display eye from the same pose. This
        // keeps random samples around the actual inverse-eye instead of around world origin.
        double eyeX = 13_540_000.0D, eyeY = 256.0D, eyeZ = 40.0D;
        double[] displayEye = poseForward(pose, eyeX, eyeY, eyeZ);
        float[] projection = perspective(1.1F, 0.9F, 1.0F, 30_000.0F);
        float[] modelView = translation((float) -displayEye[0], (float) -displayEye[1], (float) -displayEye[2]);
        var frame = CloudLodVisibility.prepare(pose, displayEye[0], displayEye[1], displayEye[2], projection, modelView);
        Random random = new Random(0xC41A11L);
        int witnessed = 0;
        for (int iteration = 0; iteration < 1_000; iteration++) {
            double x = eyeX + random.nextDouble() * 1_000.0D - 500.0D;
            double y = eyeY + random.nextDouble() * 1_000.0D - 500.0D;
            // Keep lateral samples well inside the actual perspective cone; the test is
            // about conservative culling, not a random near-plane miss distribution.
            double z = eyeZ - 2_000.0D - random.nextDouble() * 8_000.0D;
            double sx = 1.0D + random.nextDouble() * 32.0D;
            double sy = 1.0D + random.nextDouble() * 32.0D;
            double sz = 1.0D + random.nextDouble() * 32.0D;
            var bounds = new CloudLodPatchMeshBuilder.Aabb(x, x + sx, y, y + sy, z, z + sz);
            double centerDistance = distance(eyeX, eyeY, eyeZ, x + sx * 0.5D, y + sy * 0.5D, z + sz * 0.5D);
            double minimum = Math.max(0.0D, centerDistance - 64.0D);
            double maximum = centerDistance + 64.0D;
            boolean sampled = false;
            for (int xi = 0; xi < 3; xi++) for (int yi = 0; yi < 3; yi++) for (int zi = 0; zi < 3; zi++) {
                double px = interpolate(bounds.minX(), bounds.maxX(), xi / 2.0D);
                double py = interpolate(bounds.minY(), bounds.maxY(), yi / 2.0D);
                double pz = interpolate(bounds.minZ(), bounds.maxZ(), zi / 2.0D);
                if (independentlyRetained(pose, projection, modelView, eyeX, eyeY, eyeZ, px, py, pz, minimum, maximum)) sampled = true;
            }
            assertTrue("fixture must produce a visible reference sample at iteration " + iteration, sampled);
            witnessed++;
            assertTrue("conservative batch culling rejected sampled visible content at iteration " + iteration, frame.visible(bounds, minimum, maximum));
        }
        assertTrue("randomized test must exercise substantial non-vacuous visible coverage", witnessed > 100);

        int rejected = 0;
        for (int iteration = 0; iteration < 200; iteration++) {
            double z = eyeZ - 1_000.0D - random.nextDouble() * 4_000.0D;
            var bounds = new CloudLodPatchMeshBuilder.Aabb(eyeX - 4.0D, eyeX + 4.0D, eyeY - 4.0D, eyeY + 4.0D, z - 4.0D, z + 4.0D);
            double d = distance(eyeX, eyeY, eyeZ, eyeX, eyeY, z);
            if (!frame.visible(bounds, d + 200.0D, d + 400.0D)) rejected++;
        }
        assertTrue("randomized frame must also exercise meaningful distance rejection", rejected > 100);
    }

    @Test
    public void validatesPoseMatricesAndBandInputs() {
        assertThrows(IllegalArgumentException.class, () -> new CloudLodVisibility.Pose(1.0D, 1.0D, 1.0D, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CloudLodVisibility.bandForLevel(13, 12.0D));
        assertThrows(IllegalArgumentException.class, () -> CloudLodVisibility.bandForLevel(0, 0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> CloudLodVisibility.prepare(unitPose(), 0, 0, 0, new float[15], identity()));
    }

    private static CloudLodVisibility.Frame flatFrame(double x, double y, double z) {
        return CloudLodVisibility.prepare(unitPose(), x, y, z, flatClip(), identity());
    }
    private static CloudLodVisibility.Pose unitPose() { return new CloudLodVisibility.Pose(1, 0, 43_682_578.0D, 0, 0, 0); }
    private static float[] flatClip() { float[] result = new float[16]; result[15] = 1.0F; return result; }
    private static float[] identity() { return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }
    private static double interpolate(double low, double high, double t) { return low + (high - low) * t; }

    private static double[] poseForward(CloudLodVisibility.Pose pose, double x, double y, double z) {
        return new double[] {pose.cos() * x + pose.sin() * (y - pose.radius()) + pose.translateX(),
                -pose.sin() * x + pose.cos() * (y - pose.radius()) + pose.radius() + pose.translateY(), z + pose.translateZ()};
    }
    private static boolean independentlyRetained(CloudLodVisibility.Pose pose, float[] projection, float[] modelView,
                                                  double eyeX, double eyeY, double eyeZ, double x, double y, double z,
                                                  double minimum, double maximum) {
        double distance = distance(eyeX, eyeY, eyeZ, x, y, z);
        if (distance < minimum || distance >= maximum) return false;
        double[] display = poseForward(pose, x, y, z);
        double[] view = multiply(modelView, display[0], display[1], display[2], 1.0D);
        double[] clip = multiply(projection, view[0], view[1], view[2], view[3]);
        return clip[0] >= -clip[3] && clip[0] <= clip[3] && clip[1] >= -clip[3] && clip[1] <= clip[3]
                && clip[2] >= -clip[3];
    }
    private static float[] perspective(float x, float y, float near, float far) {
        return new float[] {x, 0, 0, 0, 0, y, 0, 0, 0, 0, -(far + near) / (far - near), -1,
                0, 0, -2 * far * near / (far - near), 0};
    }
    private static float[] translation(float x, float y, float z) {
        float[] result = identity(); result[12] = x; result[13] = y; result[14] = z; return result;
    }
    private static double[] multiply(float[] matrix, double x, double y, double z, double w) {
        return new double[] {matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
                matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
                matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
                matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w};
    }
    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        return Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz));
    }
}
