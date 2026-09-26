package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjglx.opengl.GLContext;

import stellarium.render.util.SamplerBindings;

/**
 * Finds a mesh fragment's exact double-precision ordering key without a GPU
 * readback. A single {@code R32F} reduction cannot distinguish all relevant
 * distances, so the caller renders the same coverage three times: high float
 * key, low residual among exact high-key matches, then colour.
 *
 * <p>The two reduction attachments are private, single-sample {@code R32F}
 * textures. They are never attached to Minecraft's framebuffer and are not
 * depth-copy targets. The callback owns shader uniforms and output routing;
 * this class owns only the reduction targets and the state boundary around
 * them.</p>
 */
final class RingworldMeshDistance {
    static final int HIGH = 0;
    static final int LOW = 1;
    static final int COLOR = 2;

    private static final float EMPTY_DISTANCE = 1.0E30F;
    private final int highTextureUnit;
    private final int lowTextureUnit;
    private final Map<Long, Targets> targets = new HashMap<>();
    private final ThreadLocal<Integer> renderDepth = ThreadLocal.withInitial(() -> 0);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    // glClearBufferfv(GL_COLOR) requires four accessible components even for an R32F target.
    private final FloatBuffer empty = BufferUtils.createFloatBuffer(4);

    RingworldMeshDistance(int highTextureUnit, int lowTextureUnit) {
        if (highTextureUnit < 0 || lowTextureUnit < 0 || highTextureUnit == lowTextureUnit) {
            throw new IllegalArgumentException("Ringworld mesh distance requires distinct non-negative texture units");
        }
        this.highTextureUnit = highTextureUnit;
        this.lowTextureUnit = lowTextureUnit;
    }

    /**
     * Executes high-key, low-key, and final colour passes. The callback is
     * invoked with a zero pixel offset for the private full-viewport targets
     * and the caller's original pixel origin for the final pass.
     */
    Selection render(int viewportX, int viewportY, int width, int height, Pass draw) {
        if (viewportX < 0 || viewportY < 0) {
            throw new IllegalArgumentException("Invalid ringworld mesh viewport " + viewportX + ',' + viewportY);
        }
        Selection selection = reduce(width, height, draw);
        renderColor(selection, viewportX, viewportY, draw);
        return selection;
    }

