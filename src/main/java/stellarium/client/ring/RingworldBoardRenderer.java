package stellarium.client.ring;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import stellarium.StellarSky;
import stellarium.client.ClientSettings;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldStripBounds;
import stellarium.world.ring.RingworldSunshade;

/** Renders the authoritative snapshot's visible opaque sunshade board in the early world pass. */
public final class RingworldBoardRenderer {
    private static final RingworldBoardProgram PROGRAM = new RingworldBoardProgram();
    private static final RingworldBoardProgram MESH_PROGRAM = new RingworldBoardProgram(true);
    private static final RingworldBoardMeshRenderer MESH = new RingworldBoardMeshRenderer();
    // LWJGL 2 implementations may require the full four-component GL_VIEWPORT backing array.
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);

    private RingworldBoardRenderer() {
    }

    /** Called by the early-world-pass hooks with their original camera partial tick value. */
    public static void renderCurrentWorld(float partialTicks) {
        RingworldDisplaySnapshot snapshot = RingworldRenderSnapshots.current();
        if (snapshot == null || snapshot.phase() == null || snapshot.sunshade().isEmpty()) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (snapshot.world() != minecraft.world || snapshot.scene() != StellarScene.getScene(minecraft.world)) {
            return;
        }
        double renderOriginX = snapshot.observer().x();
        double renderOriginY = snapshot.observer().y();
        double renderOriginZ = snapshot.observer().z();
        RingworldSunshade.CameraRelativeBands bands =
                snapshot.sunshade().cameraRelativeBands(snapshot.phase(), renderOriginX, renderOriginZ);
        ClientSettings clientSettings = StellarSky.PROXY.getClientSettings();
        RingworldBoardDotGrid.GridFrame dotGrid = RingworldBoardDotGrid.fromBands(bands, snapshot.sunshade(),
                snapshot.phase(), renderOriginX, renderOriginZ, clientSettings.ringworldBoardDotPitch);
        float dotPulseBrightness = RingworldBoardDotOutage.pulseBrightness(
                clientSettings.ringworldBoardDotPulseEnabled, minecraft.world.getTotalWorldTime(), partialTicks,
                clientSettings.ringworldBoardDotPulsePeriodSeconds);
        RingworldBoardDotWidthProfile.Profile dotWidthProfile = RingworldBoardDotWidthProfile.of(
                clientSettings.ringworldBoardDotEdgeProfileEnabled,
                clientSettings.ringworldBoardDotEdgeBandPercent,
                clientSettings.ringworldBoardDotEdgeBrightnessPercent,
                clientSettings.ringworldBoardDotCenterBrightnessPercent,
                clientSettings.ringworldBoardDotEdgeTransitionPercent);
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        if (!OpenGlHelper.shadersSupported)
            throw new IllegalStateException("Ringworld board rendering requires OpenGL shader support");
        boolean mesh = MESH.prepare(snapshot, bands,
                RingworldRenderSnapshots.currentDistantCurvatureFrameFor(snapshot.world(), snapshot.scene()));
        RingworldBoardProgram program = mesh ? MESH_PROGRAM : PROGRAM;
        int previousProgram = OpenGlHelper.openGL21 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                : ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
        RingworldColorMaskScope colorMasks = RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_FOG_BIT);
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            program.use();
            program.setUniforms(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3),
                    bands.directionX(), bands.directionZ(), bands.spacingBlocks(), bands.panelWidthBlocks(),
                    bands.gapWidthBlocks(), bands.edgeRelativeToRenderOriginBlocks(), bands.edgeOrientation(),
                    coverageUniform(bands.coverage()),
                    snapshot.sunshadeHeightBlocks() - renderOriginY, snapshot.sunshadeThicknessBlocks(),
                    RingworldStripBounds.BOARD_MIN_Z - renderOriginZ,
                    RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE - renderOriginZ,
                    clientSettings.renderRingworldBoardDots, clientSettings.ringworldBoardDotPitch,
                    clientSettings.ringworldBoardDotRadius, clientSettings.ringworldBoardDotBrightness,
                    clientSettings.ringworldBoardDotPerimeterInset,
                    dotGrid.observerXPhaseBlocks(), dotGrid.observerZPhaseBlocks(),
                    dotGrid.nearbyPanelAnchorXPhaseBlocks(), dotGrid.nearbyPanelAnchorZPhaseBlocks(),
                    dotGrid.panelStepXPhaseBlocks(), dotGrid.panelStepZPhaseBlocks(),
                    dotGrid.nearbyLeadingRelativeBlocks(),
                    clientSettings.ringworldBoardDotOffProbability, clientSettings.ringworldBoardDotSeed,
                    dotGrid.nearbyMaterialXCyclePhaseBlocks(), dotGrid.nearbyMaterialZCyclePhaseBlocks(),
                    dotGrid.panelStepXCyclePhaseBlocks(), dotGrid.panelStepZCyclePhaseBlocks(),
                    dotGrid.nearbyPanelResidue(),
                    dotGrid.tangentPhaseBlocks(), dotGrid.tangentCyclePhaseBlocks(),
                    dotPulseBrightness, dotWidthProfile);
            try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.BOARD)) {
                if (mesh) MESH.render(program, VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
                else drawClipQuad();
            }
        } finally {
            OpenGlHelper.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            colorMasks.close();
        }
    }

    /** Drops current GL objects after a resource reload; the next render compiles a fresh program. */
    public static void invalidate() {
        PROGRAM.dispose();
        MESH_PROGRAM.dispose();
        MESH.dispose();
    }

    /** Releases the program on the client-world lifecycle boundary. */
    public static void dispose() {
        PROGRAM.dispose();
        MESH_PROGRAM.dispose();
        MESH.dispose();
    }

    private static int coverageUniform(RingworldSunshade.BandCoverage coverage) {
        return switch (coverage) {
            case EMPTY -> 0;
            case PARTIAL -> 1;
            case FULL -> 2;
        };
    }

    private static void drawClipQuad() {
        GL11.glBegin(GL11.GL_QUADS);
        try {
            GL11.glVertex3d(-1.0, -1.0, 0.0);
            GL11.glVertex3d(1.0, -1.0, 0.0);
            GL11.glVertex3d(1.0, 1.0, 0.0);
            GL11.glVertex3d(-1.0, 1.0, 0.0);
        } finally {
            GL11.glEnd();
        }
    }
}
