package stellarium.client.ring.cloud;

import java.util.Objects;

/**
 * Immutable occupancy and base-colour field for the custom cloud volume.
 * Legacy constructors retain their repeating fixture semantics. Production
 * world pages use {@link #window(long, long, long, int, int, int, int[])} and
 * are deliberately finite: outside-page reads are air, never a wrapped tile.
 */
public final class CloudMask {
    public static final int MAX_LAYERS = 8;
    private final long generation;
    private final int width;
    private final int depth;
    private final int layers;
    private final int[] argb;
    private final boolean repeating;
    private final long originX;
    private final long originZ;

    public CloudMask(long generation, int width, int height, int[] argb) {
        this(generation, width, height, 1, argb);
    }

    /**
     * Creates a finite vertical cloud volume. X/Z repeat; a layer outside the
     * supplied vertical range is air rather than a wrapped texture sample.
     */
    public CloudMask(long generation, int width, int depth, int layers, int[] argb) {
        this(generation, 0L, 0L, width, depth, layers, argb, true);
    }

    private CloudMask(long generation, long originX, long originZ, int width, int depth, int layers, int[] argb,
                      boolean repeating) {
        if (width <= 0 || depth <= 0 || layers <= 0 || layers > MAX_LAYERS) {
            throw new IllegalArgumentException("Cloud mask dimensions must be greater than zero");
        }
        long cellCount = (long) width * depth * layers;
        if (cellCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cloud mask is too large");
        }
        Objects.requireNonNull(argb, "argb");
        if (argb.length != (int) cellCount) {
            throw new IllegalArgumentException("Cloud mask ARGB length does not match its dimensions");
        }
        this.generation = generation;
        this.width = width;
        this.depth = depth;
        this.layers = layers;
        this.argb = argb.clone();
        this.repeating = repeating;
        this.originX = originX;
        this.originZ = originZ;
    }

    /** Creates one finite page in absolute cloud-cell coordinates. */
    public static CloudMask window(long generation, long originX, long originZ, int width, int depth, int layers,
                                   int[] argb) {
        return new CloudMask(generation, originX, originZ, width, depth, layers, argb, false);
    }

    public long generation() {
        return generation;
    }

    public int width() {
        return width;
    }

    public int height() {
        return depth;
    }

    /** X/Z depth, retained separately from the number of vertical voxel layers. */
    public int depth() {
        return depth;
    }

    public int layers() {
        return layers;
    }

    public boolean repeating() { return repeating; }
    public long originX() { return originX; }
    public long originZ() { return originZ; }

    /** Returns the page cell colour; legacy masks repeat while world pages do not. */
    public int cellArgb(long cellX, long cellZ) {
        return cellArgb(cellX, 0, cellZ);
    }

    public int cellArgb(long cellX, int layer, long cellZ) {
        if (layer < 0 || layer >= layers) {
            return 0;
        }
        if (repeating) {
            int wrappedX = Math.floorMod(cellX, width);
            int wrappedZ = Math.floorMod(cellZ, depth);
            return argb[(layer * depth + wrappedZ) * width + wrappedX];
        }
        long localX = cellX - originX;
        long localZ = cellZ - originZ;
        if (localX < 0L || localX >= width || localZ < 0L || localZ >= depth) return 0;
        return argb[(layer * depth + (int) localZ) * width + (int) localX];
    }

    public boolean occupiedAt(long cellX, long cellZ) {
        return ((cellArgb(cellX, cellZ) >>> 24) & 0xFF) >= 128;
    }

    public boolean occupiedAt(long cellX, int layer, long cellZ) {
        return ((cellArgb(cellX, layer, cellZ) >>> 24) & 0xFF) >= 128;
    }

    /** A defensive copy for diagnostics or resource-change tests. */
    public int[] copyArgb() {
        return argb.clone();
    }
}
