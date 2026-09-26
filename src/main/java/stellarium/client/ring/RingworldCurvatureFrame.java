package stellarium.client.ring;

import java.nio.FloatBuffer;
import java.util.Objects;
import stellarium.world.ring.RingworldDisplayGeometry;
import stellarium.world.ring.RingworldRenderObserver;

/**
 * Immutable optical frame for curved world passes. Construction does not enable a render mode;
 * the world-pass coordinator must admit all depth writers before publishing this frame.
 * Render origin and optical eye are deliberately distinct, including in third-person views.
 */
public final class RingworldCurvatureFrame {
    private final Object world;
    private final Object scene;
    private final RingworldDisplayGeometry geometry;
    private final RingworldRenderObserver renderOrigin;
    private final RingworldRenderObserver opticalEye;
    private final CloudCameraFrame camera;
    private final float[] projection;
    private final float[] modelView;

    public RingworldCurvatureFrame(Object world, Object scene, RingworldRenderObserver renderOrigin,
                                  double radius, float[] projection, float[] modelView,
                                  int viewportX, int viewportY, int width, int height) {
        this.world = Objects.requireNonNull(world, "world");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.renderOrigin = Objects.requireNonNull(renderOrigin, "renderOrigin");
        this.geometry = new RingworldDisplayGeometry(radius);
        this.camera = CloudCameraFrame.from(projection, modelView, viewportX, viewportY, width, height);
        this.projection = projection.clone();
        this.modelView = modelView.clone();
        this.opticalEye = new RingworldRenderObserver(renderOrigin.x() + camera.cameraX(),
                renderOrigin.y() + camera.cameraY(), renderOrigin.z() + camera.cameraZ());
        // Validate before an invalid camera can be published to any GL/depth consumer.
        geometry.cameraRelative(opticalEye, new RingworldDisplayGeometry.Point(
                opticalEye.x(), opticalEye.y(), opticalEye.z()));
    }

    public RingworldDisplayGeometry geometry() { return geometry; }
    public RingworldRenderObserver renderOrigin() { return renderOrigin; }
    public RingworldRenderObserver opticalEye() { return opticalEye; }
    public float cameraX() { return camera.cameraX(); }
    public float cameraY() { return camera.cameraY(); }
    public float cameraZ() { return camera.cameraZ(); }
    public double pixelAngularSize() { return camera.pixelAngularSize(); }
    boolean sameOpticalFrame(RingworldCurvatureFrame other) {
        return other != null && world == other.world && scene == other.scene
                && geometry.radiusMeters() == other.geometry.radiusMeters()
                && renderOrigin.equals(other.renderOrigin) && camera.sameView(other.camera);
    }
    public boolean belongsTo(Object world, Object scene) { return this.world == world && this.scene == scene; }
    public boolean matchesViewport(int x, int y, int width, int height) {
        return camera.matchesViewport(x, y, width, height);
    }

    public void copyProjection(FloatBuffer target) { copy(projection, target); }
    public void copyModelView(FloatBuffer target) { copy(modelView, target); }
    public void copyInverseProjection(FloatBuffer target) { camera.copyInverseProjection(target); }
    public void copyInverseModelView(FloatBuffer target) { camera.copyInverseModelView(target); }

    /** Maps a physical point relative to the render origin, suitable for the frozen base view matrix. */
    public RingworldDisplayGeometry.Point displayRelative(RingworldDisplayGeometry.Point physicalRelative) {
        RingworldDisplayGeometry.Point physical = new RingworldDisplayGeometry.Point(
                renderOrigin.x() + physicalRelative.x(), renderOrigin.y() + physicalRelative.y(),
                renderOrigin.z() + physicalRelative.z());
        RingworldDisplayGeometry.Point eyeRelative = geometry.cameraRelative(opticalEye, physical);
        return new RingworldDisplayGeometry.Point(eyeRelative.x() + cameraX(),
                eyeRelative.y() + cameraY(), eyeRelative.z() + cameraZ());
    }

    /** Inverts display depth without interpreting it as a straight physical-world ray. */
    public RingworldDisplayGeometry.Point physicalRelative(RingworldDisplayGeometry.Point displayRelative) {
        RingworldDisplayGeometry.Point physical = geometry.worldPoint(opticalEye,
                new RingworldDisplayGeometry.Point(displayRelative.x() - cameraX(),
                        displayRelative.y() - cameraY(), displayRelative.z() - cameraZ()));
        return new RingworldDisplayGeometry.Point(physical.x() - renderOrigin.x(),
                physical.y() - renderOrigin.y(), physical.z() - renderOrigin.z());
    }

    private static void copy(float[] source, FloatBuffer target) {
        if (target == null || target.capacity() < 16) throw new IllegalArgumentException("Matrix output requires 16 floats");
        target.clear();
        target.put(source);
        target.flip();
    }
}
