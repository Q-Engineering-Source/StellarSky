package stellarium.world.ring;

import java.nio.FloatBuffer;
import java.util.Objects;

/**
 * Immutable, globally anchored cylindrical strip for the first procedural
 * ring preview. It is display geometry only: it creates no terrain, chunks,
 * collisions, light, GL objects, or near/far handoff policy.
 */
public final class ProceduralRingModelGeometry {
    public static final int DEFAULT_ANGULAR_SEGMENTS = 4_096;
    public static final int MIN_ANGULAR_SEGMENTS = 32;
    public static final int MAX_ANGULAR_SEGMENTS = 131_072;
    public static final int FLOATS_PER_VERTEX = 16;
    private static final int SEGMENTS_PER_BATCH = 64;

    private final double radiusMeters;
    private final double surfaceY;
    private final double minZ;
    private final double maxZ;
    private final int angularSegments;
    private final Mesh mesh;

    public ProceduralRingModelGeometry(double radiusMeters, double surfaceY, double minZ, double maxZ) {
        this(radiusMeters, surfaceY, minZ, maxZ, DEFAULT_ANGULAR_SEGMENTS);
    }

    public ProceduralRingModelGeometry(double radiusMeters, double surfaceY, double minZ, double maxZ,
                                       int angularSegments) {
        requireFinite(radiusMeters, "radiusMeters");
        requireFinite(surfaceY, "surfaceY");
        requireFinite(minZ, "minZ");
        requireFinite(maxZ, "maxZ");
        if (radiusMeters <= 0.0 || surfaceY >= radiusMeters || !(maxZ > minZ)) {
            throw new IllegalArgumentException("Ring strip requires positive radius, interior surfaceY, and minZ < maxZ");
        }
        if (angularSegments < MIN_ANGULAR_SEGMENTS || angularSegments > MAX_ANGULAR_SEGMENTS) {
            throw new IllegalArgumentException("angularSegments must be within [" + MIN_ANGULAR_SEGMENTS
                    + ", " + MAX_ANGULAR_SEGMENTS + ']');
        }
        this.radiusMeters = radiusMeters;
        this.surfaceY = surfaceY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.angularSegments = angularSegments;
        mesh = buildMesh();
    }

    public double radiusMeters() { return radiusMeters; }
    public double surfaceY() { return surfaceY; }
    public double minZ() { return minZ; }
    public double maxZ() { return maxZ; }
    public int angularSegments() { return angularSegments; }
    public Mesh mesh() { return mesh; }

    /** Keep distant cloud-shell chord error bounded as the configured ring radius grows. */
    public static int cloudSegments(double radius) {
        requireFinite(radius, "radius");
        if (radius <= 0.0D) throw new IllegalArgumentException("Radius must be positive");
        int segments=DEFAULT_ANGULAR_SEGMENTS;
        while (segments<MAX_ANGULAR_SEGMENTS && 2.0D*radius*Math.pow(Math.sin(Math.PI/(2.0D*segments)),2)>64.0D) segments*=2;
        return segments;
    }

    /**
     * Produces the per-frame tangent-basis pose without rebuilding global mesh
     * vertices. {@code cameraRelativeX} is supplied by the render integration.
     */
    public Pose pose(double opticalEyeX, double renderOriginY, double renderOriginZ, double cameraRelativeX) {
        requireFinite(opticalEyeX, "opticalEyeX");
        requireFinite(renderOriginY, "renderOriginY");
        requireFinite(renderOriginZ, "renderOriginZ");
        requireFinite(cameraRelativeX, "cameraRelativeX");
        double phi = opticalEyeX / radiusMeters;
        if (!Double.isFinite(phi)) throw new IllegalArgumentException("optical eye angle is not finite");
        return new Pose(Math.cos(phi), Math.sin(phi), cameraRelativeX, -renderOriginY, -renderOriginZ, radiusMeters);
    }

