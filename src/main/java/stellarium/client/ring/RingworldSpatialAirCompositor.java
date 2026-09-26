package stellarium.client.ring;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import stellarium.render.stellars.StellarRI;
import stellarium.util.OpenGlUtil;
import stellarium.world.StellarScene;

/**
 * The sole RenderWorldLastEvent listener for spatial air.  It copies (never
 * attaches to or clears) Minecraft's currently managed framebuffer, then
 * composites back into that same target once per dispatched eye event.
 */
public final class RingworldSpatialAirCompositor {
    private static final RingworldSpatialAirProgram LEGACY_PROGRAM = new RingworldSpatialAirProgram();
    private static final RingworldSpatialAirProgram CURVED_PROGRAM = new RingworldSpatialAirProgram(true);
    private static final RingworldSpatialAirProgram LOCAL_PROGRAM = new RingworldSpatialAirProgram(true, true);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(4);
    private static Object programmedWorld;
    private static Object programmedScene;

    public static void arm(StellarRI info) {
		observeScene(info.world, info.ringworldSnapshot == null ? null : info.ringworldSnapshot.scene());
        if (info.frameOptics != null && info.frameOptics.usesSpatialAir()) {
            RingworldSpatialAirPolicy.requireAccepted(inspect("arm", Minecraft.getMinecraft().getFramebuffer()));
        }
    }

	private static void observeScene(Object world, Object scene) {
		if (programmedWorld != null && (programmedWorld != world || programmedScene != scene)) {
			disposePrograms();
			programmedWorld = programmedScene = null;
		}
	}

