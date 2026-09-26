package stellarium.client.ring;

import java.nio.FloatBuffer;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import stellarium.StellarSky;
import stellarium.world.ring.RingworldBoardMeshGeometry;
import stellarium.world.ring.RingworldBoardMeshGeometry.Batch;
import stellarium.world.ring.RingworldBoardMeshGeometry.Mesh;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldSunshade;

/** Cached fixed-segment shell and moving panel walls; owns only its two VBOs. */
final class RingworldBoardMeshRenderer {
    private static final int STRIDE = RingworldBoardMeshGeometry.FLOATS_PER_VERTEX * Float.BYTES;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private int shellBuffer, wallBuffer, vertexArray;
    private List<Batch> shellBatches = List.of(), visibleShell = List.of(), visibleWalls = List.of();
    private double radius = Double.NaN, baseY, thickness;
    private boolean fallbackReported;

    boolean prepare(RingworldDisplaySnapshot snapshot, RingworldSunshade.CameraRelativeBands bands,
                    RingworldCurvatureFrame frame) {
        if (frame == null) return false;
        try {
            frame.copyProjection(matrixBuffer);
            if (!RingworldBoardClipProjection.supports(matrixBuffer)) {
                throw new RingworldBoardMeshGeometry.UnsupportedGeometryException("non-standard perspective near plane");
            }
            // Check the moving, potentially dense material before allocating a large shell.
            Mesh walls = RingworldBoardMeshGeometry.buildPanelWalls(frame.geometry(),
                    snapshot.sunshadeHeightBlocks(), snapshot.sunshadeThicknessBlocks(), bands,
                    frame.cameraX(), frame.renderOrigin().z());
            if (shellBuffer == 0 || radius != frame.geometry().radiusMeters()
                    || baseY != snapshot.sunshadeHeightBlocks() || thickness != snapshot.sunshadeThicknessBlocks()) {
                Mesh shell = RingworldBoardMeshGeometry.build(frame.geometry(),
                        snapshot.sunshadeHeightBlocks(), snapshot.sunshadeThicknessBlocks());
                int candidate = upload(shell, 0, GL15.GL_STATIC_DRAW);
                if (shellBuffer != 0) GL15.glDeleteBuffers(shellBuffer);
                shellBuffer = candidate;
                shellBatches = shell.batches();
                radius = frame.geometry().radiusMeters();
                baseY = snapshot.sunshadeHeightBlocks();
                thickness = snapshot.sunshadeThicknessBlocks();
                StellarSky.INSTANCE.getLogger().info("SS board mesh: radius={} m, segment=8192 m, segments={}, vertices={}, bytes={}",
                        radius, shell.segmentCount(), shell.vertexCount(), (long) shell.floatCount() * Float.BYTES);
            }
            if (walls.vertexCount() != 0) wallBuffer = upload(walls, wallBuffer, GL15.GL_STREAM_DRAW);
            double[][] planes = viewPlanes(frame);
            visibleShell = shellBatches.stream().filter(batch -> visible(batch, planes)).toList();
            visibleWalls = walls.batches().stream().filter(batch -> visible(batch, planes)).toList();
            fallbackReported = false;
            return true;
        } catch (RingworldBoardMeshGeometry.UnsupportedGeometryException | RingworldBoardMeshGeometry.WallBudgetException unsupported) {
            if (!fallbackReported) {
                StellarSky.INSTANCE.getLogger().warn("SS board mesh budget unavailable; using existing analytical board: {}",
                        unsupported.getMessage());
                fallbackReported = true;
            }
            return false;
        }
    }

    void render(RingworldBoardProgram program, int x, int y, int width, int height) {
        int priorBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int priorVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        if (vertexArray == 0) {
            vertexArray = GL30.glGenVertexArrays();
            if (vertexArray == 0) throw new IllegalStateException("Cannot allocate board mesh VAO");
        }
        try {
            // Actinium's client-attrib stack restores FFP flags, not generic array
            // pointers/enables. Keep all six streams in our own tracked VAO instead.
            GL30.glBindVertexArray(vertexArray);
            RingworldBoardMeshDistance.render(x, y, width, height, (stage, high, low, offsetX, offsetY) -> {
                program.setMeshPass(stage, stage == RingworldBoardMeshDistance.COLOR ? x : 0,
                        stage == RingworldBoardMeshDistance.COLOR ? y : 0, width, height, offsetX, offsetY);
                try (var timing = RingworldGpuProfile.measure(switch (stage) {
                    case RingworldBoardMeshDistance.HIGH -> RingworldGpuProfile.Stage.BOARD_NEAREST_HIGH;
                    case RingworldBoardMeshDistance.LOW -> RingworldGpuProfile.Stage.BOARD_NEAREST_LOW;
                    default -> RingworldGpuProfile.Stage.BOARD_COLOR;
                })) {
                    draw(shellBuffer, visibleShell);
                    if (!visibleWalls.isEmpty()) draw(wallBuffer, visibleWalls);
                }
            });
        } finally {
            GL30.glBindVertexArray(priorVertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, priorBuffer);
        }
    }