    /**
     * Executes only the two private reductions.
     *
     * <p>The caller's target, viewport, draw buffers and colour masks are left
     * exactly as they were, and nothing is drawn into the caller's framebuffer.
     * The returned {@link Selection} therefore stays the sole source of this
     * layer's distance keys for the current render scope and nesting depth, so a
     * later stage of the same optical pass can settle the colour without
     * re-submitting the geometry. Another reduction at the same instance and
     * depth replaces it, which is why only one scope-local payload may hold it.</p>
     */
    Selection reduce(int width, int height, Pass draw) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid ringworld mesh reduction size " + width + 'x' + height);
        }
        Objects.requireNonNull(draw, "draw");
        requireSupport();

        int depth = renderDepth.get();
        renderDepth.set(depth + 1);
        try {
            GlState reductions = captureState();
            boolean reductionsRestored = false;
            try {
                int scopeDepth = RingworldRenderSnapshots.scopeDepth();
                if (scopeDepth < 0) {
                    throw new IllegalStateException("Board mesh distance requires an active world-render scope");
                }
                long targetKey = ((long) scopeDepth << 32) | (depth & 0xffffffffL);
                Targets selectedTargets = targetFor(targetKey, width, height);
                drawReduction(selectedTargets.high, HIGH, 0, 0, 0, 0, draw);
                drawReduction(selectedTargets.low, LOW, selectedTargets.high.texture, 0, 0, 0, draw);
                Selection selection =
                        new Selection(selectedTargets.high.texture, selectedTargets.low.texture, width, height);
                reductions.restore();
                reductionsRestored = true;
                return selection;
            } finally {
                if (!reductionsRestored) reductions.restore();
            }
        } finally {
            renderDepth.set(depth);
        }
    }

    /**
     * Settles a selection produced earlier in this render scope into the caller's
     * current colour target.
     *
     * <p>The colour pass intentionally sees the exact caller state, including the
     * caller FBO, viewport, blend/depth state, scissor, draw buffers and every
     * indexed colour mask. Only the resolve itself reads the reduction targets, so
     * the geometry keys stay valid for a later stage of the same optical pass.</p>
     */
    void renderColor(Selection selection, int viewportX, int viewportY, Pass draw) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(draw, "draw");
        if (viewportX < 0 || viewportY < 0) {
            throw new IllegalArgumentException("Invalid ringworld mesh viewport " + viewportX + ',' + viewportY);
        }
        if (selection.high() <= 0 || selection.low() <= 0) {
            throw new IllegalArgumentException("Reduction selection has no owned distance textures");
        }
        GlState colour = captureState();
        try (Binding bindings = bind(selection.high(), selection.low())) {
            draw.draw(COLOR, selection.high(), selection.low(), viewportX, viewportY);
        } finally {
            colour.restore();
        }
    }

    Binding bind(Selection selection) {
        return bind(selection.high(), selection.low());
    }

    /** Deletes only reduction targets allocated by this helper. */
    void dispose() {
        for (Targets pair : targets.values()) pair.dispose();
        targets.clear();
        renderDepth.remove();
    }

    private Binding bind(int highTexture, int lowTexture) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        Binding bindings;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + highTextureUnit);
            int highPrior = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int highSampler = SamplerBindings.get(highTextureUnit);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + lowTextureUnit);
            int lowPrior = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int lowSampler = SamplerBindings.get(lowTextureUnit);
            bindings = new Binding(active, highPrior, lowPrior, highSampler, lowSampler);
        } finally {
            GL13.glActiveTexture(active);
        }
        boolean bound = false;
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + highTextureUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, highTexture);
            GL33.glBindSampler(highTextureUnit, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + lowTextureUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowTexture);
            GL33.glBindSampler(lowTextureUnit, 0);
            GL13.glActiveTexture(active);
            bound = true;
            return bindings;
        } finally {
            if (!bound) bindings.close();
        }
    }

    private GlState captureState() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_SCISSOR_BIT | GL11.GL_VIEWPORT_BIT);
        try {
            return new GlState();
        } catch (RuntimeException | Error failure) {
            GL11.glPopAttrib();
            throw failure;
        }
    }

    private void drawReduction(Target target, int stage, int highTexture, int lowTexture,
                               int pixelOffsetX, int pixelOffsetY, Pass draw) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, target.width, target.height);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL14.glBlendEquation(GL14.GL_MIN);
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        GL30.glColorMaski(0, true, true, true, true);
        clearDistance();

        try (Binding bindings = bind(highTexture, lowTexture)) {
            draw.draw(stage, highTexture, lowTexture, pixelOffsetX, pixelOffsetY);
        }
    }

    private void clearDistance() {
        empty.clear();
        empty.put(EMPTY_DISTANCE).put(0.0F).put(0.0F).put(0.0F).flip();
        GL30.glClearBuffer(GL11.GL_COLOR, 0, empty);
    }

    private Targets targetFor(long depth, int width, int height) {
        Targets current = targets.get(depth);
        if (current != null && current.matches(width, height)) return current;

        // Allocate both candidates before publishing either. A failed resize
        // leaves a valid old pair available for a later frame, while this frame
        // propagates the allocation error instead of silently flattening it.
        Targets replacement = new Targets(width, height);
        Targets previous = targets.put(depth, replacement);
        if (previous != null) previous.dispose();
        return replacement;
    }

    private void requireSupport() {
        if (!GLContext.getCapabilities().OpenGL30) {
            throw new IllegalStateException("Ringworld mesh distance reduction requires OpenGL 3.0");
        }
        if (!GLContext.getCapabilities().OpenGL33 && !GLContext.getCapabilities().GL_ARB_sampler_objects) {
            throw new IllegalStateException("Ringworld mesh distance reduction requires sampler objects");
        }
        if (GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) < 1) {
            throw new IllegalStateException("Ringworld mesh distance reduction requires a draw buffer");
        }
        if (GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)
                <= Math.max(highTextureUnit, lowTextureUnit)) {
            throw new IllegalStateException("Ringworld mesh distance reduction requires texture units "
                    + highTextureUnit + " and " + lowTextureUnit);
        }
    }

    /** Shader/program adapter. Stages are {@link #HIGH}, {@link #LOW}, and {@link #COLOR}. */
    @FunctionalInterface
    interface Pass {
        void draw(int stage, int highTexture, int lowTexture, int pixelOffsetX, int pixelOffsetY);
    }

    record Selection(int high, int low, int width, int height) { }

    final class Binding implements AutoCloseable {
        private final int activeTexture;
        private final int highTexture;
        private final int lowTexture;
        private final int highSampler;
        private final int lowSampler;

        private Binding(int activeTexture, int highTexture, int lowTexture, int highSampler, int lowSampler) {
            this.activeTexture = activeTexture;
            this.highTexture = highTexture;
            this.lowTexture = lowTexture;
            this.highSampler = highSampler;
            this.lowSampler = lowSampler;
        }

        @Override
        public void close() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + highTextureUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, highTexture);
            GL33.glBindSampler(highTextureUnit, highSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + lowTextureUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowTexture);
            GL33.glBindSampler(lowTextureUnit, lowSampler);
            GL13.glActiveTexture(activeTexture);
        }
    }

    private final class Targets {
        private final int width;
        private final int height;
        private final Target high;
        private final Target low;

        private Targets(int width, int height) {
            this.width = width;
            this.height = height;
            Target highCandidate = null;
            Target lowCandidate = null;
            try {
                highCandidate = new Target(width, height);
                lowCandidate = new Target(width, height);
                high = highCandidate;
                low = lowCandidate;
            } catch (RuntimeException | Error failure) {
                if (lowCandidate != null) lowCandidate.dispose();
                if (highCandidate != null) highCandidate.dispose();
                throw failure;
            }
        }

        private boolean matches(int width, int height) {
            return this.width == width && this.height == height;
        }

        private void dispose() {
            high.dispose();
            low.dispose();
        }
    }

    private final class Target {
        private final int width;
        private final int height;
        private int framebuffer;
        private int texture;

        private Target(int width, int height) {
            this.width = width;
            this.height = height;
            try {
                framebuffer = GL30.glGenFramebuffers();
                if (framebuffer <= 0) throw new IllegalStateException("Ringworld mesh reduction allocated invalid framebuffer id");
                texture = texture(width, height);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, texture, 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Ringworld mesh reduction framebuffer is incomplete");
                }
            } catch (RuntimeException | Error failure) {
                dispose();
                throw failure;
            }
        }

        private int texture(int width, int height) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + highTextureUnit);
            int texture = GL11.glGenTextures();
            if (texture <= 0) throw new IllegalStateException("Ringworld mesh reduction allocated invalid texture id");
            int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            boolean allocated = false;
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, width, height, 0,
                        GL11.GL_RED, GL11.GL_FLOAT, (ByteBuffer) null);
                int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                int actualWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int actualHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (format != GL30.GL_R32F || actualWidth != width || actualHeight != height) {
                    throw new IllegalStateException("Ringworld mesh reduction texture allocation was not R32F "
                            + width + 'x' + height);
                }
                allocated = true;
                return texture;
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
                if (!allocated) GL11.glDeleteTextures(texture);
            }
        }

        private void dispose() {
            if (texture != 0) GL11.glDeleteTextures(texture);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            texture = framebuffer = 0;
        }
    }

    /** Captures state that glPushAttrib does not cover on an MRT-capable context. */
    private final class GlState {
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        private final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int highUnitTexture;
        private final int lowUnitTexture;
        private final int highUnitSampler;
        private final int lowUnitSampler;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int[] drawBuffers;
        private final boolean[] redMasks;
        private final boolean[] greenMasks;
        private final boolean[] blueMasks;
        private final boolean[] alphaMasks;
        private boolean restored;

        private GlState() {
            int savedHighTexture = 0;
            int savedLowTexture = 0;
            int savedHighSampler = 0;
            int savedLowSampler = 0;
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + highTextureUnit);
                savedHighTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                savedHighSampler = SamplerBindings.get(highTextureUnit);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + lowTextureUnit);
                savedLowTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                savedLowSampler = SamplerBindings.get(lowTextureUnit);
            } finally {
                GL13.glActiveTexture(activeTexture);
            }
            highUnitTexture = savedHighTexture;
            lowUnitTexture = savedLowTexture;
            highUnitSampler = savedHighSampler;
            lowUnitSampler = savedLowSampler;
            viewport.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            viewportX = viewport.get(0);
            viewportY = viewport.get(1);
            viewportWidth = viewport.get(2);
            viewportHeight = viewport.get(3);
            int count = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            if (count <= 0) throw new IllegalStateException("OpenGL reports no draw buffers");
            drawBuffers = new int[count];
            redMasks = new boolean[count];
            greenMasks = new boolean[count];
            blueMasks = new boolean[count];
            alphaMasks = new boolean[count];
            ByteBuffer mask = BufferUtils.createByteBuffer(4);
            for (int index = 0; index < count; index++) {
                drawBuffers[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
                mask.clear();
                org.lwjglx.opengl.GL30.glGetBoolean(GL11.GL_COLOR_WRITEMASK, index, mask);
                redMasks[index] = mask.get(0) != 0;
                greenMasks[index] = mask.get(1) != 0;
                blueMasks[index] = mask.get(2) != 0;
                alphaMasks[index] = mask.get(3) != 0;
            }
        }

        private void restore() {
            if (restored) return;
            restored = true;
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL11.glReadBuffer(readBuffer);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11.glPopAttrib();
            try {
                if (drawFramebuffer == 0) {
                    // Default framebuffer accepts exactly one buffer;
                    // passing GL_MAX_DRAW_BUFFERS is GL_INVALID_VALUE.
                    GL11.glDrawBuffer(drawBuffers[0]);
                } else {
                    int count = drawBuffers.length;
                    while (count > 1 && drawBuffers[count - 1] == GL11.GL_NONE) count--;
                    IntBuffer buffers = BufferUtils.createIntBuffer(count);
                    buffers.put(drawBuffers, 0, count).flip();
                    GL20.glDrawBuffers(buffers);
                }
            } finally {
                for (int index = 0; index < drawBuffers.length; index++) {
                    GL30.glColorMaski(index, redMasks[index], greenMasks[index], blueMasks[index], alphaMasks[index]);
                }
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + highTextureUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, highUnitTexture);
            GL33.glBindSampler(highTextureUnit, highUnitSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + lowTextureUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowUnitTexture);
            GL33.glBindSampler(lowTextureUnit, lowUnitSampler);
            GL13.glActiveTexture(activeTexture);
        }
    }
}
