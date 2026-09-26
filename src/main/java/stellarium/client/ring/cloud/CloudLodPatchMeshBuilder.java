package stellarium.client.ring.cloud;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * CPU-only cached-page mesh builder for the cloud LOD hierarchy.
 *
 * <p>It reads only immutable {@link CloudWorldCache.Page} material cells.  LOD0--3 retain actual
 * exposed voxel faces; LOD4--12 become one cull-disabled cloud sheet.  Geometry is embedded directly in
 * the ring cylinder, so every emitted triangle owns the plane of its three encoded points.  A
 * renderer may apply its current rigid camera/wind pose without regenerating this page mesh.</p>
 */
public final class CloudLodPatchMeshBuilder {
    public static final int FLOATS_PER_QUAD = 37;
    public static final long MAX_MESH_BYTES = 64L * 1024L * 1024L;
    public static final double MAX_CURVED_SPAN_METERS = 8_192.0D;
    public static final int QUADS_PER_BATCH = 1_024;
    private static final int NO_FACE = -1;

    private CloudLodPatchMeshBuilder() {
    }

    public static PatchMesh build(CloudWorldCache.Page page, CloudGeometrySettings geometry, double cloudBaseY,
                                  CloudClipBounds clipBounds, double ringRadiusMeters) {
        return build(page, geometry, cloudBaseY, clipBounds, ringRadiusMeters, false);
    }

