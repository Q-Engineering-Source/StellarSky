package stellarium.client.ring.cloud;

import java.util.Objects;

/**
 * One frame's stable +X cloud movement and camera-relative mesh origin.
 *
 * <p>The fixed +X direction is intentional. Wind is unwrapped material
 * distance, not a mask-period remainder, so crossing old 768m boundaries
 * cannot return an old formation.</p>
 */
public record CloudMotionFrame(long anchorCellX,
                               long anchorCellZ,
                               double meshOffsetX,
                               double meshOffsetZ,
                               double windOffsetBlocks) {
    public static final double WIND_BLOCKS_PER_TICK = 0.03D;
    /** Bound keeps the unwrapped tick + partial-tick conversion materially precise. */
    public static final long MAX_SUPPORTED_CLIENT_TICKS = 1L << 40;

    public CloudMotionFrame {
        requireFinite("meshOffsetX", meshOffsetX);
        requireFinite("meshOffsetZ", meshOffsetZ);
        requireFinite("windOffsetBlocks", windOffsetBlocks);
        if (windOffsetBlocks < 0.0D) {
            throw new IllegalArgumentException("windOffsetBlocks must be non-negative");
        }
    }

    public static CloudMotionFrame at(long clientTicks, double partialTicks,
                                      double observerX, double observerZ,
                                      CloudMask mask, CloudGeometrySettings geometry) {
        Objects.requireNonNull(mask, "mask");
        return at(clientTicks, partialTicks, observerX, observerZ, geometry);
    }

    /** Material-coordinate frame; production no longer derives a period from a mask. */
    public static CloudMotionFrame at(long clientTicks, double partialTicks,
                                      double observerX, double observerZ, CloudGeometrySettings geometry) {
        Objects.requireNonNull(geometry, "geometry");
        requireFinite("partialTicks", partialTicks);
        requireFinite("observerX", observerX);
        requireFinite("observerZ", observerZ);
        if (partialTicks < 0.0D || partialTicks > 1.0D) {
            throw new IllegalArgumentException("partialTicks must be within [0, 1]");
        }

        // A signed long tick clock multiplied by .03 remains finite, but the
        // explicit guard prevents an accidental long wrap from becoming a
        // discontinuous cloud teleport.
        if (clientTicks < 0L || clientTicks > MAX_SUPPORTED_CLIENT_TICKS) {
            throw new IllegalArgumentException("cloud wind clock is outside the exact supported range");
        }
        double wind = (clientTicks + partialTicks) * WIND_BLOCKS_PER_TICK;
        if (!Double.isFinite(wind)) throw new IllegalArgumentException("cloud wind distance overflowed");

        long anchorX = floorToLong((observerX - wind) / geometry.cellSizeBlocks(), "observerX");
        long anchorZ = floorToLong(observerZ / geometry.cellSizeBlocks(), "observerZ");
        double offsetX = anchorX * geometry.cellSizeBlocks() + wind - observerX;
        double offsetZ = anchorZ * geometry.cellSizeBlocks() - observerZ;
        return new CloudMotionFrame(anchorX, anchorZ, offsetX, offsetZ, wind);
    }

    public CloudMeshCacheKey cacheKey(CloudMask mask, CloudGeometrySettings geometry,
                                      double cloudBaseY, CloudClipBounds clipBounds) {
        return CloudMeshCacheKey.at(anchorCellX, anchorCellZ, mask, geometry, cloudBaseY, clipBounds);
    }

    private static long floorToLong(double value, String name) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the supported cloud-cell coordinate range");
        }
        return (long) Math.floor(value);
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
