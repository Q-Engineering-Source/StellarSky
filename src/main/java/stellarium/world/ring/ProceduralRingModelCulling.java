package stellarium.world.ring;

import java.util.Objects;

/**
 * CPU frustum selection for the globally encoded first procedural-ring mesh.
 *
 * <p>Matrices are OpenGL column-major {@code float[16]} values.  A batch AABB first receives the
 * same {@link ProceduralRingModelGeometry.Pose} affine transform as mesh vertices, then its eight
 * corners are tested in homogeneous clip space against left, right, bottom, top, and near planes.
 * The far plane is deliberately omitted: the ring remains a distant visual shell and must not
 * disappear solely because an ordinary terrain far plane is shorter than the ring radius.</p>
 */
public final class ProceduralRingModelCulling {
    /** The default 4096-segment mesh has exactly 64 AABB batches. */
    public static final int DEFAULT_BATCH_COUNT = 64;

    private ProceduralRingModelCulling() {
    }

    /**
     * Returns one visibility value per mesh batch.  A {@code true} value is conservative: batches
     * crossing a retained plane remain visible.  This method owns no mutable culling state.
     */
    public static boolean[] visible(ProceduralRingModelGeometry.Mesh mesh, ProceduralRingModelGeometry.Pose pose,
                                    float[] projection, float[] modelView) {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(pose, "pose");
        requireMatrix(projection, "projection");
        requireMatrix(modelView, "modelView");
        boolean[] result = new boolean[mesh.batchCount()];
        for (int batchIndex = 0; batchIndex < result.length; batchIndex++) {
            result[batchIndex] = intersectsRetainedFrustum(mesh.batch(batchIndex).encodedBounds(), pose,
                    projection, modelView);
        }
        return result;
    }

    private static boolean intersectsRetainedFrustum(ProceduralRingModelGeometry.Aabb bounds,
                                                      ProceduralRingModelGeometry.Pose pose,
                                                      float[] projection, float[] modelView) {
        double left = Double.NEGATIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        double top = Double.NEGATIVE_INFINITY;
        double near = Double.NEGATIVE_INFINITY;
        double greatestMagnitude = 1.0D;
        for (int xi = 0; xi < 2; xi++) {
            double x = xi == 0 ? bounds.minX() : bounds.maxX();
            for (int yi = 0; yi < 2; yi++) {
                double y = yi == 0 ? bounds.minY() : bounds.maxY();
                for (int zi = 0; zi < 2; zi++) {
                    double z = zi == 0 ? bounds.minZ() : bounds.maxZ();
                    ProceduralRingModelGeometry.Point positioned =
                            pose.transformPoint(new ProceduralRingModelGeometry.Point(x, y, z));
                    ClipPoint point = project(positioned, projection, modelView);
                    left = Math.max(left, point.x() + point.w());
                    right = Math.max(right, point.w() - point.x());
                    bottom = Math.max(bottom, point.y() + point.w());
                    top = Math.max(top, point.w() - point.y());
                    near = Math.max(near, point.z() + point.w());
                    greatestMagnitude = Math.max(greatestMagnitude, Math.abs(point.x()));
                    greatestMagnitude = Math.max(greatestMagnitude, Math.abs(point.y()));
                    greatestMagnitude = Math.max(greatestMagnitude, Math.abs(point.z()));
                    greatestMagnitude = Math.max(greatestMagnitude, Math.abs(point.w()));
                }
            }
        }
        double allowance = greatestMagnitude * 1.0e-6D + 1.0e-9D;
        return !outside(left, allowance) && !outside(right, allowance)
                && !outside(bottom, allowance) && !outside(top, allowance)
                && !outside(near, allowance);
    }

    private static ClipPoint project(ProceduralRingModelGeometry.Point point, float[] projection, float[] modelView) {
        double viewX = modelView[0] * point.x() + modelView[4] * point.y() + modelView[8] * point.z()
                + modelView[12];
        double viewY = modelView[1] * point.x() + modelView[5] * point.y() + modelView[9] * point.z()
                + modelView[13];
        double viewZ = modelView[2] * point.x() + modelView[6] * point.y() + modelView[10] * point.z()
                + modelView[14];
        double viewW = modelView[3] * point.x() + modelView[7] * point.y() + modelView[11] * point.z()
                + modelView[15];
        double clipX = projection[0] * viewX + projection[4] * viewY + projection[8] * viewZ + projection[12] * viewW;
        double clipY = projection[1] * viewX + projection[5] * viewY + projection[9] * viewZ + projection[13] * viewW;
        double clipZ = projection[2] * viewX + projection[6] * viewY + projection[10] * viewZ + projection[14] * viewW;
        double clipW = projection[3] * viewX + projection[7] * viewY + projection[11] * viewZ + projection[15] * viewW;
        if (!Double.isFinite(clipX) || !Double.isFinite(clipY) || !Double.isFinite(clipZ) || !Double.isFinite(clipW)) {
            throw new IllegalArgumentException("Projection produced a non-finite clip coordinate");
        }
        return new ClipPoint(clipX, clipY, clipZ, clipW);
    }

    private static boolean outside(double greatestSignedDistance, double allowance) {
        // Inputs originated as float GL matrices.  Retaining a one-ppm envelope avoids false
        // rejection when an encoded float split, affine pose, and clip arithmetic meet a plane.
        return greatestSignedDistance < -allowance;
    }

    private static void requireMatrix(float[] matrix, String name) {
        Objects.requireNonNull(matrix, name);
        if (matrix.length != 16) {
            throw new IllegalArgumentException(name + " must contain exactly 16 column-major values");
        }
        for (int index = 0; index < matrix.length; index++) {
            if (!Float.isFinite(matrix[index])) {
                throw new IllegalArgumentException(name + " contains a non-finite value at index " + index);
            }
        }
    }

    private record ClipPoint(double x, double y, double z, double w) {
    }
}