    static PatchMesh build(CloudWorldCache.Page page, CloudGeometrySettings geometry, double cloudBaseY,
                           CloudClipBounds clipBounds, double ringRadiusMeters, boolean physicalCoordinates) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(clipBounds, "clipBounds");
        if (!Double.isFinite(cloudBaseY) || !Double.isFinite(ringRadiusMeters) || ringRadiusMeters <= 0.0D) {
            throw new IllegalArgumentException("Cloud patch base and ring radius must be finite, with positive radius");
        }
        CloudLodLayout.AtlasLevel level = page.level();
        double cellWidth = geometry.cellSizeBlocks() * level.xzScale();
        double cellHeight = geometry.thicknessBlocks() * level.yScale();
        if (!Double.isFinite(cellWidth) || !Double.isFinite(cellHeight) || cellWidth <= 0.0D || cellHeight <= 0.0D) {
            throw new IllegalArgumentException("Cloud patch cell dimensions are invalid");
        }
        // Local-series and tangent bounds are admitted only in their proven radius domain.
        Writer writer = new Writer(ringRadiusMeters, physicalCoordinates && ringRadiusMeters >= 8_192_000.0);
        double pageMinZ=page.originZ()*cellWidth;
        double pageMaxZ=(page.originZ()+level.depth())*cellWidth;
        if (pageMaxZ<=clipBounds.minWorldZ() || pageMinZ>=clipBounds.maxWorldZ()
                || cloudBaseY>=clipBounds.upperY() || cloudBaseY+level.layers()*cellHeight<=clipBounds.lowerY()) {
            return writer.finish(page,geometry,cloudBaseY,clipBounds);
        }
        if (level.isThreeDimensional()) {
            buildVoxelShell(page, cellWidth, cellHeight, cloudBaseY, clipBounds, writer);
        } else {
            buildSheet(page, cellWidth, cellHeight, cloudBaseY, clipBounds, writer);
        }
        return writer.finish(page, geometry, cloudBaseY, clipBounds);
    }

    private static void buildVoxelShell(CloudWorldCache.Page page, double cellWidth, double cellHeight,
                                        double baseY, CloudClipBounds clip, Writer writer) {
        CloudLodLayout.AtlasLevel level = page.level();
        int width = level.width(), depth = level.depth(), layers = level.layers();
        int[] faces = new int[width * depth];
        for (int layer = 0; layer < layers; layer++) {
            final int faceLayer = layer;
            for (int z = 0; z < depth; z++) {
                checkInterrupted("voxel top/bottom row");
                long worldZ = page.originZ() + z;
                for (int x = 0; x < width; x++) {
                    long worldX = page.originX() + x;
                    int color = clippedOpaque(page, layer, worldX, worldZ, cellWidth, clip);
                    faces[z * width + x] = color != 0 && intersectsClip(layer, baseY, cellHeight, clip)
                            && topExposed(page, layer, worldX, worldZ, baseY, cellHeight, clip)
                            ? 1 : NO_FACE;
                }
            }
            greedy(faces, width, depth, (x, z, runX, runZ, color) -> writer.horizontal(page, cellWidth, baseY,
                    cellHeight, faceLayer, x, z, runX, runZ, color, true, false, clip));
            for (int z = 0; z < depth; z++) {
                checkInterrupted("voxel top/bottom row");
                long worldZ = page.originZ() + z;
                for (int x = 0; x < width; x++) {
                    long worldX = page.originX() + x;
                    int color = clippedOpaque(page, layer, worldX, worldZ, cellWidth, clip);
                    faces[z * width + x] = color != 0 && intersectsClip(layer, baseY, cellHeight, clip)
                            && bottomExposed(page, layer, worldX, worldZ, baseY, cellHeight, clip)
                            ? 1 : NO_FACE;
                }
            }
            greedy(faces, width, depth, (x, z, runX, runZ, color) -> writer.horizontal(page, cellWidth, baseY,
                    cellHeight, faceLayer, x, z, runX, runZ, color, false, false, clip));
        }

        // Each X plane owns a z-by-layer face grid.  Exact colors may merge, but a physical cell
        // boundary never disappears unless both cells contain the same solid cloud volume.
        int[] sideFaces = new int[depth * layers];
        for (int x = 0; x < width; x++) {
            final int faceX = x;
            checkInterrupted("voxel X row");
            long worldX = page.originX() + x;
            for (int layer = 0; layer < layers; layer++) for (int z = 0; z < depth; z++) {
                long worldZ = page.originZ() + z;
                int color = clippedOpaque(page, layer, worldX, worldZ, cellWidth, clip);
                sideFaces[layer * depth + z] = color != 0 && !opaqueAt(page, layer, worldX - 1L, worldZ)
                        ? 1 : NO_FACE;
            }
            greedy(sideFaces, depth, layers, (z, layer, runZ, runLayer, color) -> writer.xSide(page, cellWidth,
                    cellHeight, baseY, faceX, z, layer, runZ, runLayer, color, false, clip));
            for (int layer = 0; layer < layers; layer++) for (int z = 0; z < depth; z++) {
                long worldZ = page.originZ() + z;
                int color = clippedOpaque(page, layer, worldX, worldZ, cellWidth, clip);
                sideFaces[layer * depth + z] = color != 0 && !opaqueAt(page, layer, worldX + 1L, worldZ)
                        ? 1 : NO_FACE;
            }
            greedy(sideFaces, depth, layers, (z, layer, runZ, runLayer, color) -> writer.xSide(page, cellWidth,
                    cellHeight, baseY, faceX + 1, z, layer, runZ, runLayer, color, true, clip));
        }

        int[] zFaces = new int[width * layers];
        for (int z = 0; z < depth; z++) {
            final int faceZ = z;
            checkInterrupted("voxel Z row");
            long worldZ = page.originZ() + z;
            for (int layer = 0; layer < layers; layer++) for (int x = 0; x < width; x++) {
                long worldX = page.originX() + x;
                int color = clippedOpaque(page, layer, worldX, worldZ, cellWidth, clip);
                zFaces[layer * width + x] = color != 0 && (!intersectsZ(worldZ - 1L,cellWidth,clip)
                        || !opaqueAt(page, layer, worldX, worldZ - 1L))
                        ? 1 : NO_FACE;
            }
            greedy(zFaces, width, layers, (x, layer, runX, runLayer, color) -> writer.zSide(page, cellWidth,
                    cellHeight, baseY, faceZ, x, layer, runX, runLayer, color, false, clip));
            for (int layer = 0; layer < layers; layer++) for (int x = 0; x < width; x++) {
                long worldX = page.originX() + x;
                int color = clippedOpaque(page, layer, worldX, worldZ, cellWidth, clip);
                zFaces[layer * width + x] = color != 0 && (!intersectsZ(worldZ + 1L,cellWidth,clip)
                        || !opaqueAt(page, layer, worldX, worldZ + 1L))
                        ? 1 : NO_FACE;
            }
            greedy(zFaces, width, layers, (x, layer, runX, runLayer, color) -> writer.zSide(page, cellWidth,
                    cellHeight, baseY, faceZ + 1, x, layer, runX, runLayer, color, true, clip));
        }
    }

    private static void buildSheet(CloudWorldCache.Page page, double cellWidth, double cellHeight, double baseY,
                                   CloudClipBounds clip, Writer writer) {
        int width = page.level().width(), depth = page.level().depth();
        checkInterrupted("two-dimensional sheet");
        double midY = Math.max(clip.lowerY(), Math.min(clip.upperY(), baseY + cellHeight * 0.5D));
        // The sheet is deliberately full: the renderer samples this page's alpha/RGB atlas at
        // its physical hit. Empty local cells therefore leave room for the independent global
        // far-cloud field instead of opening camera-window-shaped holes in the handoff.
        writer.horizontal(page, cellWidth, midY, 0.0D, 0, 0, 0, width, depth,
                0xFFFFFFFF, true, true, clip);
    }

    private static boolean topExposed(CloudWorldCache.Page page, int layer, long x, long z,
                                      double baseY, double height, CloudClipBounds clip) {
        double rawTop = baseY + (layer + 1.0D) * height;
        return rawTop >= clip.upperY() || !opaqueAt(page, layer + 1, x, z);
    }

    private static boolean bottomExposed(CloudWorldCache.Page page, int layer, long x, long z,
                                         double baseY, double height, CloudClipBounds clip) {
        double rawBottom = baseY + layer * height;
        return rawBottom <= clip.lowerY() || !opaqueAt(page, layer - 1, x, z);
    }

    private static boolean intersectsClip(int layer, double baseY, double height, CloudClipBounds clip) {
        return baseY + (layer + 1.0D) * height > clip.lowerY()
                && baseY + layer * height < clip.upperY();
    }

    private static boolean intersectsZ(long cellZ, double cellWidth, CloudClipBounds clip) {
        return (cellZ+1L)*cellWidth>clip.minWorldZ() && cellZ*cellWidth<clip.maxWorldZ();
    }

    private static int clippedOpaque(CloudWorldCache.Page page, int layer, long x, long z,
                                     double cellWidth, CloudClipBounds clip) {
        if(!intersectsZ(z,cellWidth,clip)) return 0;
        int argb = page.argbAt(layer, x, z);
        // Occupancy callers test against zero; NO_FACE belongs only to the greedy-face scratch grid.
        return (argb >>> 24 & 255) >= 128 ? argb : 0;
    }

    private static boolean opaqueAt(CloudWorldCache.Page page, int layer, long x, long z) {
        return layer >= 0 && layer < page.level().layers() && page.containsCell(x, z)
                && (page.argbAt(layer, x, z) >>> 24 & 255) >= 128;
    }

    private static void greedy(int[] faces, int width, int height, RectangleEmitter emitter) {
        for (int row = 0; row < height; row++) {
            checkInterrupted("greedy face row");
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                int color = faces[index];
                if (color == NO_FACE) continue;
                int runX = 1;
                while (column + runX < width && faces[index + runX] == color) runX++;
                int runY = 1;
                outer: while (row + runY < height) {
                    int next = (row + runY) * width + column;
                    for (int dx = 0; dx < runX; dx++) if (faces[next + dx] != color) break outer;
                    runY++;
                }
                for (int dy = 0; dy < runY; dy++) Arrays.fill(faces, (row + dy) * width + column,
                        (row + dy) * width + column + runX, NO_FACE);
                emitter.emit(column, row, runX, runY, color);
            }
        }
    }

    private static void checkInterrupted(String where) {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Cloud LOD patch generation interrupted during " + where);
        }
    }

    @FunctionalInterface private interface RectangleEmitter {
        void emit(int x, int y, int width, int height, int argb);
    }

    public record Aabb(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        public Aabb {
            if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) throw new IllegalArgumentException("Invalid patch AABB");
        }
    }

    public record Batch(int firstQuad, int quadCount, Aabb bounds) {
        public Batch { Objects.requireNonNull(bounds, "bounds"); }
    }

    /** Immutable instanced-quads page mesh; expansion is {@code 0,1,2,0,2,3} per quad. */
    public static final class PatchMesh {
        private final CloudLodLayout.AtlasLevel level;
        private final float[] quads;
        private final List<Batch> batches;
        private final boolean physicalCoordinates;
        private PatchMesh(CloudLodLayout.AtlasLevel level, float[] quads, List<Batch> batches, boolean physicalCoordinates) {
            // Private constructor takes the writer's newly trimmed array; no mutable alias escapes.
            this.level = level; this.quads = quads; this.batches = List.copyOf(batches);
            this.physicalCoordinates = physicalCoordinates;
        }
        public boolean physicalCoordinates() { return physicalCoordinates; }
        public CloudLodLayout.AtlasLevel level() { return level; }
        public int quadCount() { return quads.length / FLOATS_PER_QUAD; }
        /** Logical expanded vertex count; this is not the uploaded instance payload count. */
        public int vertexCount() { return Math.multiplyExact(quadCount(), 6); }
        /** Four strip vertices produce the same two triangles and shared diagonal. */
        public int submittedVertexCount() { return Math.multiplyExact(quadCount(), 4); }
        public int floatCount() { return quads.length; }
        public int byteCount() { return Math.multiplyExact(quads.length, Float.BYTES); }
        public int batchCount() { return batches.size(); }
        public Batch batch(int index) { return batches.get(index); }
        public float[] copyQuadData() { return quads.clone(); }
        public FloatBuffer quadBuffer() { return FloatBuffer.wrap(quads).asReadOnlyBuffer(); }
        /** Writes only the compact instance stream; no expanded duplicate triangle array exists. */
        public void writeTo(FloatBuffer target) {
            Objects.requireNonNull(target, "target");
            if (target.remaining() < quads.length) throw new IllegalArgumentException("Target lacks cloud patch instance capacity");
            target.put(quads);
        }
        /** Component 0..5 is highXYZ then lowXYZ for one actual quad corner. */
        public float cornerComponent(int quad, int corner, int component) {
            if (quad < 0 || quad >= quadCount() || corner < 0 || corner >= 4 || component < 0 || component >= 6) {
                throw new IndexOutOfBoundsException();
            }
            return quads[quad * FLOATS_PER_QUAD + corner * 6 + component];
        }
        public float planeComponent(int quad, int component) {
            if (quad < 0 || quad >= quadCount() || component < 0 || component >= 8) throw new IndexOutOfBoundsException();
            return quads[quad * FLOATS_PER_QUAD + 24 + component];
        }
        public float physicalNormalComponent(int quad, int component) {
            if (quad < 0 || quad >= quadCount() || component < 0 || component >= 3) throw new IndexOutOfBoundsException();
            float axis = quads[quad * FLOATS_PER_QUAD + 32];
            return Math.abs(axis) == component + 1 ? Math.signum(axis) : 0.0F;
        }
        /** Unwrapped physical longitude at the chord midpoint; cached independently of camera/wind. */
        public double materialCenterX(int quad) {
            if (quad < 0 || quad >= quadCount()) throw new IndexOutOfBoundsException();
            int offset = quad * FLOATS_PER_QUAD + 33;
            return (double) quads[offset] + quads[offset + 1];
        }
        /** Exact physical coordinate on the face's normal axis, before cylindrical embedding. */
        public double physicalAnchor(int quad) {
            if (quad < 0 || quad >= quadCount()) throw new IndexOutOfBoundsException();
            int offset = quad * FLOATS_PER_QUAD + 35;
            return (double) quads[offset] + quads[offset + 1];
        }
    }

    private static final class Writer {
        private final double ringRadius;
        private float[] data = new float[4_096];
        private int size;
        private final List<Batch> batches = new ArrayList<>();
        private int batchFirst;
        private Bounds bounds = new Bounds();

        private final boolean physicalCoordinates;
        private Writer(double ringRadius, boolean physicalCoordinates) {
            this.ringRadius = ringRadius;
            this.physicalCoordinates = physicalCoordinates;
        }

        private void horizontal(CloudWorldCache.Page page, double cellWidth, double baseY, double cellHeight, int layer,
                                int localX, int localZ, int runX, int runZ, int argb, boolean top, boolean sheet,
                                CloudClipBounds clip) {
            double x0 = (page.originX() + localX) * cellWidth;
            double x1 = (page.originX() + localX + runX) * cellWidth;
            double z0 = Math.max(clip.minWorldZ(),(page.originZ() + localZ) * cellWidth);
            double z1 = Math.min(clip.maxWorldZ(),(page.originZ() + localZ + runZ) * cellWidth);
            if (!(z1>z0)) return;
            double rawY = sheet ? baseY : (top ? baseY + (layer + 1.0D) * cellHeight : baseY + layer * cellHeight);
            double y = Math.max(clip.lowerY(), Math.min(clip.upperY(), rawY));
            double maximumSpan = sheet ? MAX_CURVED_SPAN_METERS
                    : Math.min(MAX_CURVED_SPAN_METERS, cellWidth * 16.0D);
            for (double start = x0; start < x1;) {
                double end = Math.min(x1, start + maximumSpan);
                Point a = point(start, y, z0), b = point(start, y, z1), c = point(end, y, z1), d = point(end, y, z0);
                if (top) quad(a, b, c, d, argb, 0.0F, 1.0F, 0.0F); else quad(d, c, b, a, argb, 0.0F, -1.0F, 0.0F);
                start = end;
            }
        }

        private void xSide(CloudWorldCache.Page page, double cellWidth, double cellHeight, double baseY, int localX,
                           int localZ, int layer, int runZ, int runLayer, int argb, boolean east, CloudClipBounds clip) {
            double y0 = Math.max(clip.lowerY(), baseY + layer * cellHeight);
            double y1 = Math.min(clip.upperY(), baseY + (layer + runLayer) * cellHeight);
            if (!(y1 > y0)) return;
            double x = (page.originX() + localX) * cellWidth;
            double z0 = Math.max(clip.minWorldZ(),(page.originZ() + localZ) * cellWidth);
            double z1 = Math.min(clip.maxWorldZ(),(page.originZ() + localZ + runZ) * cellWidth);
            if (!(z1>z0)) return;
            Point a = point(x, y0, z0), b = point(x, y1, z0), c = point(x, y1, z1), d = point(x, y0, z1);
            if (east) quad(a, b, c, d, argb, 1.0F, 0.0F, 0.0F); else quad(d, c, b, a, argb, -1.0F, 0.0F, 0.0F);
        }

        private void zSide(CloudWorldCache.Page page, double cellWidth, double cellHeight, double baseY, int localZ,
                           int localX, int layer, int runX, int runLayer, int argb, boolean south, CloudClipBounds clip) {
            double y0 = Math.max(clip.lowerY(), baseY + layer * cellHeight);
            double y1 = Math.min(clip.upperY(), baseY + (layer + runLayer) * cellHeight);
            if (!(y1 > y0)) return;
            double x0 = (page.originX() + localX) * cellWidth, x1 = (page.originX() + localX + runX) * cellWidth;
            // A clipped neighbour is empty even when its original atlas cell is occupied.
            // Move that boundary face onto the exact physical cut, including cuts inside a cell.
            double z = Math.clamp((page.originZ() + localZ) * cellWidth,clip.minWorldZ(),clip.maxWorldZ());
            double maximumSpan = Math.min(MAX_CURVED_SPAN_METERS, cellWidth * 16.0D);
            for (double start = x0; start < x1;) {
                double end = Math.min(x1, start + maximumSpan);
                Point a = point(start, y0, z), b = point(start, y1, z), c = point(end, y1, z), d = point(end, y0, z);
                if (south) quad(a, b, c, d, argb, 0.0F, 0.0F, 1.0F); else quad(d, c, b, a, argb, 0.0F, 0.0F, -1.0F);
                start = end;
            }
        }

        private Point point(double worldX, double worldY, double worldZ) {
            if (!Double.isFinite(worldX) || !Double.isFinite(worldY) || !Double.isFinite(worldZ) || worldY >= ringRadius) {
                throw new IllegalArgumentException("Cloud patch point is outside the representable ring cylinder");
            }
            double angle = worldX / ringRadius;
            if (physicalCoordinates) return new Point(worldX, worldY, worldZ, worldX, worldY);
            double radial = ringRadius - worldY;
            return new Point(radial * Math.sin(angle), ringRadius - radial * Math.cos(angle), worldZ, worldX, worldY);
        }

        private void quad(Point a, Point b, Point c, Point d, int argb, float physicalNx, float physicalNy, float physicalNz) {
            double abX = b.x - a.x, abY = b.y - a.y, abZ = b.z - a.z;
            double acX = c.x - a.x, acY = c.y - a.y, acZ = c.z - a.z;
            double nx = abY * acZ - abZ * acY, ny = abZ * acX - abX * acZ, nz = abX * acY - abY * acX;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!Double.isFinite(length) || length == 0.0D) throw new IllegalStateException("Cloud patch triangle is degenerate");
            nx /= length; ny /= length; nz /= length;
            double planeOffset = -(nx * a.x + ny * a.y + nz * a.z);
            ensure(FLOATS_PER_QUAD);
            writeCorner(a); writeCorner(b); writeCorner(c); writeCorner(d);
            writeSplit4(nx, ny, nz, planeOffset);
            // All physical normals are signed axes. Packing one scalar frees two existing
            // floats for the material chart without increasing the page/collection budget.
            data[size++] = physicalNx != 0.0F ? physicalNx : physicalNy != 0.0F ? physicalNy * 2.0F : physicalNz * 3.0F;
            double centerX = a.worldX + (d.worldX - a.worldX) * 0.5D;
            float centerHigh = finiteFloat(centerX, "physical cloud material center");
            data[size++] = centerHigh;
            data[size++] = finiteFloat(centerX - centerHigh, "physical cloud material center residual");
            double anchor = physicalNx != 0.0F ? a.worldX : physicalNy != 0.0F ? a.worldY : a.z;
            float anchorHigh = finiteFloat(anchor, "physical cloud face anchor");
            data[size++] = anchorHigh;
            data[size++] = finiteFloat(anchor - anchorHigh, "physical cloud face anchor residual");
            bounds.include(a); bounds.include(b); bounds.include(c); bounds.include(d);
            int quadsInBatch = size / FLOATS_PER_QUAD - batchFirst;
            if (quadsInBatch == QUADS_PER_BATCH) flushBatch();
        }

        private void writeCorner(Point point) {
            writeSplit3(point.x, point.y, point.z);
        }

        private void writeSplit3(double x, double y, double z) {
            float hx = finiteFloat(x, "encoded cloud patch value");
            float hy = finiteFloat(y, "encoded cloud patch value");
            float hz = finiteFloat(z, "encoded cloud patch value");
            data[size++] = hx; data[size++] = hy; data[size++] = hz;
            data[size++] = finiteFloat(x - hx, "encoded cloud patch residual");
            data[size++] = finiteFloat(y - hy, "encoded cloud patch residual");
            data[size++] = finiteFloat(z - hz, "encoded cloud patch residual");
        }

        private void writeSplit4(double x, double y, double z, double w) {
            float hx = finiteFloat(x, "encoded cloud patch plane");
            float hy = finiteFloat(y, "encoded cloud patch plane");
            float hz = finiteFloat(z, "encoded cloud patch plane");
            float hw = finiteFloat(w, "encoded cloud patch plane");
            data[size++] = hx; data[size++] = hy; data[size++] = hz; data[size++] = hw;
            data[size++] = finiteFloat(x - hx, "encoded cloud patch plane residual");
            data[size++] = finiteFloat(y - hy, "encoded cloud patch plane residual");
            data[size++] = finiteFloat(z - hz, "encoded cloud patch plane residual");
            data[size++] = finiteFloat(w - hw, "encoded cloud patch plane residual");
        }

        private void ensure(int additional) {
            if ((long) (size + additional) * Float.BYTES > MAX_MESH_BYTES) {
                throw new MeshBudgetException("Cloud LOD patch exceeds the explicit 64 MiB CPU mesh budget");
            }
            if (size + additional <= data.length) return;
            int next = Math.max(data.length * 2, size + additional);
            if ((long) next * Float.BYTES > MAX_MESH_BYTES) next = (int) (MAX_MESH_BYTES / Float.BYTES);
            data = Arrays.copyOf(data, next);
        }

        private void flushBatch() {
            int quads = size / FLOATS_PER_QUAD - batchFirst;
            if (quads != 0) batches.add(new Batch(batchFirst, quads, bounds.finish()));
            batchFirst += quads;
            bounds = new Bounds();
        }

        private PatchMesh finish(CloudWorldCache.Page page, CloudGeometrySettings geometry, double baseY,
                                 CloudClipBounds clipBounds) {
            flushBatch();
            if (physicalCoordinates && size > 0) {
                // A slab should not re-submit a page-wide batch merely because one
                // quad touches it. Sort the compact payload in place, once per build.
                sortByLongitude(0, size / FLOATS_PER_QUAD - 1);
                batches.clear();
                int count = size / FLOATS_PER_QUAD;
                for (int first = 0; first < count; first += QUADS_PER_BATCH) {
                    Bounds spatial = new Bounds();
                    int end = Math.min(count, first + QUADS_PER_BATCH);
                    for (int q = first; q < end; q++) for (int corner = 0; corner < 4; corner++) {
                        int offset = q * FLOATS_PER_QUAD + corner * 6;
                        spatial.include(new Point((double)data[offset] + data[offset + 3],
                                (double)data[offset + 1] + data[offset + 4],
                                (double)data[offset + 2] + data[offset + 5], 0.0, 0.0));
                    }
                    batches.add(new Batch(first, end - first, spatial.finish()));
                }
            }
            return new PatchMesh(page.level(), Arrays.copyOf(data, size), batches, physicalCoordinates);
        }

        private double longitude(int quad) {
            int offset = quad * FLOATS_PER_QUAD + 33;
            return (double)data[offset] + data[offset + 1];
        }

        private void sortByLongitude(int first, int last) {
            checkInterrupted("physical cloud spatial ordering");
            int left = first, right = last;
            double pivot = longitude(first + (last - first) / 2);
            while (left <= right) {
                while (longitude(left) < pivot) left++;
                while (longitude(right) > pivot) right--;
                if (left <= right) {
                    for (int component = 0; component < FLOATS_PER_QUAD; component++) {
                        int a = left * FLOATS_PER_QUAD + component, b = right * FLOATS_PER_QUAD + component;
                        float value = data[a]; data[a] = data[b]; data[b] = value;
                    }
                    left++; right--;
                }
            }
            if (first < right) sortByLongitude(first, right);
            if (left < last) sortByLongitude(left, last);
        }
    }

    /** Stored point is already in global cylindrical display coordinates. */
    private record Point(double x, double y, double z, double worldX, double worldY) { }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        private void include(Point point) { minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x); minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y); minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z); }
        private Aabb finish() { return new Aabb(Math.nextDown(minX), Math.nextUp(maxX), Math.nextDown(minY), Math.nextUp(maxY), Math.nextDown(minZ), Math.nextUp(maxZ)); }
    }

    private static float finiteFloat(double value, String name) {
        float result = (float) value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException(name + " is outside finite float range");
        return result;
    }

    public static final class MeshBudgetException extends IllegalStateException {
        public MeshBudgetException(String message) { super(message); }
    }
}