    public static void invalidate() { disposePrograms(); RingworldOwnMediaOcclusion.dispose(); RingworldGpuProfile.dispose(); programmedWorld = programmedScene = null; }
    public static void dispose() { disposePrograms(); RingworldOwnMediaOcclusion.dispose(); RingworldGpuProfile.dispose(); programmedWorld = programmedScene = null; }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        try {
            compositeWorldLast(event);
        } finally {
            // Only completed timestamp pairs are read; slow frames never wait here for the GPU.
            RingworldGpuProfile.poll();
        }
    }

    private void compositeWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null) return;
        Object scene = StellarScene.getScene(minecraft.world);
        RingworldCurvatureFrame frame = RingworldRenderSnapshots.currentDistantCurvatureFrameFor(minecraft.world, scene);
        if (frame != null) {
            // A resize invalidates this optical pass; never combine two viewport coordinate systems.
            if (!RingworldDistantCurvature.viewportMatches(frame)) return;
            // Only air belongs after the world's transparent layers. Opaque media was captured early.
            try (var camera = RingworldDistantCurvature.useCamera(frame);
                 var texture = RingworldDistantDepthUniforms.bindTexture();
                 var media = RingworldOwnMediaDepthUniforms.bindTexture()) {
                renderAir(event, scene, frame);
            }
        } else {
            renderAir(event, scene, null);
        }
    }

    private void renderAir(RenderWorldLastEvent event, Object scene, RingworldCurvatureFrame curvatureFrame) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null) return;
        RingworldSpatialAirFrameOptics optics = RingworldRenderSnapshots.currentFrameOpticsFor(minecraft.world, scene);
        if (optics == null || !optics.usesSpatialAir()) return;
        boolean connectedModel = curvatureFrame != null
                && ProceduralRingModelRenderer.connectedPresented(optics.snapshot(), curvatureFrame);
        // The existing far preview remains a complete presentation owner.  A
        // connected model is the explicit seam where its far radiance may be
        // retained and the bounded local-air pass reconnected over it.
        if (ProceduralRingModelRenderer.presented(optics.snapshot(), curvatureFrame) && !connectedModel) return;
		if (programmedWorld != minecraft.world || programmedScene != scene) {
			disposePrograms();
			programmedWorld = minecraft.world;
			programmedScene = scene;
		}
        RingworldSpatialAirPolicy.requireAccepted(inspect("world-last", minecraft.getFramebuffer()));
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        int x = VIEWPORT.get(0), y = VIEWPORT.get(1), width = VIEWPORT.get(2), height = VIEWPORT.get(3);
        GlState state = GlState.capture();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
			// The shader explicitly decodes and re-encodes display-linear values;
			// fixed-function framebuffer conversion would apply a second transfer.
			GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            // Preserve the caller's anaglyph/stereo color mask.  The pass
            // writes only whichever channels the current eye is authorized to write.
            // The vertex shader writes gl_Position directly, so it needs no
            // matrix mutation.  Keeping both stacks untouched also preserves
            // the exact matrices used below to reconstruct the world ray.
            SSCloudFrame cloudFrame = SSCloudRenderer.currentFrameFor(minecraft.world, scene, optics.snapshot());
            if (cloudFrame != null && cloudFrame.closeWindow()) cloudFrame = null;
            RingworldSpatialAirProgram program = curvatureFrame == null ? LEGACY_PROGRAM
                    : connectedModel ? LOCAL_PROGRAM : CURVED_PROGRAM;
            try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.AIR_TOTAL)) {
                program.render(x, y, width, height, optics, cloudFrame, curvatureFrame);
            }
        } catch (RuntimeException exception) {
            disposePrograms();
            throw exception;
        } finally {
            state.restore();
        }
    }

    private static void disposePrograms() {
        try {
            LEGACY_PROGRAM.dispose();
        } finally {
            try {
                CURVED_PROGRAM.dispose();
            } finally {
                LOCAL_PROGRAM.dispose();
            }
        }
    }

    private static RingworldSpatialAirPolicy.FramebufferDiagnostic inspect(String stage, Framebuffer buffer) {
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        int viewportX = VIEWPORT.get(0), viewportY = VIEWPORT.get(1), viewportWidth = VIEWPORT.get(2), viewportHeight = VIEWPORT.get(3);
        int expected = buffer == null ? -1 : buffer.framebufferObject;
        int managedWidth = buffer == null ? -1 : buffer.framebufferWidth;
        int managedHeight = buffer == null ? -1 : buffer.framebufferHeight;
        if (buffer == null || !OpenGlHelper.framebufferSupported) {
            return rejected(stage, expected, -1, -1, managedWidth, managedHeight, false, viewportX, viewportY,
                    viewportWidth, viewportHeight, "managed framebuffer unavailable");
        }
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!caps.OpenGL30) {
            return rejected(stage, expected, -1, -1, managedWidth, managedHeight, buffer.isStencilEnabled(), viewportX, viewportY,
                    viewportWidth, viewportHeight, "OpenGL30 attachment inspection unavailable");
        }
        int readBound = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawBound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean viewportNonEmpty = viewportWidth > 0 && viewportHeight > 0;
        boolean viewportInside = viewportX >= 0 && viewportY >= 0
                && viewportX + viewportWidth <= buffer.framebufferWidth && viewportY + viewportHeight <= buffer.framebufferHeight;
        boolean managed = buffer.useDepth;
        boolean positive = expected > 0;
        boolean matches = positive && readBound == expected && drawBound == expected;
        if (!managed || !positive || !matches) {
            RingworldSpatialAirPolicy.FramebufferFacts facts = new RingworldSpatialAirPolicy.FramebufferFacts(managed,
                    matches, positive, viewportNonEmpty, viewportInside, false, false, false, false, false);
            String reason = "unsafe attachment inspection: expected=" + expected + ", read=" + readBound
                    + ", draw=" + drawBound;
            return new RingworldSpatialAirPolicy.FramebufferDiagnostic(stage, expected, readBound, drawBound,
                    managedWidth, managedHeight, viewportX, viewportY, viewportWidth, viewportHeight,
                    buffer.isStencilEnabled(), facts,
                    RingworldSpatialAirPolicy.AttachmentFacts.unavailable("color", reason),
                    RingworldSpatialAirPolicy.AttachmentFacts.unavailable("depth", reason));
        }
        // Both current read/draw bindings are the known Minecraft target. Do
        // not bind any framebuffer here: attachment inspection is read-only.
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        try {
            int colorType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int colorName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            boolean colorTexture = colorType == GL11.GL_TEXTURE && colorName == buffer.framebufferTexture
                    && colorName > 0 && GL11.glIsTexture(colorName);
            int colorFormat = -1;
            if (colorTexture) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorName);
                colorFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            }
            boolean rgba8 = colorTexture && colorFormat == GL11.GL_RGBA8;
            RingworldManagedDepth.Result depth = RingworldManagedDepth.inspect(buffer);
            boolean knownDepth = depth.managedAttachment();
            boolean depth24 = depth.supportedFormat();
            boolean singleSample = depth.singleSample();
            RingworldSpatialAirPolicy.FramebufferFacts facts = new RingworldSpatialAirPolicy.FramebufferFacts(true,
                    true, true, viewportNonEmpty, viewportInside, colorTexture, rgba8, knownDepth, depth24,
                    singleSample);
            return new RingworldSpatialAirPolicy.FramebufferDiagnostic(stage, expected, readBound, drawBound,
                    managedWidth, managedHeight, viewportX, viewportY, viewportWidth, viewportHeight,
                    buffer.isStencilEnabled(), facts,
                    new RingworldSpatialAirPolicy.AttachmentFacts("color", true, null, colorType, colorName,
                            colorFormat, 1),
                    new RingworldSpatialAirPolicy.AttachmentFacts("depth", true, null, depth.objectType(), depth.objectName(),
                            depth.internalFormat(), depth.samples()));
        } finally {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActive);
        }
    }

    private static RingworldSpatialAirPolicy.FramebufferDiagnostic rejected(String stage, int expected, int read,
                                                                              int draw, int width, int height, boolean stencilEnabled,
                                                                              int viewportX, int viewportY,
                                                                              int viewportWidth, int viewportHeight,
                                                                              String reason) {
        RingworldSpatialAirPolicy.FramebufferFacts facts = new RingworldSpatialAirPolicy.FramebufferFacts(false,
                false, expected > 0, viewportWidth > 0 && viewportHeight > 0, false, false, false,
                false, false, false);
        return new RingworldSpatialAirPolicy.FramebufferDiagnostic(stage, expected, read, draw, width, height,
                viewportX, viewportY, viewportWidth, viewportHeight, stencilEnabled, facts,
                RingworldSpatialAirPolicy.AttachmentFacts.unavailable("color", reason),
                RingworldSpatialAirPolicy.AttachmentFacts.unavailable("depth", reason));
    }

    private static final class GlState {
        private final RingworldColorMaskScope colorMasks;
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int texture0;
        private final int texture1;
        private final int texture2;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        // Captured explicitly because the pass disables FRAMEBUFFER_SRGB while
        // performing its own calibrated display-linear decode/encode.
        @SuppressWarnings("unused")
        private final boolean framebufferSrgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);

        private GlState(RingworldColorMaskScope colorMasks) {
            this.colorMasks = colorMasks;
            GL13.glActiveTexture(GL13.GL_TEXTURE0); texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE2); texture2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(activeTexture);
            VIEWPORT.clear(); GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
            viewportX = VIEWPORT.get(0); viewportY = VIEWPORT.get(1); viewportWidth = VIEWPORT.get(2); viewportHeight = VIEWPORT.get(3);
        }
        static GlState capture() {
            RingworldColorMaskScope masks = RingworldColorMaskScope.capture();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_FOG_BIT);
            try {
                return new GlState(masks);
            } catch (RuntimeException | Error failure) {
                GL11.glPopAttrib();
                masks.close();
                throw failure;
            }
        }
        void restore() {
            OpenGlHelper.glUseProgram(program);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture1);
            GL13.glActiveTexture(GL13.GL_TEXTURE2); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture2);
            GL13.glActiveTexture(activeTexture);
            GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11.glPopAttrib();
			colorMasks.close();
			if (framebufferSrgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB); else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glMatrixMode(matrixMode);
        }
    }
}
