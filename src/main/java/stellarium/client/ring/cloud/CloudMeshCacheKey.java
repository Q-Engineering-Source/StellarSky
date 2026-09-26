package stellarium.client.ring.cloud;

import java.util.Objects;

/**
 * Cache identity for static voxel geometry. Camera fractional position, time,
 * wind remainder and illumination intentionally do not belong here.
 */
public record CloudMeshCacheKey(long anchorCellX,
                                long anchorCellZ,
                                int visibleCellRadius,
                                long maskGeneration,
                                CloudMask mask,
                                CloudGeometrySettings geometry,
                                double cloudBaseY,
                                CloudClipBounds clipBounds) {
    public CloudMeshCacheKey {
        Objects.requireNonNull(mask, "mask");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(clipBounds, "clipBounds");
        if (visibleCellRadius != geometry.visibleCellRadius()) {
            throw new IllegalArgumentException("Cache radius must match geometry settings");
        }
        long safeMargin = (long) visibleCellRadius + 1L;
        if (anchorCellX < Long.MIN_VALUE + safeMargin || anchorCellX > Long.MAX_VALUE - safeMargin
                || anchorCellZ < Long.MIN_VALUE + safeMargin || anchorCellZ > Long.MAX_VALUE - safeMargin) {
            throw new IllegalArgumentException("Cache anchor is too close to the long coordinate boundary");
        }
        if (maskGeneration != mask.generation()) {
            throw new IllegalArgumentException("Cache generation must match its mask");
        }
        if (!Double.isFinite(cloudBaseY)) {
            throw new IllegalArgumentException("cloudBaseY must be finite");
        }
    }

    public static CloudMeshCacheKey at(long anchorCellX, long anchorCellZ,
                                       CloudMask mask, CloudGeometrySettings geometry,
                                       double cloudBaseY, CloudClipBounds clipBounds) {
        return new CloudMeshCacheKey(anchorCellX, anchorCellZ, geometry.visibleCellRadius(),
                mask.generation(), mask, geometry, cloudBaseY, clipBounds);
    }
}
