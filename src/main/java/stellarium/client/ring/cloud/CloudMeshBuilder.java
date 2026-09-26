package stellarium.client.ring.cloud;

import java.util.Arrays;
import java.util.Objects;

/**
 * Builds the closed visible-voxel boundary in anchor-relative finite float
 * coordinates. Faces with the same plane, normal and source RGB are greedily
 * merged; occupancy itself is never simplified or recoloured.
 */
public final class CloudMeshBuilder {
    private static final int MAX_FLOATS = 64 * 1024 * 1024 / Float.BYTES;
    private static final int NO_FACE = -1;

    private CloudMeshBuilder() {
    }

    public static CloudMesh build(CloudMeshCacheKey key) {
        return build(key, true);
    }

    /**
     * With a continuous horizon field, the cache window is not a material
     * boundary. Physical air clips still produce caps in either mode.
     */
    public static CloudMesh build(CloudMeshCacheKey key, boolean closeWindow) {
        Objects.requireNonNull(key, "key");
        VertexWriter writer = new VertexWriter(MAX_FLOATS);
        int side = 2 * key.visibleCellRadius() + 1;
        int[] faces = new int[side * Math.max(side, key.mask().layers())];

        emitHorizontalFaces(key, closeWindow, side, faces, writer, true);
        emitHorizontalFaces(key, closeWindow, side, faces, writer, false);
        emitXFaces(key, closeWindow, side, faces, writer, true);
        emitXFaces(key, closeWindow, side, faces, writer, false);
        emitZFaces(key, closeWindow, side, faces, writer, true);
        emitZFaces(key, closeWindow, side, faces, writer, false);
        return new CloudMesh(writer.quadCount, writer.finish());
    }

    private static void emitHorizontalFaces(CloudMeshCacheKey key, boolean closeWindow, int side,
                                            int[] faces, VertexWriter writer, boolean top) {
        int radius = key.visibleCellRadius();
        for (int layer = 0; layer < key.mask().layers(); layer++) {
            clearFaces(faces, side * side);
            for (int localZ = -radius; localZ <= radius; localZ++) {
                long worldZ = key.anchorCellZ() + localZ;
                for (int localX = -radius; localX <= radius; localX++) {
                    long worldX = key.anchorCellX() + localX;
                    if (!visibleVoxel(key, worldX, layer, worldZ)) {
                        continue;
                    }
                    int neighbourLayer = top ? layer + 1 : layer - 1;
                    if (!hasVisibleNeighbour(key, worldX, neighbourLayer, worldZ,
                            localX, localZ, closeWindow)) {
                        faces[(localZ + radius) * side + localX + radius]
                                = rgb(key.mask().cellArgb(worldX, layer, worldZ));
                    }
                }
            }
            final int faceLayer = layer;
            mergeRectangles(faces, side, side, (u, v, width, height, color) -> {
                double x0 = xPlane(key, u - radius);
                double x1 = xPlane(key, u - radius + width);
                double z0 = zLowerPlane(key, v - radius);
                double z1 = zUpperPlane(key, v - radius + height - 1);
                double y = top ? yUpper(key, faceLayer) : yLower(key, faceLayer);
                if (top) {
                    writer.top(x0, x1, z0, z1, y, red(color), green(color), blue(color));
                } else {
                    writer.bottom(x0, x1, z0, z1, y, red(color), green(color), blue(color));
                }
            });
        }
    }

    private static void emitXFaces(CloudMeshCacheKey key, boolean closeWindow, int side,
                                   int[] faces, VertexWriter writer, boolean west) {
        int radius = key.visibleCellRadius();
        int layers = key.mask().layers();
        for (int localX = -radius; localX <= radius; localX++) {
            clearFaces(faces, side * layers);
            long worldX = key.anchorCellX() + localX;
            for (int layer = 0; layer < layers; layer++) {
                for (int localZ = -radius; localZ <= radius; localZ++) {
                    long worldZ = key.anchorCellZ() + localZ;
                    if (!visibleVoxel(key, worldX, layer, worldZ)) {
                        continue;
                    }
                    long neighbourX = west ? worldX - 1L : worldX + 1L;
                    int neighbourLocalX = west ? localX - 1 : localX + 1;
                    if (!hasVisibleNeighbour(key, neighbourX, layer, worldZ,
                            neighbourLocalX, localZ, closeWindow)) {
                        faces[layer * side + localZ + radius] = rgb(key.mask().cellArgb(worldX, layer, worldZ));
                    }
                }
            }
            final int faceX = localX;
            mergeRectangles(faces, side, layers, (u, v, width, height, color) -> {
                double z0 = zLowerPlane(key, u - radius);
                double z1 = zUpperPlane(key, u - radius + width - 1);
                double y0 = yLower(key, v);
                double y1 = yUpper(key, v + height - 1);
                double x = xPlane(key, west ? faceX : faceX + 1);
                if (west) {
                    writer.west(x, z0, z1, y0, y1, red(color), green(color), blue(color));
                } else {
                    writer.east(x, z0, z1, y0, y1, red(color), green(color), blue(color));
                }
            });
        }
    }

