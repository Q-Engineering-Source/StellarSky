package stellarium.client.ring.cloud;

import java.util.Objects;

/** Finite diagnostic page using the same canonical reduction as the runtime cache. */
public final class CloudWindow {
    private final long originX, originZ;
    private final int width, depth, layers;
    private final double cellSize;
    private final CloudFieldSettings settings;
    private final int[] argb;

    public CloudWindow(long originX, long originZ, int width, int depth, int layers, double cellSize,
                       CloudFieldSettings settings) {
        if (width <= 0 || depth <= 0 || (layers != 1 && layers != 2 && layers != 4 && layers != 8)
                || !Double.isFinite(cellSize) || cellSize <= 0.0D) {
            throw new IllegalArgumentException("finite cloud window needs positive dimensions and 1/2/4/8 canonical layer groups");
        }
        this.originX = originX; this.originZ = originZ; this.width = width; this.depth = depth; this.layers = layers;
        this.cellSize = cellSize; this.settings = Objects.requireNonNull(settings, "settings");
        this.argb = new int[Math.multiplyExact(Math.multiplyExact(width, depth), layers)];
        CloudWorldField.Sampler sampler = CloudWorldField.sampler(settings);
        CloudColumn column = new CloudColumn();
        int[] reduced = new int[layers];
        for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            double worldX = Math.addExact(originX, x) * cellSize;
            double worldZ = Math.addExact(originZ, z) * cellSize;
            if (layers == 1) sampler.fillFilteredColumn(worldX, worldZ, cellSize, column);
            else sampler.fillColumn(worldX, worldZ, column);
            CloudWorldField.reduceInto(layers, column, reduced);
            for (int layer = 0; layer < layers; layer++) {
                argb[(layer * depth + z) * width + x] = reduced[layer];
            }
        }
    }
    public long originX() { return originX; }
    public long originZ() { return originZ; }
    public int width() { return width; }
    public int depth() { return depth; }
    public int layers() { return layers; }
    public double cellSize() { return cellSize; }
    public boolean contains(long x, long z) { return x >= originX && x - originX < width && z >= originZ && z - originZ < depth; }
    public int argb(long x, int layer, long z) {
        if (layer < 0 || layer >= layers || !contains(x, z)) return 0;
        return argb[(layer * depth + (int) (z - originZ)) * width + (int) (x - originX)];
    }
    public CloudMask asMask(long generation) { return CloudMask.window(generation, originX, originZ, width, depth, layers, argb); }
}
