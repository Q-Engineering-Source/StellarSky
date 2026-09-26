package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;

/**
 * Captures StellarSky's early opaque board/cloud pass in a private framebuffer.
 *
 * <p>The Minecraft framebuffer is only ever a blit source or destination.  In
 * particular, the distance attachment is never attached to it: the target has
 * an independent RGBA8 colour texture, RG32F distance texture, and a matching
 * depth (or depth/stencil) renderbuffer.  One target is retained per nested
 * render scope so an inner viewport cannot invalidate an outer eye's result.</p>
 */
public final class RingworldOwnMediaOcclusion {
    private static final Map<Integer, Target> TARGETS = new HashMap<>();
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(4);
    private static final IntBuffer DRAW_BOTH = BufferUtils.createIntBuffer(2);

    private RingworldOwnMediaOcclusion() {
    }

    /**
     * Copies the complete managed colour/depth target into an owned target,
     * lets the caller draw its early opaque media once, and copies the result
     * back.  The distance texture remains valid only for this exact scope.
     */
    public static void render(RingworldCurvatureFrame frame, Runnable drawOwnMedia) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(drawOwnMedia, "drawOwnMedia");
        if (frame != RingworldRenderSnapshots.currentDistantCurvatureFrame()) {
            throw new IllegalStateException("Own-media occlusion requires the current distant curvature frame");
        }
        int scopeDepth = RingworldRenderSnapshots.scopeDepth();
        if (scopeDepth < 0) throw new IllegalStateException("Own-media occlusion requires an active render scope");

