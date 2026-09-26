package stellarium.world.ring;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Static, camera-longitude-relative shell mesh for the first curved-ring board renderer.
 *
 * <p>This is deliberately a CPU-only geometry description. It owns neither OpenGL buffers nor
 * the moving sunshade material phase. The four continuous faces cover the closed physical slab;
 * periodic panel holes remain a fragment-material decision, so this mesh never creates a cap at
 * an X subdivision or at the canonical seam.</p>
 */
public final class RingworldBoardMeshGeometry {
    public static final double SEGMENT_LENGTH_METERS = 8_192.0;
    public static final int SEGMENTS_PER_BATCH = 64;
    public static final int MAX_SEGMENTS = 131_072;
    /** Hard cap for a single dynamic panel-wall update; candidates are never silently truncated. */
    public static final int MAX_WALL_QUADS = 131_072;
    public static final int FLOATS_PER_VERTEX = 20;

    public static final int FACE_TOP = 1;
    public static final int FACE_UNDERSIDE = 2;
    public static final int FACE_NEGATIVE_STRIP_SIDE = 3;
    public static final int FACE_PANEL_LEADING_SIDE = 4;
    public static final int FACE_POSITIVE_STRIP_SIDE = 5;
    public static final int FACE_PANEL_TRAILING_SIDE = 6;

    private static final int VERTICES_PER_QUAD = 6;
    private static final int QUADS_PER_SEGMENT = 4;
    private static final int VERTICES_PER_SEGMENT = QUADS_PER_SEGMENT * VERTICES_PER_QUAD;
    private static final RingworldRenderObserver VIRTUAL_OPTICAL_EYE = new RingworldRenderObserver(0.0, 0.0, 0.0);

    private RingworldBoardMeshGeometry() {
    }

    /**
     * Builds a whole closed canonical shell in 8192 m longitudinal pieces.
     *
     * @throws UnsupportedGeometryException when this fixed first-release mesh budget cannot
     *                                      represent the supplied radius
     */
    public static Mesh build(RingworldDisplayGeometry geometry, double baseY, double thickness) {
        Objects.requireNonNull(geometry, "geometry");
        requireFinite(baseY, "baseY");
        requireFinite(thickness, "thickness");
        if (thickness <= 0.0) {
            throw new IllegalArgumentException("thickness must be greater than zero");
        }
        double upperY = baseY + thickness;
        if (!Double.isFinite(upperY) || baseY >= geometry.radiusMeters() || upperY >= geometry.radiusMeters()) {
            throw new IllegalArgumentException("board slab must remain strictly inside the ringworld cylinder");
        }

        double circumference = geometry.circumferenceMeters();
        // Keep s=0 on an 8192 m grid. The canonical endpoints usually fall inside two distinct
        // cells, so this deliberately has two clipped terminal pieces rather than sliding every
        // tile away from the fixed local optical-longitude grid.
        double canonicalMinX = -circumference * 0.5;
        double canonicalMaxX = circumference * 0.5;
        double firstGridCellValue = Math.floor(canonicalMinX / SEGMENT_LENGTH_METERS);
        double afterLastGridCellValue = Math.ceil(canonicalMaxX / SEGMENT_LENGTH_METERS);
        if (!Double.isFinite(firstGridCellValue) || !Double.isFinite(afterLastGridCellValue)
                || firstGridCellValue < Long.MIN_VALUE || afterLastGridCellValue > Long.MAX_VALUE) {
            throw new UnsupportedGeometryException("8192 m board mesh grid indices exceed the supported range");
        }
        long firstGridCell = (long) firstGridCellValue;
        long afterLastGridCell = (long) afterLastGridCellValue;
        long requiredSegments;
        try {
            requiredSegments = Math.subtractExact(afterLastGridCell, firstGridCell);
        } catch (ArithmeticException exception) {
            throw new UnsupportedGeometryException("8192 m board mesh segment count overflows long");
        }
        if (requiredSegments <= 0L || requiredSegments > MAX_SEGMENTS) {
            throw new UnsupportedGeometryException("8192 m board mesh requires " + requiredSegments
                    + " segments, exceeding the first-release limit of " + MAX_SEGMENTS);
        }
        long vertexCount = Math.multiplyExact(requiredSegments, VERTICES_PER_SEGMENT);
        long floatCount = Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX);
        if (floatCount > Integer.MAX_VALUE) {
            throw new UnsupportedGeometryException("board mesh float count exceeds Java array capacity: " + floatCount);
        }

