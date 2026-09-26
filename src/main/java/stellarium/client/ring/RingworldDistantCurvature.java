package stellarium.client.ring;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Loader;
import org.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;
import stellarium.StellarSky;
import stellarium.client.ring.dh.DistantHorizonsDepthBridge;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplayGeometry;

/** Publishes one optical frame for DH/own media, leaving near entities and native chunk culling flat. */
public final class RingworldDistantCurvature {
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODEL_VIEW = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static boolean reported;

    private RingworldDistantCurvature() { }

    public static void prepareCurrentWorld() {
        RingworldBoardMeshDistance.clearSelection();
        DistantHorizonsDepthBridge.clear();
        var snapshot = RingworldRenderSnapshots.current();
        if (snapshot == null || snapshot.phase() == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (snapshot.world() != minecraft.world || snapshot.scene() != StellarScene.getScene(minecraft.world)) return;
        if (!GLContext.getCapabilities().OpenGL40) {
            throw new IllegalStateException("StellarSky distant curvature requires OpenGL 4.0 and GPU FP64");
        }
        PROJECTION.clear();
        MODEL_VIEW.clear();
        VIEWPORT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODEL_VIEW);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        float[] projection = new float[16], view = new float[16];
        PROJECTION.get(projection);
        MODEL_VIEW.get(view);
        double radius = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
        if (snapshot.sunshadeHeightBlocks() + (double) snapshot.sunshadeThicknessBlocks() >= radius
                || snapshot.airProfile().upperY() >= radius) {
            throw new IllegalStateException("Ringworld media must remain below the display cylinder axis");
        }
        RingworldCurvatureFrame frame = new RingworldCurvatureFrame(snapshot.world(),
                snapshot.scene(), snapshot.observer(), radius, projection, view,
                VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
        RingworldRenderSnapshots.captureOpticalFrame(frame);
        // The inactive own-media path still needs the actual optical eye for its linear ray.
        if (!Loader.isModLoaded("distanthorizons")) return;
        var provider = ShaderProviderHolder.getProvider();
        if (ShaderProviderHolder.isShadowPass() || (provider != null && provider.isShadersEnabled())) {
            throw new IllegalStateException("StellarSky distant curvature currently requires native Actinium/DH rendering without an external shaderpack");
        }
        RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
        if (!reported) {
            StellarSky.INSTANCE.getLogger().info("SS distant curvature frame published: radius={} m; DH LOD and own media share the optical frame", radius);
            reported = true;
        }
    }

    static boolean viewportMatches(RingworldCurvatureFrame frame) {
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        return frame.matchesViewport(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
    }

    public static void renderEarlyMedia(float partialTicks) {
        Runnable draw = () -> {
            RingworldBoardRenderer.renderCurrentWorld(partialTicks);
            SSCloudRenderer.renderCurrentWorld(partialTicks);
        };
        var frame = RingworldRenderSnapshots.currentDistantCurvatureFrame();
        if (frame == null) {
            draw.run();
        } else {
            try (var camera = useCamera(frame);
                 var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.OWN_MEDIA_TOTAL)) {
                RingworldOwnMediaOcclusion.render(frame, draw);
            }
        }
    }

    /**
     * Settles the preview ground of this optical pass.
     *
     * <p>The early own-media pass can only produce the preview's reduction keys: this
     * frame's admitted Distant Horizons coverage is published when DH's own submission
     * and main-view composition have both returned, which happens inside the SOLID
     * terrain call. The caller therefore invokes this between SOLID and the remaining
     * world layers, so the preview's colour and depth reach the managed framebuffer
     * after near terrain and DH have drawn but before cutout and translucent layers.</p>
     *
     * <p>The frozen optical matrices are reinstalled for this stage because the model
     * shader reconstructs both its ray and its fragment depth from them.</p>
     */
    public static void renderDeferredPreview(float partialTicks) {
        var frame = RingworldRenderSnapshots.currentDistantCurvatureFrame();
        if (frame == null) return;
        try (var camera = useCamera(frame)) {
            ProceduralRingModelRenderer.drawDeferredGround(frame);
        }
    }

    /** Restores the frozen optical matrices while preserving the WorldLast caller's stacks. */
    static FrozenCamera useCamera(RingworldCurvatureFrame frame) {
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        frame.copyProjection(PROJECTION);
        GL11.glLoadMatrix(PROJECTION);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        frame.copyModelView(MODEL_VIEW);
        GL11.glLoadMatrix(MODEL_VIEW);
        return new FrozenCamera(mode);
    }

    static final class FrozenCamera implements AutoCloseable {
        private final int originalMode;
        private FrozenCamera(int originalMode) { this.originalMode = originalMode; }
        @Override public void close() {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(originalMode);
        }
    }
}