    void dispose() {
        if (shellBuffer != 0) GL15.glDeleteBuffers(shellBuffer);
        if (wallBuffer != 0) GL15.glDeleteBuffers(wallBuffer);
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
        shellBuffer = wallBuffer = vertexArray = 0;
        shellBatches = visibleShell = visibleWalls = List.of();
        radius = Double.NaN;
        RingworldBoardMeshDistance.dispose();
    }

    private static int upload(Mesh mesh, int existing, int usage) {
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int buffer = existing == 0 ? GL15.glGenBuffers() : existing;
        if (buffer == 0) throw new IllegalStateException("Cannot allocate board mesh VBO");
        boolean uploaded = false;
        try {
            FloatBuffer vertices = BufferUtils.createFloatBuffer(mesh.floatCount());
            mesh.writeTo(vertices);
            vertices.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, usage);
            uploaded = true;
            return buffer;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            if (!uploaded && existing == 0) GL15.glDeleteBuffers(buffer);
        }
    }

    private static void draw(int buffer, List<Batch> batches) {
        if (batches.isEmpty()) return;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        attribute(0, 3, 0);
        attribute(1, 3, 3);
        attribute(2, 4, 6);
        attribute(3, 4, 10);
        attribute(4, 3, 14);
        attribute(5, 3, 17);
        for (Batch batch : batches) GL11.glDrawArrays(GL11.GL_TRIANGLES, batch.vertexFirst(), batch.vertexCount());
    }

    private static void attribute(int index, int size, int floatOffset) {
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, STRIDE, (long) floatOffset * Float.BYTES);
        GL20.glEnableVertexAttribArray(index);
    }

    private double[][] viewPlanes(RingworldCurvatureFrame frame) {
        frame.copyProjection(matrixBuffer);
        float[] projection = new float[16]; matrixBuffer.get(projection);
        frame.copyModelView(matrixBuffer);
        float[] view = new float[16]; matrixBuffer.get(view);
        double[][] clip = new double[4][4];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            for (int k = 0; k < 4; k++) clip[row][column] += (double) projection[k * 4 + row] * view[column * 4 + k];
        }
        for (int row = 0; row < 4; row++) {
            clip[row][3] += clip[row][0] * frame.cameraX()
                    - clip[row][1] * frame.renderOrigin().y() - clip[row][2] * frame.renderOrigin().z();
        }
        double[][] planes = new double[5][4];
        for (int i = 0; i < 5; i++) {
            int axis = i / 2;
            double sign = i % 2 == 0 ? 1.0 : -1.0;
            for (int component = 0; component < 4; component++) planes[i][component] = clip[3][component] + sign * clip[axis][component];
        }
        // There is deliberately no far plane: native far depth is clamped, not an optical cutoff.
        return planes;
    }

    private static boolean visible(Batch batch, double[][] planes) {
        for (double[] plane : planes) {
            double x = plane[0] >= 0.0 ? batch.maxDisplayX() : batch.minDisplayX();
            double y = plane[1] >= 0.0 ? batch.maxDisplayY() : batch.minDisplayY();
            double z = plane[2] >= 0.0 ? batch.maxDisplayZ() : batch.minDisplayZ();
            double a = plane[0] * x, b = plane[1] * y, c = plane[2] * z;
            // Conservative float-clip rounding allowance; it can only retain extra batches.
            double margin = 1.0e-6 * (Math.abs(a) + Math.abs(b) + Math.abs(c) + Math.abs(plane[3]) + 1.0);
            if (a + b + c + plane[3] < -margin) return false;
        }
        return true;
    }
}