    private Mesh buildMesh() {
        int vertexCount = Math.multiplyExact(angularSegments, 6);
        float[] data = new float[Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX)];
        Batch[] batches = new Batch[(angularSegments + SEGMENTS_PER_BATCH - 1) / SEGMENTS_PER_BATCH];
        int cursor = 0;
        int batchIndex = 0;
        for (int start = 0; start < angularSegments; start += SEGMENTS_PER_BATCH) {
            int count = Math.min(SEGMENTS_PER_BATCH, angularSegments - start);
            Bounds bounds = new Bounds();
            for (int segment = start; segment < start + count; segment++) {
                double theta0 = -Math.PI + (2.0 * Math.PI * segment) / angularSegments;
                double theta1 = -Math.PI + (2.0 * Math.PI * (segment + 1)) / angularSegments;
                Point a = point(theta0, minZ);
                Point b = point(theta1, minZ);
                Point c = point(theta1, maxZ);
                Point d = point(theta0, maxZ);
                double mid = (theta0 + theta1) * 0.5;
                Plane plane = plane(mid, a);
                double u0 = (double) segment / angularSegments;
                double u1 = (double) (segment + 1) / angularSegments;
                cursor = emit(data, cursor, a, plane, u0, 0.0, bounds);
                cursor = emit(data, cursor, b, plane, u1, 0.0, bounds);
                cursor = emit(data, cursor, c, plane, u1, 1.0, bounds);
                cursor = emit(data, cursor, a, plane, u0, 0.0, bounds);
                cursor = emit(data, cursor, c, plane, u1, 1.0, bounds);
                cursor = emit(data, cursor, d, plane, u0, 1.0, bounds);
            }
            int firstVertex = start * 6;
            batches[batchIndex++] = new Batch(firstVertex, count * 6, start, count, bounds.encodedAabb());
        }
        return new Mesh(data, batches);
    }

    private Point point(double theta, double z) {
        double radial = radiusMeters - surfaceY;
        double x = radial * Math.sin(theta);
        // 2 sin^2(theta / 2) remains accurate around tangent zero.
        double y = surfaceY + radial * (2.0 * Math.sin(theta * 0.5) * Math.sin(theta * 0.5));
        return new Point(finiteResult(x, "ring x"), finiteResult(y, "ring y"), z);
    }

    private static Plane plane(double midTheta, Point point) {
        // This is the outward normal of the exact chord plane shared by both
        // triangles in one angular quad. It is unit length by construction.
        double nx = Math.sin(midTheta);
        double ny = -Math.cos(midTheta);
        double d = -(nx * point.x + ny * point.y);
        return new Plane(nx, ny, 0.0, finiteResult(d, "ring plane offset"));
    }

    private static int emit(float[] target, int cursor, Point point, Plane plane, double u, double v, Bounds bounds) {
        float px = finiteFloat(point.x, "packed x"); float py = finiteFloat(point.y, "packed y"); float pz = finiteFloat(point.z, "packed z");
        float nx = finiteFloat(plane.nx, "packed nx"); float ny = finiteFloat(plane.ny, "packed ny");
        float nz = finiteFloat(plane.nz, "packed nz"); float nd = finiteFloat(plane.d, "packed d");
        float pxLow = finiteFloat(point.x - px, "packed x residual"); float pyLow = finiteFloat(point.y - py, "packed y residual"); float pzLow = finiteFloat(point.z - pz, "packed z residual");
        float nxLow = finiteFloat(plane.nx - nx, "packed nx residual"); float nyLow = finiteFloat(plane.ny - ny, "packed ny residual");
        float nzLow = finiteFloat(plane.nz - nz, "packed nz residual"); float ndLow = finiteFloat(plane.d - nd, "packed d residual");
        target[cursor++] = px; target[cursor++] = py; target[cursor++] = pz;
        target[cursor++] = pxLow; target[cursor++] = pyLow; target[cursor++] = pzLow;
        target[cursor++] = nx; target[cursor++] = ny; target[cursor++] = nz; target[cursor++] = nd;
        target[cursor++] = nxLow; target[cursor++] = nyLow; target[cursor++] = nzLow; target[cursor++] = ndLow;
        target[cursor++] = finiteFloat(u, "u"); target[cursor++] = finiteFloat(v, "v");
        bounds.include((double) px + pxLow, (double) py + pyLow, (double) pz + pzLow);
        return cursor;
    }

    private static float finiteFloat(double value, String name) {
        float packed = (float) value;
        if (!Float.isFinite(packed)) throw new IllegalArgumentException(name + " is outside float split range");
        return packed;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static double finiteResult(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " is not representable");
        return value;
    }

    public record Point(double x, double y, double z) {
        public Point { requireFinite(x, "point.x"); requireFinite(y, "point.y"); requireFinite(z, "point.z"); }
    }

    /** Unit-normal plane {@code nx*x + ny*y + nz*z + d = 0}. */
    public record Plane(double nx, double ny, double nz, double d) {
        public Plane {
            requireFinite(nx, "plane.nx"); requireFinite(ny, "plane.ny"); requireFinite(nz, "plane.nz"); requireFinite(d, "plane.d");
        }
    }

    public record Aabb(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        public Aabb {
            if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) throw new IllegalArgumentException("Invalid AABB");
        }
        public boolean contains(Point point) { return point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY && point.z >= minZ && point.z <= maxZ; }
    }

    public record Batch(int firstVertex, int vertexCount, int firstSegment, int segmentCount, Aabb encodedBounds) {
        public Batch { Objects.requireNonNull(encodedBounds, "encodedBounds"); }
    }

    public static final class Mesh {
        private final float[] vertices;
        private final Batch[] batches;
        private Mesh(float[] vertices, Batch[] batches) { this.vertices = vertices; this.batches = batches; }
        public int vertexCount() { return vertices.length / FLOATS_PER_VERTEX; }
        public int floatCount() { return vertices.length; }
        public int byteCount() { return Math.multiplyExact(vertices.length, Float.BYTES); }
        public int batchCount() { return batches.length; }
        public Batch batch(int index) { return batches[index]; }
        public void writeTo(FloatBuffer target) {
            Objects.requireNonNull(target, "target");
            if (target.remaining() < vertices.length) throw new IllegalArgumentException("Target buffer lacks ring mesh capacity");
            target.put(vertices);
        }
        public float component(int vertex, int component) {
            if (vertex < 0 || vertex >= vertexCount() || component < 0 || component >= FLOATS_PER_VERTEX) throw new IndexOutOfBoundsException();
            return vertices[vertex * FLOATS_PER_VERTEX + component];
        }
    }

    /** Pose for globally anchored mesh vertices and their exact chord planes. */
    public static final class Pose {
        private final double cos, sin, tx, ty, tz, radius;
        private Pose(double cos, double sin, double tx, double ty, double tz, double radius) {
            this.cos = cos; this.sin = sin; this.tx = tx; this.ty = ty; this.tz = tz; this.radius = radius;
        }
        public double cos() { return cos; } public double sin() { return sin; }
        public double translationX() { return tx; } public double translationY() { return ty; } public double translationZ() { return tz; }
        public Point transformPoint(Point point) {
            Objects.requireNonNull(point, "point");
            double x = cos * point.x + sin * (point.y - radius) + tx;
            double y = -sin * point.x + cos * (point.y - radius) + radius + ty;
            return new Point(finiteResult(x, "posed x"), finiteResult(y, "posed y"), finiteResult(point.z + tz, "posed z"));
        }
        public Plane transformPlane(Plane plane) {
            Objects.requireNonNull(plane, "plane");
            double nx = cos * plane.nx + sin * plane.ny;
            double ny = -sin * plane.nx + cos * plane.ny;
            // C - A*C + t for C=(0,R,0), evaluated without constructing mutable matrices.
            double offsetX = -sin * radius + tx;
            double offsetY = radius - cos * radius + ty;
            double d = plane.d - nx * offsetX - ny * offsetY - plane.nz * tz;
            return new Plane(finiteResult(nx, "posed plane nx"), finiteResult(ny, "posed plane ny"), plane.nz,
                    finiteResult(d, "posed plane d"));
        }
    }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        private void include(double x, double y, double z) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z); }
        private Aabb encodedAabb() { return new Aabb(Math.nextDown(minX), Math.nextUp(maxX), Math.nextDown(minY), Math.nextUp(maxY), Math.nextDown(minZ), Math.nextUp(maxZ)); }
    }
}