        int segments = Math.toIntExact(requiredSegments);
        float[] packed = new float[(int) floatCount];
        List<Batch> batches = new ArrayList<>((segments + SEGMENTS_PER_BATCH - 1) / SEGMENTS_PER_BATCH);
        int write = 0;

        for (int segmentStart = 0; segmentStart < segments; segmentStart += SEGMENTS_PER_BATCH) {
            int segmentEnd = Math.min(segments, segmentStart + SEGMENTS_PER_BATCH);
            int vertexFirst = write / FLOATS_PER_VERTEX;
            Bounds bounds = new Bounds();
            for (int segment = segmentStart; segment < segmentEnd; segment++) {
                long gridCell = firstGridCell + segment;
                double tileOriginX = gridCell * SEGMENT_LENGTH_METERS;
                double s0 = Math.max(canonicalMinX, tileOriginX);
                double s1 = Math.min(canonicalMaxX, tileOriginX + SEGMENT_LENGTH_METERS);
                if (!(s1 > s0)) {
                    throw new IllegalStateException("canonical board mesh grid cell has no positive length");
                }
                write = emitSegment(packed, write, bounds, geometry, s0, s1, tileOriginX, baseY, upperY);
            }
            int vertexCountForBatch = write / FLOATS_PER_VERTEX - vertexFirst;
            batches.add(bounds.toBatch(vertexFirst, vertexCountForBatch));
        }
        if (write != packed.length) {
            throw new IllegalStateException("board mesh write count mismatch");
        }
        return new Mesh(packed, List.copyOf(batches), segments, canonicalMinX, canonicalMaxX,
                baseY, thickness);
    }

    /**
     * Builds the material leading/trailing walls for one frozen partial sunshade phase.
     *
     * <p>The continuous shell remains fragment-discarded for its periodic holes. These walls are
     * the only geometry that closes those holes vertically. A wall endpoint is embedded by linear
     * interpolation across the corresponding emitted 8192 m shell cell, never by a fresh ideal
     * cylindrical evaluation, so the two pieces share the exact same piecewise-planar edge.</p>
     */
    public static Mesh buildPanelWalls(RingworldDisplayGeometry geometry, double baseY, double thickness,
                                       RingworldSunshade.CameraRelativeBands bands,
                                       double opticalEyeRelativeX, double renderOriginZ) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(bands, "bands");
        requireFinite(baseY, "baseY");
        requireFinite(thickness, "thickness");
        requireFinite(opticalEyeRelativeX, "opticalEyeRelativeX");
        requireFinite(renderOriginZ, "renderOriginZ");
        if (thickness <= 0.0) {
            throw new IllegalArgumentException("thickness must be greater than zero");
        }
        double upperY = baseY + thickness;
        if (!Double.isFinite(upperY) || baseY >= geometry.radiusMeters() || upperY >= geometry.radiusMeters()) {
            throw new IllegalArgumentException("board slab must remain strictly inside the ringworld cylinder");
        }

        double canonicalMinX = -geometry.circumferenceMeters() * 0.5;
        double canonicalMaxX = geometry.circumferenceMeters() * 0.5;
        if (bands.coverage() != RingworldSunshade.BandCoverage.PARTIAL) {
            return Mesh.empty(canonicalMinX, canonicalMaxX, baseY, thickness);
        }

        double headingX = bands.directionX();
        double headingZ = bands.directionZ();
        double headingLength = Math.hypot(headingX, headingZ);
        if (!Double.isFinite(headingLength) || headingLength == 0.0) {
            throw new IllegalArgumentException("partial panel walls require a non-zero heading");
        }
        int orientation = bands.edgeOrientation();
        double edgeInMesh = bands.edgeRelativeToRenderOriginBlocks()
                - headingX * opticalEyeRelativeX + headingZ * renderOriginZ;
        if (!Double.isFinite(edgeInMesh)) {
            throw new IllegalArgumentException("panel edge cannot be represented in mesh coordinates");
        }

        List<WallSpan> spans = new ArrayList<>();
        collectBoundarySpans(spans, canonicalMinX, canonicalMaxX, RingworldStripBounds.BOARD_MIN_Z,
                RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE, headingX, headingZ, edgeInMesh, orientation,
                bands.spacingBlocks(), 0.0, FACE_PANEL_LEADING_SIDE);
        collectBoundarySpans(spans, canonicalMinX, canonicalMaxX, RingworldStripBounds.BOARD_MIN_Z,
                RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE, headingX, headingZ, edgeInMesh, orientation,
                bands.spacingBlocks(), bands.panelWidthBlocks(), FACE_PANEL_TRAILING_SIDE);
        if (spans.isEmpty()) {
            return Mesh.empty(canonicalMinX, canonicalMaxX, baseY, thickness);
        }

        long vertexCount = Math.multiplyExact((long) spans.size(), VERTICES_PER_QUAD);
        long floatCount = Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX);
        if (floatCount > Integer.MAX_VALUE) {
            throw new WallBudgetException("panel wall mesh float count exceeds Java array capacity: " + floatCount);
        }
        float[] packed = new float[(int) floatCount];
        List<Batch> batches = new ArrayList<>((spans.size() + SEGMENTS_PER_BATCH - 1) / SEGMENTS_PER_BATCH);
        int write = 0;
        for (int spanStart = 0; spanStart < spans.size(); spanStart += SEGMENTS_PER_BATCH) {
            int spanEnd = Math.min(spans.size(), spanStart + SEGMENTS_PER_BATCH);
            int vertexFirst = write / FLOATS_PER_VERTEX;
            Bounds bounds = new Bounds();
            for (int span = spanStart; span < spanEnd; span++) {
                write = emitWallQuad(packed, write, bounds, geometry, spans.get(span), canonicalMinX, canonicalMaxX,
                        baseY, upperY);
            }
            batches.add(bounds.toBatch(vertexFirst, write / FLOATS_PER_VERTEX - vertexFirst));
        }
        if (write != packed.length) {
            throw new IllegalStateException("panel wall mesh write count mismatch");
        }
        return new Mesh(packed, List.copyOf(batches), spans.size(), canonicalMinX, canonicalMaxX,
                baseY, thickness);
    }

    private static void collectBoundarySpans(List<WallSpan> spans, double minX, double maxX, double minZ, double maxZ,
                                             double headingX, double headingZ, double edgeInMesh, int orientation,
                                             double spacing, double boundaryOffset, int face) {
        double minProjection = Math.min(headingX * minX, headingX * maxX)
                + Math.min(headingZ * minZ, headingZ * maxZ);
        double maxProjection = Math.max(headingX * minX, headingX * maxX)
                + Math.max(headingZ * minZ, headingZ * maxZ);
        double sequenceMin = orientation > 0
                ? (minProjection - edgeInMesh - boundaryOffset) / spacing
                : (edgeInMesh - boundaryOffset - maxProjection) / spacing;
        double sequenceMax = orientation > 0
                ? (maxProjection - edgeInMesh - boundaryOffset) / spacing
                : (edgeInMesh - boundaryOffset - minProjection) / spacing;
        long first = checkedCeiling(sequenceMin);
        long last = checkedFloor(sequenceMax);
        if (last < first) return;
        long candidateCount;
        try {
            candidateCount = Math.addExact(Math.subtractExact(last, first), 1L);
        } catch (ArithmeticException exception) {
            throw new WallBudgetException("panel wall candidate range overflows long");
        }
        if (candidateCount > MAX_WALL_QUADS) {
            throw new WallBudgetException("panel wall candidate count " + candidateCount
                    + " exceeds limit " + MAX_WALL_QUADS);
        }
        for (long sequence = first; ; sequence++) {
            double q = edgeInMesh + orientation * (sequence * spacing + boundaryOffset);
            if (!Double.isFinite(q)) {
                throw new WallBudgetException("panel wall boundary cannot be represented");
            }
            Line line = clipLineToBoard(q, headingX, headingZ, minX, maxX, minZ, maxZ);
            if (line != null) appendSubdividedLine(spans, line, face);
            if (sequence == last) break;
        }
    }

    private static long checkedCeiling(double value) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
            throw new WallBudgetException("panel wall candidate lower bound is outside long range");
        }
        return (long) Math.ceil(value);
    }

    private static long checkedFloor(double value) {
        if (!Double.isFinite(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
            throw new WallBudgetException("panel wall candidate upper bound is outside long range");
        }
        return (long) Math.floor(value);
    }

    /** Clips hX*s + hZ*z = q using only its largest non-zero heading component as divisor. */
    private static Line clipLineToBoard(double q, double headingX, double headingZ,
                                        double minX, double maxX, double minZ, double maxZ) {
        if (Math.abs(headingX) >= Math.abs(headingZ)) {
            if (headingX == 0.0) return null;
            double intercept = q / headingX;
            double slope = -headingZ / headingX;
            double lowZ = minZ;
            double highZ = maxZ;
            if (slope == 0.0) {
                if (intercept < minX || intercept > maxX) return null;
            } else {
                double atMinX = (minX - intercept) / slope;
                double atMaxX = (maxX - intercept) / slope;
                lowZ = Math.max(lowZ, Math.min(atMinX, atMaxX));
                highZ = Math.min(highZ, Math.max(atMinX, atMaxX));
            }
            if (!(highZ > lowZ)) return null;
            return clippedLine(intercept + slope * lowZ, lowZ, intercept + slope * highZ, highZ,
                    minX, maxX, minZ, maxZ);
        }
        if (headingZ == 0.0) return null;
        double intercept = q / headingZ;
        double slope = -headingX / headingZ;
        double lowX = minX;
        double highX = maxX;
        if (slope == 0.0) {
            if (intercept < minZ || intercept > maxZ) return null;
        } else {
            double atMinZ = (minZ - intercept) / slope;
            double atMaxZ = (maxZ - intercept) / slope;
            lowX = Math.max(lowX, Math.min(atMinZ, atMaxZ));
            highX = Math.min(highX, Math.max(atMinZ, atMaxZ));
        }
        if (!(highX > lowX)) return null;
        return clippedLine(lowX, intercept + slope * lowX, highX, intercept + slope * highX,
                minX, maxX, minZ, maxZ);
    }

    private static Line clippedLine(double x0, double z0, double x1, double z1,
                                    double minX, double maxX, double minZ, double maxZ) {
        // Intersected endpoints can round an ulp beyond their original rectangle.
        return new Line(Math.clamp(x0, minX, maxX), Math.clamp(z0, minZ, maxZ),
                Math.clamp(x1, minX, maxX), Math.clamp(z1, minZ, maxZ));
    }

    private static void appendSubdividedLine(List<WallSpan> spans, Line line, int face) {
        double currentX = line.x0;
        double currentZ = line.z0;
        double endX = line.x1;
        double endZ = line.z1;
        if (endX > currentX) {
            double boundary = (Math.floor(currentX / SEGMENT_LENGTH_METERS) + 1.0) * SEGMENT_LENGTH_METERS;
            while (boundary < endX) {
                double t = (boundary - line.x0) / (line.x1 - line.x0);
                appendWallSpan(spans, currentX, currentZ, boundary, line.z0 + (line.z1 - line.z0) * t, face);
                currentX = boundary;
                currentZ = line.z0 + (line.z1 - line.z0) * t;
                boundary += SEGMENT_LENGTH_METERS;
            }
        } else if (endX < currentX) {
            double boundary = Math.floor(currentX / SEGMENT_LENGTH_METERS) * SEGMENT_LENGTH_METERS;
            if (!(boundary < currentX)) boundary -= SEGMENT_LENGTH_METERS;
            while (boundary > endX) {
                double t = (boundary - line.x0) / (line.x1 - line.x0);
                appendWallSpan(spans, currentX, currentZ, boundary, line.z0 + (line.z1 - line.z0) * t, face);
                currentX = boundary;
                currentZ = line.z0 + (line.z1 - line.z0) * t;
                boundary -= SEGMENT_LENGTH_METERS;
            }
        }
        appendWallSpan(spans, currentX, currentZ, endX, endZ, face);
    }

    private static void appendWallSpan(List<WallSpan> spans, double x0, double z0, double x1, double z1, int face) {
        if (!(Math.hypot(x1 - x0, z1 - z0) > 0.0)) return;
        if (spans.size() >= MAX_WALL_QUADS) {
            throw new WallBudgetException("panel wall quad count exceeds limit " + MAX_WALL_QUADS);
        }
        double midpointX = (x0 + x1) * 0.5;
        long gridCell = (long) Math.floor(midpointX / SEGMENT_LENGTH_METERS);
        spans.add(new WallSpan(x0, z0, x1, z1, gridCell * SEGMENT_LENGTH_METERS, face));
    }

    private static int emitWallQuad(float[] packed, int write, Bounds bounds, RingworldDisplayGeometry geometry,
                                    WallSpan span, double canonicalMinX, double canonicalMaxX,
                                    double baseY, double upperY) {
        Vertex bottom0 = linearShellVertex(geometry, span.x0, baseY, span.z0, span.tileOriginX,
                canonicalMinX, canonicalMaxX, baseY, span.face);
        Vertex bottom1 = linearShellVertex(geometry, span.x1, baseY, span.z1, span.tileOriginX,
                canonicalMinX, canonicalMaxX, baseY, span.face);
        Vertex top1 = linearShellVertex(geometry, span.x1, upperY, span.z1, span.tileOriginX,
                canonicalMinX, canonicalMaxX, baseY, span.face);
        Vertex top0 = linearShellVertex(geometry, span.x0, upperY, span.z0, span.tileOriginX,
                canonicalMinX, canonicalMaxX, baseY, span.face);
        bounds.include(bottom0.display);
        bounds.include(bottom1.display);
        bounds.include(top1.display);
        bounds.include(top0.display);
        write = emitTriangle(packed, write, bottom0, bottom1, top1);
        return emitTriangle(packed, write, bottom0, top1, top0);
    }

    private static Vertex linearShellVertex(RingworldDisplayGeometry geometry, double physicalX, double physicalY,
                                            double physicalZ, double tileOriginX, double canonicalMinX,
                                            double canonicalMaxX, double boardBaseY, int face) {
        double segmentStart = Math.max(canonicalMinX, tileOriginX);
        double segmentEnd = Math.min(canonicalMaxX, tileOriginX + SEGMENT_LENGTH_METERS);
        if (!(segmentEnd > segmentStart) || physicalX < segmentStart || physicalX > segmentEnd) {
            throw new IllegalStateException("panel wall span does not belong to its shell cell");
        }
        double t = (physicalX - segmentStart) / (segmentEnd - segmentStart);
        RingworldDisplayGeometry.Point start = geometry.cameraRelative(VIRTUAL_OPTICAL_EYE,
                new RingworldDisplayGeometry.Point(segmentStart, physicalY, physicalZ));
        RingworldDisplayGeometry.Point end = geometry.cameraRelative(VIRTUAL_OPTICAL_EYE,
                new RingworldDisplayGeometry.Point(segmentEnd, physicalY, physicalZ));
        RingworldDisplayGeometry.Point display = new RingworldDisplayGeometry.Point(
                start.x() + (end.x() - start.x()) * t,
                start.y() + (end.y() - start.y()) * t,
                start.z() + (end.z() - start.z()) * t);
        return new Vertex(display, physicalX - tileOriginX, physicalY - boardBaseY,
                physicalZ - RingworldStripBounds.BOARD_MIN_Z, tileOriginX, face);
    }

    private static int emitSegment(float[] packed, int write, Bounds bounds, RingworldDisplayGeometry geometry,
                                   double s0, double s1, double tileOriginX, double baseY, double upperY) {
        double minZ = RingworldStripBounds.BOARD_MIN_Z;
        double maxZ = RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE;

        // Top and underside are intentionally emitted as separate surfaces. Their winding is
        // consistent over every segment, although the current renderer deliberately disables culling.
        write = emitQuad(packed, write, bounds, geometry, s0, s1, tileOriginX, baseY,
                upperY, upperY, minZ, maxZ, FACE_TOP);
        write = emitQuad(packed, write, bounds, geometry, s0, s1, tileOriginX, baseY,
                baseY, baseY, maxZ, minZ, FACE_UNDERSIDE);

        // The two finite-strip walls use the same physical slab and never manufacture a longitudinal end cap.
        write = emitQuad(packed, write, bounds, geometry, s0, s1, tileOriginX, baseY,
                baseY, upperY, minZ, minZ,
                FACE_NEGATIVE_STRIP_SIDE);
        return emitQuad(packed, write, bounds, geometry, s1, s0, tileOriginX, baseY,
                baseY, upperY, maxZ, maxZ,
                FACE_POSITIVE_STRIP_SIDE);
    }

    /** Emits {@code (s0,y0,z0), (s1,y0,z0), (s1,y1,z1), (s0,y1,z1)} as two triangles. */
    private static int emitQuad(float[] packed, int write, Bounds bounds, RingworldDisplayGeometry geometry,
                                double s0, double s1, double tileOriginX, double boardBaseY,
                                double y0, double y1, double z0, double z1, int face) {
        Vertex a = vertex(geometry, s0, y0, z0, tileOriginX, boardBaseY, face);
        Vertex b = vertex(geometry, s1, y0, z0, tileOriginX, boardBaseY, face);
        Vertex c = vertex(geometry, s1, y1, z1, tileOriginX, boardBaseY, face);
        Vertex d = vertex(geometry, s0, y1, z1, tileOriginX, boardBaseY, face);
        bounds.include(a.display);
        bounds.include(b.display);
        bounds.include(c.display);
        bounds.include(d.display);
        write = emitTriangle(packed, write, a, b, c);
        return emitTriangle(packed, write, a, c, d);
    }

    private static Vertex vertex(RingworldDisplayGeometry geometry, double physicalX, double physicalY,
                                 double physicalZ, double tileOriginX, double boardBaseY, int face) {
        RingworldDisplayGeometry.Point display = geometry.cameraRelative(VIRTUAL_OPTICAL_EYE,
                new RingworldDisplayGeometry.Point(physicalX, physicalY, physicalZ));
        return new Vertex(display, physicalX - tileOriginX, physicalY - boardBaseY,
                physicalZ - RingworldStripBounds.BOARD_MIN_Z,
                tileOriginX, face);
    }

    private static int emitTriangle(float[] packed, int write, Vertex a, Vertex b, Vertex c) {
        Plane plane = Plane.of(a.display, b.display, c.display);
        write = writeVertex(packed, write, a, plane);
        write = writeVertex(packed, write, b, plane);
        return writeVertex(packed, write, c, plane);
    }

    private static int writeVertex(float[] packed, int write, Vertex vertex, Plane plane) {
        write = writeHighLow3(packed, write, vertex.display.x(), vertex.display.y(), vertex.display.z());
        write = writeHighLow4(packed, write, plane.x, plane.y, plane.z, plane.distance);
        packed[write++] = finiteFloat(vertex.localX, "physicalLocal.x");
        packed[write++] = finiteFloat(vertex.localY, "physicalLocal.y");
        packed[write++] = finiteFloat(vertex.localZ, "physicalLocal.z");
        float tileHigh = finiteFloat(vertex.tileOriginX, "tileOriginX");
        packed[write++] = tileHigh;
        packed[write++] = finiteFloat(vertex.tileOriginX - tileHigh, "tileOriginXLow");
        packed[write++] = vertex.face;
        return write;
    }

    private static int writeHighLow3(float[] packed, int write, double x, double y, double z) {
        float highX = finiteFloat(x, "position.x");
        float highY = finiteFloat(y, "position.y");
        float highZ = finiteFloat(z, "position.z");
        packed[write++] = highX;
        packed[write++] = highY;
        packed[write++] = highZ;
        packed[write++] = finiteFloat(x - highX, "position.xLow");
        packed[write++] = finiteFloat(y - highY, "position.yLow");
        packed[write++] = finiteFloat(z - highZ, "position.zLow");
        return write;
    }

    private static int writeHighLow4(float[] packed, int write, double x, double y, double z, double w) {
        float highX = finiteFloat(x, "plane.x");
        float highY = finiteFloat(y, "plane.y");
        float highZ = finiteFloat(z, "plane.z");
        float highW = finiteFloat(w, "plane.distance");
        packed[write++] = highX;
        packed[write++] = highY;
        packed[write++] = highZ;
        packed[write++] = highW;
        packed[write++] = finiteFloat(x - highX, "plane.xLow");
        packed[write++] = finiteFloat(y - highY, "plane.yLow");
        packed[write++] = finiteFloat(z - highZ, "plane.zLow");
        packed[write++] = finiteFloat(w - highW, "plane.distanceLow");
        return write;
    }

    private static float finiteFloat(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
            throw new IllegalArgumentException(name + " cannot be represented by a finite float");
        }
        return (float) value;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /** Immutable packed mesh and batch bounds. */
    public static final class Mesh {
        private final float[] packed;
        private final List<Batch> batches;
        private final int segmentCount;
        private final double canonicalMinX;
        private final double canonicalMaxX;
        private final double baseY;
        private final double thickness;

        private static Mesh empty(double minimum, double maximum, double baseY, double thickness) {
            return new Mesh(new float[0], List.of(), 0, minimum, maximum, baseY, thickness);
        }

        private Mesh(float[] packed, List<Batch> batches, int segmentCount, double canonicalMinX,
                     double canonicalMaxX, double baseY, double thickness) {
            this.packed = packed;
            this.batches = batches;
            this.segmentCount = segmentCount;
            this.canonicalMinX = canonicalMinX;
            this.canonicalMaxX = canonicalMaxX;
            this.baseY = baseY;
            this.thickness = thickness;
        }

        /** Returns an independent packed vertex copy in the documented 20-float layout. */
        public float[] vertexData() {
            return packed.clone();
        }

        /** Copies the packed vertex stream once into an already sized caller-owned upload buffer. */
        public void writeTo(FloatBuffer target) {
            Objects.requireNonNull(target, "target");
            if (target.remaining() < packed.length) {
                throw new IllegalArgumentException("target has insufficient remaining floats for board mesh");
            }
            target.put(packed);
        }

        /** Provides a read-only upload view without exposing mutable backing storage. */
        public FloatBuffer vertexBuffer() {
            return FloatBuffer.wrap(packed).asReadOnlyBuffer();
        }

        public List<Batch> batches() { return batches; }
        public int segmentCount() { return segmentCount; }
        public int vertexCount() { return packed.length / FLOATS_PER_VERTEX; }
        public int floatCount() { return packed.length; }
        public double canonicalMinX() { return canonicalMinX; }
        public double canonicalMaxX() { return canonicalMaxX; }
        public double baseY() { return baseY; }
        public double thickness() { return thickness; }
    }

    /** Immutable vertex range and conservative bounds of its emitted display-space triangles. */
    public record Batch(int vertexFirst, int vertexCount,
                        double minDisplayX, double minDisplayY, double minDisplayZ,
                        double maxDisplayX, double maxDisplayY, double maxDisplayZ) {
        public Batch {
            if (vertexFirst < 0 || vertexCount <= 0
                    || !Double.isFinite(minDisplayX) || !Double.isFinite(minDisplayY) || !Double.isFinite(minDisplayZ)
                    || !Double.isFinite(maxDisplayX) || !Double.isFinite(maxDisplayY) || !Double.isFinite(maxDisplayZ)
                    || minDisplayX > maxDisplayX || minDisplayY > maxDisplayY || minDisplayZ > maxDisplayZ) {
                throw new IllegalArgumentException("invalid board mesh batch");
            }
        }
    }

    /** Signals that the fixed first-release mesh budget does not cover a requested radius. */
    public static final class UnsupportedGeometryException extends IllegalArgumentException {
        public UnsupportedGeometryException(String message) {
            super(message);
        }
    }

    public static final class WallBudgetException extends IllegalArgumentException {
        public WallBudgetException(String message) { super(message); }
    }

    private record Line(double x0, double z0, double x1, double z1) { }
    private record WallSpan(double x0, double z0, double x1, double z1, double tileOriginX, int face) { }

    private record Vertex(RingworldDisplayGeometry.Point display, double localX, double localY,
                          double localZ, double tileOriginX, int face) {
    }

    private static final class Plane {
        private final double x;
        private final double y;
        private final double z;
        private final double distance;

        private Plane(double x, double y, double z, double distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
        }

        private static Plane of(RingworldDisplayGeometry.Point a, RingworldDisplayGeometry.Point b,
                                RingworldDisplayGeometry.Point c) {
            double abX = b.x() - a.x();
            double abY = b.y() - a.y();
            double abZ = b.z() - a.z();
            double acX = c.x() - a.x();
            double acY = c.y() - a.y();
            double acZ = c.z() - a.z();
            double normalX = abY * acZ - abZ * acY;
            double normalY = abZ * acX - abX * acZ;
            double normalZ = abX * acY - abY * acX;
            double length = Math.hypot(Math.hypot(normalX, normalY), normalZ);
            if (!Double.isFinite(length) || length == 0.0) {
                throw new IllegalStateException("board mesh emitted a degenerate triangle");
            }
            normalX /= length;
            normalY /= length;
            normalZ /= length;
            double distance = normalX * a.x() + normalY * a.y() + normalZ * a.z();
            if (!Double.isFinite(distance)) {
                throw new IllegalStateException("board mesh plane distance is not finite");
            }
            return new Plane(normalX, normalY, normalZ, distance);
        }
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void include(RingworldDisplayGeometry.Point point) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }

        private Batch toBatch(int vertexFirst, int vertexCount) {
            return new Batch(vertexFirst, vertexCount, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