    private static void emitZFaces(CloudMeshCacheKey key, boolean closeWindow, int side,
                                   int[] faces, VertexWriter writer, boolean north) {
        int radius = key.visibleCellRadius();
        int layers = key.mask().layers();
        for (int localZ = -radius; localZ <= radius; localZ++) {
            clearFaces(faces, side * layers);
            long worldZ = key.anchorCellZ() + localZ;
            for (int layer = 0; layer < layers; layer++) {
                for (int localX = -radius; localX <= radius; localX++) {
                    long worldX = key.anchorCellX() + localX;
                    if (!visibleVoxel(key, worldX, layer, worldZ)) {
                        continue;
                    }
                    long neighbourZ = north ? worldZ - 1L : worldZ + 1L;
                    int neighbourLocalZ = north ? localZ - 1 : localZ + 1;
                    if (!hasVisibleNeighbour(key, worldX, layer, neighbourZ,
                            localX, neighbourLocalZ, closeWindow)) {
                        faces[layer * side + localX + radius] = rgb(key.mask().cellArgb(worldX, layer, worldZ));
                    }
                }
            }
            final int faceZ = localZ;
            mergeRectangles(faces, side, layers, (u, v, width, height, color) -> {
                double x0 = xPlane(key, u - radius);
                double x1 = xPlane(key, u - radius + width);
                double y0 = yLower(key, v);
                double y1 = yUpper(key, v + height - 1);
                double z = north ? zLowerPlane(key, faceZ) : zUpperPlane(key, faceZ);
                if (north) {
                    writer.north(x0, x1, z, y0, y1, red(color), green(color), blue(color));
                } else {
                    writer.south(x0, x1, z, y0, y1, red(color), green(color), blue(color));
                }
            });
        }
    }

    /** Greedily consumes equal-colour rectangles from a bounded two-dimensional face slice. */
    private static void mergeRectangles(int[] faces, int width, int height, RectangleEmitter emitter) {
        for (int v = 0; v < height; v++) {
            for (int u = 0; u < width; u++) {
                int start = v * width + u;
                int color = faces[start];
                if (color == NO_FACE) {
                    continue;
                }
                int rectangleWidth = 1;
                while (u + rectangleWidth < width && faces[start + rectangleWidth] == color) {
                    rectangleWidth++;
                }
                int rectangleHeight = 1;
                while (v + rectangleHeight < height && rowMatches(faces, width, u,
                        v + rectangleHeight, rectangleWidth, color)) {
                    rectangleHeight++;
                }
                for (int row = v; row < v + rectangleHeight; row++) {
                    Arrays.fill(faces, row * width + u, row * width + u + rectangleWidth, NO_FACE);
                }
                emitter.emit(u, v, rectangleWidth, rectangleHeight, color);
            }
        }
    }

    private static boolean rowMatches(int[] faces, int width, int u, int v, int rectangleWidth, int color) {
        int start = v * width + u;
        for (int offset = 0; offset < rectangleWidth; offset++) {
            if (faces[start + offset] != color) {
                return false;
            }
        }
        return true;
    }

    private static void clearFaces(int[] faces, int length) {
        Arrays.fill(faces, 0, length, NO_FACE);
    }

    private static boolean visibleVoxel(CloudMeshCacheKey key, long worldX, int layer, long worldZ) {
        return key.mask().occupiedAt(worldX, layer, worldZ) && zUpper(key, worldZ) > zLower(key, worldZ)
                && yUpper(key, layer) > yLower(key, layer);
    }

    private static boolean hasVisibleNeighbour(CloudMeshCacheKey key, long worldX, int layer, long worldZ,
                                                int localX, int localZ, boolean closeWindow) {
        int radius = key.visibleCellRadius();
        if (closeWindow && (localX < -radius || localX > radius || localZ < -radius || localZ > radius)) {
            return false;
        }
        return visibleVoxel(key, worldX, layer, worldZ);
    }