        // A previous capture must never be sampled after an attempted redraw,
        // including a validation, allocation, or media-render failure.
        RingworldRenderSnapshots.captureOwnMediaOcclusion(null);
        Source source = inspectManagedSource(frame, Minecraft.getMinecraft().getFramebuffer());
        GlState caller = GlState.capture();
        boolean copiedBack = false;
        try {
            // Copying and the sentinel clear cover the whole managed target.
            // The caller's clip applies only to the actual media geometry.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            Target target = targetFor(scopeDepth, source.width, source.height, source.stencil);
            copyManagedTarget(source, target, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                    | (source.stencil ? GL11.GL_STENCIL_BUFFER_BIT : 0));

            // This is the sole clear in the owned FBO and selects only the
            // distance attachment.  (0, 0) is the shader's no-own-media sentinel.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT1);
            GL11.glColorMask(true, true, true, true);
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            DRAW_BOTH.clear();
            DRAW_BOTH.put(GL30.GL_COLOR_ATTACHMENT0).put(GL30.GL_COLOR_ATTACHMENT1).flip();
            GL20Access.drawBuffers(DRAW_BOTH);
            // Stereo/anaglyph callers retain their colour permission on the
            // visible attachment, while RG distance always receives both lanes.
            caller.applyColorMask(0);
            GL30.glColorMaski(1, true, true, true, true);
            caller.applyScissor();
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            drawOwnMedia.run();

            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            copyOwnedTarget(target, source, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                    | (source.stencil ? GL11.GL_STENCIL_BUFFER_BIT : 0));
            copiedBack = true;
            RingworldRenderSnapshots.captureOwnMediaOcclusion(new Snapshot(frame, target.distanceTexture,
                    source.viewportX, source.viewportY, source.viewportWidth, source.viewportHeight,
                    target.width, target.height, scopeDepth));
        } finally {
            // Do not publish an old value after a failure.  The caller's MC
            // target was deliberately not blitted back until the full draw ended.
            if (!copiedBack) RingworldRenderSnapshots.captureOwnMediaOcclusion(null);
            caller.restore();
        }
    }

    /** Returns an owned distance texture only for the active frame and nested scope. */
    public static Snapshot current() {
        Snapshot snapshot = RingworldRenderSnapshots.currentOwnMediaOcclusion();
        if (snapshot == null) return null;
        RingworldCurvatureFrame frame = RingworldRenderSnapshots.currentDistantCurvatureFrame();
        return snapshot.frame == frame && snapshot.scopeDepth == RingworldRenderSnapshots.scopeDepth() ? snapshot : null;
    }

    /** Deletes only framebuffer objects and textures allocated by this class. */
    public static void dispose() {
        for (Target target : TARGETS.values()) target.dispose();
        TARGETS.clear();
    }

    private static Source inspectManagedSource(RingworldCurvatureFrame frame, Framebuffer framebuffer) {
        if (framebuffer == null || !OpenGlHelper.framebufferSupported) {
            throw new IllegalStateException("Minecraft managed framebuffer is unavailable");
        }
        ContextCapabilities caps = GLContext.getCapabilities();
        if (!caps.OpenGL30) throw new IllegalStateException("Own-media occlusion requires OpenGL 3.0 framebuffer support");
        if (GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) < 2) {
            throw new IllegalStateException("Own-media occlusion requires two draw buffers");
        }
        if (!framebuffer.useDepth || framebuffer.framebufferObject <= 0
                || framebuffer.framebufferWidth <= 0 || framebuffer.framebufferHeight <= 0) {
            throw new IllegalStateException("Minecraft managed framebuffer is not a renderable depth target");
        }
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (read != framebuffer.framebufferObject || draw != framebuffer.framebufferObject) {
            throw new IllegalStateException("Minecraft managed framebuffer is not bound for both read and draw");
        }
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        int viewportX = VIEWPORT.get(0), viewportY = VIEWPORT.get(1);
        int viewportWidth = VIEWPORT.get(2), viewportHeight = VIEWPORT.get(3);
        if (viewportWidth <= 0 || viewportHeight <= 0
                || viewportX < 0 || viewportY < 0
                || viewportX + viewportWidth > framebuffer.framebufferWidth
                || viewportY + viewportHeight > framebuffer.framebufferHeight
                || !frame.matchesViewport(viewportX, viewportY, viewportWidth, viewportHeight)) {
            throw new IllegalStateException("Managed framebuffer viewport does not match the frozen curvature frame");
        }
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Minecraft managed framebuffer is incomplete");
        }

        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        try {
            int colorType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int colorName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (colorType != GL11.GL_TEXTURE || colorName != framebuffer.framebufferTexture || colorName <= 0
                    || !GL11.glIsTexture(colorName)) {
                throw new IllegalStateException("Minecraft managed colour attachment is not its RGBA texture");
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorName);
            int colorFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (colorFormat == GL21.GL_SRGB8_ALPHA8) {
                throw new IllegalStateException("Minecraft managed sRGB colour attachment is unsupported for own-media copying");
            }
            if (colorFormat != GL11.GL_RGBA8) {
                throw new IllegalStateException("Minecraft managed colour attachment is not RGBA8");
            }
            RingworldManagedDepth.Result depth = RingworldManagedDepth.inspect(framebuffer);
            if (!depth.accepted()) {
                throw new IllegalStateException("Minecraft managed depth attachment is unsupported for own-media copying: "
                        + depth);
            }
            boolean stencil = framebuffer.isStencilEnabled();
            return new Source(framebuffer.framebufferObject, framebuffer.framebufferWidth, framebuffer.framebufferHeight,
                    stencil, viewportX, viewportY, viewportWidth, viewportHeight);
        } finally {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL13.glActiveTexture(activeTexture);
        }
    }

    private static Target targetFor(int scopeDepth, int width, int height, boolean stencil) {
        Target existing = TARGETS.get(scopeDepth);
        if (existing != null && existing.matches(width, height, stencil)) return existing;
        if (existing != null) existing.dispose();
        Target created = new Target(width, height, stencil);
        TARGETS.put(scopeDepth, created);
        return created;
    }

    private static void copyManagedTarget(Source source, Target target, int mask) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.framebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.framebuffer);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL30.glBlitFramebuffer(0, 0, source.width, source.height, 0, 0, target.width, target.height, mask, GL11.GL_NEAREST);
    }

    private static void copyOwnedTarget(Target target, Source targetFramebuffer, int mask) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.framebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer.framebuffer);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL30.glBlitFramebuffer(0, 0, target.width, target.height, 0, 0, targetFramebuffer.width,
                targetFramebuffer.height, mask, GL11.GL_NEAREST);
    }

    private static final class Target {
        private final int width, height;
        private final boolean stencil;
        private int framebuffer;
        private int colorTexture;
        private int distanceTexture;
        private int depthRenderbuffer;

        private Target(int width, int height, boolean stencil) {
            this.width = width;
            this.height = height;
            this.stencil = stencil;
            try {
                framebuffer = GL30.glGenFramebuffers();
                colorTexture = texture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, width, height);
                distanceTexture = texture(GL30.GL_RG32F, GL30.GL_RG, GL11.GL_FLOAT, width, height);
                depthRenderbuffer = GL30.glGenRenderbuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, colorTexture, 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                        GL11.GL_TEXTURE_2D, distanceTexture, 0);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRenderbuffer);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, stencil ? GL30.GL_DEPTH24_STENCIL8 : GL14.GL_DEPTH_COMPONENT24,
                        width, height);
                int samples = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
                if (samples != 0 && samples != 1) {
                    throw new IllegalStateException("Own-media depth renderbuffer unexpectedly allocated multisample storage");
                }
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_RENDERBUFFER, depthRenderbuffer);
                if (stencil) {
                    GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                            GL30.GL_RENDERBUFFER, depthRenderbuffer);
                }
                DRAW_BOTH.clear();
                DRAW_BOTH.put(GL30.GL_COLOR_ATTACHMENT0).put(GL30.GL_COLOR_ATTACHMENT1).flip();
                GL20Access.drawBuffers(DRAW_BOTH);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Own-media framebuffer is incomplete");
                }
            } catch (RuntimeException failure) {
                dispose();
                throw failure;
            }
        }

        private static int texture(int internalFormat, int format, int type, int width, int height) {
            int texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12Access.clampToEdge());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12Access.clampToEdge());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer)null);
            return texture;
        }

        private boolean matches(int width, int height, boolean stencil) {
            return this.width == width && this.height == height && this.stencil == stencil;
        }

        private void dispose() {
            if (depthRenderbuffer != 0) GL30.glDeleteRenderbuffers(depthRenderbuffer);
            if (colorTexture != 0) GL11.glDeleteTextures(colorTexture);
            if (distanceTexture != 0) GL11.glDeleteTextures(distanceTexture);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = colorTexture = distanceTexture = depthRenderbuffer = 0;
        }
    }

    /** Immutable, scope-bound metadata for sampling the owned RG32F distance attachment. */
    public static final class Snapshot {
        private final RingworldCurvatureFrame frame;
        private final int textureId;
        private final int viewportX, viewportY, viewportWidth, viewportHeight;
        private final int textureWidth, textureHeight;
        private final int scopeDepth;

        public Snapshot(RingworldCurvatureFrame frame, int textureId, int viewportX, int viewportY,
                        int viewportWidth, int viewportHeight, int textureWidth, int textureHeight, int scopeDepth) {
            this.frame = Objects.requireNonNull(frame, "frame");
            if (textureId <= 0 || scopeDepth < 0 || viewportX < 0 || viewportY < 0
                    || viewportWidth <= 0 || viewportHeight <= 0 || textureWidth <= 0 || textureHeight <= 0
                    || viewportX + viewportWidth > textureWidth || viewportY + viewportHeight > textureHeight
                    || !frame.matchesViewport(viewportX, viewportY, viewportWidth, viewportHeight)) {
                throw new IllegalArgumentException("Invalid own-media occlusion snapshot");
            }
            this.textureId = textureId;
            this.viewportX = viewportX;
            this.viewportY = viewportY;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.scopeDepth = scopeDepth;
        }

        public RingworldCurvatureFrame frame() { return frame; }
        public int textureId() { return textureId; }
        public int viewportX() { return viewportX; }
        public int viewportY() { return viewportY; }
        public int viewportWidth() { return viewportWidth; }
        public int viewportHeight() { return viewportHeight; }
        public int textureWidth() { return textureWidth; }
        public int textureHeight() { return textureHeight; }

        int scopeDepth() { return scopeDepth; }
    }

    private record Source(int framebuffer, int width, int height, boolean stencil,
                          int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
    }

    private static final class GlState {
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        private final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int texture2d;
        private final int viewportX, viewportY, viewportWidth, viewportHeight;
        private final boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final int scissorX, scissorY, scissorWidth, scissorHeight;
        private final int[] drawBuffers;
        private final boolean[] redMasks, greenMasks, blueMasks, alphaMasks;

        private GlState() {
            texture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            VIEWPORT.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
            viewportX = VIEWPORT.get(0);
            viewportY = VIEWPORT.get(1);
            viewportWidth = VIEWPORT.get(2);
            viewportHeight = VIEWPORT.get(3);
            IntBuffer scissor = BufferUtils.createIntBuffer(4);
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
            scissorX = scissor.get(0);
            scissorY = scissor.get(1);
            scissorWidth = scissor.get(2);
            scissorHeight = scissor.get(3);
            int count = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            if (count <= 0) throw new IllegalStateException("OpenGL reports no draw buffers");
            drawBuffers = new int[count];
            redMasks = new boolean[count];
            greenMasks = new boolean[count];
            blueMasks = new boolean[count];
            alphaMasks = new boolean[count];
            ByteBuffer indexedMask = BufferUtils.createByteBuffer(4);
            for (int index = 0; index < count; index++) {
                drawBuffers[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
                indexedMask.clear();
                // The legacy name is redirected by Actinium without checking
                // its indexed descriptor. Use Cleanroom's explicit binding.
                org.lwjglx.opengl.GL30.glGetBoolean(GL11.GL_COLOR_WRITEMASK, index, indexedMask);
                redMasks[index] = indexedMask.get(0) != 0;
                greenMasks[index] = indexedMask.get(1) != 0;
                blueMasks[index] = indexedMask.get(2) != 0;
                alphaMasks[index] = indexedMask.get(3) != 0;
            }
        }

        private static GlState capture() {
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                    | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_SCISSOR_BIT);
            try {
                return new GlState();
            } catch (RuntimeException | Error failure) {
                GL11.glPopAttrib();
                throw failure;
            }
        }

        private void restore() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL11.glReadBuffer(readBuffer);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            GL13.glActiveTexture(activeTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture2d);
            GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11.glPopAttrib();
            // Attribute stacks and Actinium's emulation do not own indexed
            // MRT state. Restore it last so attachment one cannot leak.
            IntBuffer restoredDrawBuffers = BufferUtils.createIntBuffer(drawBuffers.length);
            restoredDrawBuffers.put(drawBuffers).flip();
            GL20Access.drawBuffers(restoredDrawBuffers);
            for (int index = 0; index < drawBuffers.length; index++) applyColorMask(index);
        }

        private void applyColorMask(int index) {
            GL30.glColorMaski(index, redMasks[index], greenMasks[index], blueMasks[index], alphaMasks[index]);
        }

        private void applyScissor() {
            GL11.glScissor(scissorX, scissorY, scissorWidth, scissorHeight);
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** Isolates the two GL symbols that need a core profile rather than Minecraft's helper wrapper. */
    private static final class GL20Access {
        private static void drawBuffers(IntBuffer attachments) { GL20.glDrawBuffers(attachments); }
    }

    private static final class GL12Access {
        private static int clampToEdge() { return GL12.GL_CLAMP_TO_EDGE; }
    }
}
