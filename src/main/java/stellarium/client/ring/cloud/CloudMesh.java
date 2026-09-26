package stellarium.client.ring.cloud;

import java.nio.FloatBuffer;
import java.util.Objects;

/** Immutable CPU mesh using an interleaved {@code XYZ RGB} float layout. */
public final class CloudMesh {
    public static final int FLOATS_PER_VERTEX = 6;
    private final int quadCount;
    private final float[] vertices;

    CloudMesh(int quadCount, float[] vertices) {
        if (quadCount < 0) {
            throw new IllegalArgumentException("quadCount must be non-negative");
        }
        Objects.requireNonNull(vertices, "vertices");
        if (vertices.length != quadCount * 4 * FLOATS_PER_VERTEX) {
            throw new IllegalArgumentException("Vertex array does not match the quad count");
        }
        this.quadCount = quadCount;
        this.vertices = vertices.clone();
    }

    public int quadCount() {
        return quadCount;
    }

    public int vertexCount() {
        return quadCount * 4;
    }

    /** Read-only view for the client upload path; its backing array is never exposed. */
    public FloatBuffer vertexBuffer() {
        return FloatBuffer.wrap(vertices).asReadOnlyBuffer();
    }

    /** Defensive diagnostic/test copy. Render code should use {@link #vertexBuffer()}. */
    public float[] copyVertices() {
        return vertices.clone();
    }
}