    private static double xPlane(CloudMeshCacheKey key, int localX) {
        return gridPlane(localX, key.geometry().cellSizeBlocks());
    }

    private static double zLowerPlane(CloudMeshCacheKey key, int localZ) {
        return zLower(key, key.anchorCellZ() + localZ)
                - gridPlane(key.anchorCellZ(), key.geometry().cellSizeBlocks());
    }

    private static double zUpperPlane(CloudMeshCacheKey key, int localZ) {
        return zUpper(key, key.anchorCellZ() + localZ)
                - gridPlane(key.anchorCellZ(), key.geometry().cellSizeBlocks());
    }

    private static double zLower(CloudMeshCacheKey key, long worldZ) {
        return Math.max(gridPlane(worldZ, key.geometry().cellSizeBlocks()), key.clipBounds().minWorldZ());
    }

    private static double zUpper(CloudMeshCacheKey key, long worldZ) {
        return Math.min(gridPlane(worldZ + 1L, key.geometry().cellSizeBlocks()), key.clipBounds().maxWorldZ());
    }

    private static double yLower(CloudMeshCacheKey key, int layer) {
        return Math.max(gridPlane(layer, key.geometry().voxelHeightBlocks()),
                key.clipBounds().lowerY() - key.cloudBaseY());
    }

    private static double yUpper(CloudMeshCacheKey key, int layer) {
        return Math.min(gridPlane(layer + 1L, key.geometry().voxelHeightBlocks()),
                key.clipBounds().upperY() - key.cloudBaseY());
    }

    private static int rgb(int argb) {
        return argb & 0x00FFFFFF;
    }

    private static float red(int rgb) {
        return ((rgb >>> 16) & 0xFF) / 255.0F;
    }

    private static float green(int rgb) {
        return ((rgb >>> 8) & 0xFF) / 255.0F;
    }

    private static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0F;
    }

    private static double gridPlane(long index, double cellSize) {
        return index * cellSize;
    }

    private interface RectangleEmitter {
        void emit(int u, int v, int width, int height, int color);
    }

    private static final class VertexWriter {
        private float[] vertices;
        private int size;
        private int quadCount;

        private VertexWriter(int capacity) {
            vertices = new float[Math.min(capacity, 1_024)];
        }

        private void top(double x0, double x1, double z0, double z1, double y, float r, float g, float b) {
            quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, r, g, b);
        }

        private void bottom(double x0, double x1, double z0, double z1, double y, float r, float g, float b) {
            quad(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, r, g, b);
        }

        private void west(double x, double z0, double z1, double y0, double y1, float r, float g, float b) {
            quad(x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0, r, g, b);
        }

        private void east(double x, double z0, double z1, double y0, double y1, float r, float g, float b) {
            quad(x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1, r, g, b);
        }

        private void north(double x0, double x1, double z, double y0, double y1, float r, float g, float b) {
            quad(x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z, r, g, b);
        }

        private void south(double x0, double x1, double z, double y0, double y1, float r, float g, float b) {
            quad(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, r, g, b);
        }

        private void quad(double ax, double ay, double az, double bx, double by, double bz,
                          double cx, double cy, double cz, double dx, double dy, double dz,
                          float r, float g, float b) {
            ensure(4 * CloudMesh.FLOATS_PER_VERTEX);
            vertex(ax, ay, az, r, g, b);
            vertex(bx, by, bz, r, g, b);
            vertex(cx, cy, cz, r, g, b);
            vertex(dx, dy, dz, r, g, b);
            quadCount++;
        }

        private void vertex(double x, double y, double z, float r, float g, float b) {
            vertices[size++] = (float) x;
            vertices[size++] = (float) y;
            vertices[size++] = (float) z;
            vertices[size++] = r;
            vertices[size++] = g;
            vertices[size++] = b;
        }

        private void ensure(int addition) {
            int required = size + addition;
            if (required > MAX_FLOATS) {
                throw new IllegalStateException("Cloud mesh exceeded its bounded CPU memory budget");
            }
            if (required > vertices.length) {
                vertices = Arrays.copyOf(vertices, Math.min(MAX_FLOATS, Math.max(required, vertices.length * 2)));
            }
        }

        private float[] finish() {
            return Arrays.copyOf(vertices, size);
        }
    }
}
