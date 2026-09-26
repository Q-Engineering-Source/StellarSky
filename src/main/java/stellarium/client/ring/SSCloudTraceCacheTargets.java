package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjglx.opengl.GLContext;

import stellarium.render.util.SamplerBindings;

/**
 * Instance-owned raw cloud trace cache: normal/lambda and material/censorship
 * are retained in two nearest-sampled {@code RGBA32F} attachments.  It does
 * not decide when a cache is valid; that key and fallback policy belong to the
 * horizon renderer which owns this instance.
 */
final class SSCloudTraceCacheTargets {
    private static final int GEOMETRY_UNIT = 1;
    private static final int MATERIAL_UNIT = 2;
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final int BYTES_PER_PIXEL = 2 * 4 * Float.BYTES;
    private static final IntBuffer DRAW_ATTACHMENTS = BufferUtils.createIntBuffer(3);
    private static final FloatBuffer CLEAR_VALUE = BufferUtils.createFloatBuffer(4);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(4);

    private Targets targets;
    private boolean captureActive;

    /** Side-effect-free; allocation begins only when {@link #ensureSize(int, int)} is called. */
    SSCloudTraceCacheTargets() {
    }

    /**
     * Ensures exact-size raw targets.  It never reduces resolution or changes
     * filtering to fit memory: the owning renderer must explicitly fall back
     * if this throws {@link CacheUnavailableException}.
     */
    void ensureSize(int width, int height) {
        if (captureActive) throw new IllegalStateException("Cannot resize SS cloud trace cache during capture");
        validateDimensions(width, height);
        requireSupport();
        if (targets != null && targets.matches(width, height)) return;

        GlState caller = GlState.capture();
        try {
            Targets candidate = new Targets(width, height);
            Targets prior = targets;
            targets = candidate;
            if (prior != null) prior.dispose();
        } finally {
            caller.restore();
        }
    }

    /**
     * Binds the private two-attachment FBO for a raw full-coverage trace pass.
     * The cache FBO has no depth or stencil attachment.  Its viewport always
     * starts at zero; the caller's viewport origin is deliberately not reused.
     */
    CaptureScope captureScope(int viewportX, int viewportY, int width, int height) {
        if (captureActive) throw new IllegalStateException("Nested SS cloud trace cache capture is forbidden");
        if (viewportX < 0 || viewportY < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid SS cloud trace viewport " + viewportX + ',' + viewportY
                    + ' ' + width + 'x' + height);
        }
        if (targets == null || !targets.matches(width, height)) {
            throw new CacheUnavailableException("SS cloud trace cache is not allocated for " + width + 'x' + height);
        }
        verifyCallerViewport(viewportX, viewportY, width, height);
        GlState caller = GlState.capture();
        boolean configured = false;
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targets.framebuffer);
            drawAttachments();
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_POLYGON_STIPPLE);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            for (int plane = 0; plane < 6; plane++) GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL30.glColorMaski(1, true, true, true, true);
            GL30.glColorMaski(2, true, true, true, true);
            // These are draw-buffer indices, mapped below to our two owned textures.
            // Clear only on capture, never on reuse; an interrupted/partial draw cannot
            // retain a preceding camera's cloud hit in the freshly published image.
            CLEAR_VALUE.clear();
            CLEAR_VALUE.put(0).put(0).put(0).put(-1).flip();
            GL30.glClearBuffer(GL11.GL_COLOR, 1, CLEAR_VALUE);
            CLEAR_VALUE.clear();
            CLEAR_VALUE.put(0).put(0).put(0).put(0).flip();
            GL30.glClearBuffer(GL11.GL_COLOR, 2, CLEAR_VALUE);
            captureActive = true;
            configured = true;
            return new CaptureScope(caller);
        } finally {
            if (!configured) caller.restore();
        }
    }

    /** Binds the completed raw attachments to units one and two with sampler object zero. */
    TextureScope textureScope() {
        if (captureActive) throw new IllegalStateException("Cannot sample SS cloud trace cache during its capture pass");
        if (targets == null) throw new CacheUnavailableException("SS cloud trace cache has no allocated targets");
        return TextureScope.bind(targets.geometryTexture, targets.materialTexture);
    }

    /** Deletes only this instance's private framebuffer and textures. */
    void dispose() {
        if (captureActive) throw new IllegalStateException("Cannot dispose SS cloud trace cache during capture");
        if (targets != null) {
            targets.dispose();
            targets = null;
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new CacheUnavailableException("SS cloud trace cache dimensions must be positive");
        }
        long bytes = (long) width * (long) height * BYTES_PER_PIXEL;
        if (bytes > MAX_BYTES) {
            throw new CacheUnavailableException("SS cloud trace cache " + width + 'x' + height + " requires "
                    + bytes + " bytes, exceeding the " + MAX_BYTES + " byte raw-target cap");
        }
    }

    private static void requireSupport() {
        if (!GLContext.getCapabilities().OpenGL30) {
            throw new CacheUnavailableException("SS cloud trace cache requires OpenGL 3.0 framebuffer and RGBA32F support");
        }
        if (!GLContext.getCapabilities().OpenGL33 && !GLContext.getCapabilities().GL_ARB_sampler_objects) {
            throw new CacheUnavailableException("SS cloud trace cache requires sampler-object support");
        }
        if (GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) < 3) {
            throw new CacheUnavailableException("SS cloud trace cache requires three output slots for two data attachments");
        }
        if (GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) <= MATERIAL_UNIT) {
            throw new CacheUnavailableException("SS cloud trace cache requires texture units "
                    + GEOMETRY_UNIT + " and " + MATERIAL_UNIT);
        }
    }

    private static void verifyCallerViewport(int viewportX, int viewportY, int width, int height) {
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        if (VIEWPORT.get(0) != viewportX || VIEWPORT.get(1) != viewportY
                || VIEWPORT.get(2) != width || VIEWPORT.get(3) != height) {
            throw new IllegalStateException("SS cloud trace cache viewport does not match the current caller viewport");
        }
    }

    private static void drawAttachments() {
        copyDrawBufferLayout(DRAW_ATTACHMENTS);
        GL20.glDrawBuffers(DRAW_ATTACHMENTS);
    }

    /** The runtime draw mapping is also consumed by the offline fragment-output replay. */
    static void copyDrawBufferLayout(IntBuffer target) {
        if (target == null || target.capacity() < 3) throw new IllegalArgumentException("Raw cloud output mapping requires three slots");
        target.clear();
        target.put(GL11.GL_NONE).put(GL30.GL_COLOR_ATTACHMENT0).put(GL30.GL_COLOR_ATTACHMENT1).flip();
    }

    static final class CacheUnavailableException extends IllegalStateException {
        CacheUnavailableException(String message) {
            super(message);
        }
    }

    final class CaptureScope implements AutoCloseable {
        private final GlState caller;
        private boolean closed;

        private CaptureScope(GlState caller) {
            this.caller = caller;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                caller.restore();
            } finally {
                captureActive = false;
            }
        }
    }

    static final class TextureScope implements AutoCloseable {
        private final int activeTexture;
        private final int geometryTexture;
        private final int materialTexture;
        private final int geometrySampler;
        private final int materialSampler;
        private boolean closed;

        private TextureScope(int activeTexture, int geometryTexture, int materialTexture,
                             int geometrySampler, int materialSampler) {
            this.activeTexture = activeTexture;
            this.geometryTexture = geometryTexture;
            this.materialTexture = materialTexture;
            this.geometrySampler = geometrySampler;
            this.materialSampler = materialSampler;
        }

        private static TextureScope bind(int geometry, int material) {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            TextureScope scope = null;
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + GEOMETRY_UNIT);
                int geometryPrior = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                int geometrySampler = SamplerBindings.get(GEOMETRY_UNIT);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + MATERIAL_UNIT);
                int materialPrior = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                int materialSampler = SamplerBindings.get(MATERIAL_UNIT);
                scope = new TextureScope(active, geometryPrior, materialPrior, geometrySampler, materialSampler);
            } finally {
                GL13.glActiveTexture(active);
            }
            boolean bound = false;
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + GEOMETRY_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, geometry);
                GL33.glBindSampler(GEOMETRY_UNIT, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + MATERIAL_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, material);
                GL33.glBindSampler(MATERIAL_UNIT, 0);
                GL13.glActiveTexture(active);
                bound = true;
                return scope;
            } finally {
                if (!bound && scope != null) scope.close();
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GEOMETRY_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, geometryTexture);
            GL33.glBindSampler(GEOMETRY_UNIT, geometrySampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MATERIAL_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, materialTexture);
            GL33.glBindSampler(MATERIAL_UNIT, materialSampler);
            GL13.glActiveTexture(activeTexture);
        }
    }

    private static final class Targets {
        private final int width;
        private final int height;
        private int framebuffer;
        private int geometryTexture;
        private int materialTexture;

        private Targets(int width, int height) {
            this.width = width;
            this.height = height;
            try {
                int maxTexture = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
                if (width > maxTexture || height > maxTexture) {
                    throw new CacheUnavailableException("SS cloud trace cache " + width + 'x' + height
                            + " exceeds GL_MAX_TEXTURE_SIZE=" + maxTexture);
                }
                framebuffer = GL30.glGenFramebuffers();
                if (framebuffer <= 0) throw new CacheUnavailableException("Unable to allocate SS cloud trace framebuffer");
                geometryTexture = texture(width, height);
                materialTexture = texture(width, height);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, geometryTexture, 0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                        GL11.GL_TEXTURE_2D, materialTexture, 0);
                drawAttachments();
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new CacheUnavailableException("SS cloud trace framebuffer is incomplete");
                }
            } catch (RuntimeException | Error failure) {
                dispose();
                throw failure;
            }
        }

        private boolean matches(int width, int height) {
            return this.width == width && this.height == height;
        }

        private static int texture(int width, int height) {
            int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            int texture = GL11.glGenTextures();
            if (texture <= 0) throw new CacheUnavailableException("Unable to allocate SS cloud trace texture");
            boolean allocated = false;
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + GEOMETRY_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, height, 0,
                        GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
                int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                int actualWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int actualHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (format != GL30.GL_RGBA32F || actualWidth != width || actualHeight != height) {
                    throw new CacheUnavailableException("SS cloud trace texture allocation was not RGBA32F "
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
            if (geometryTexture != 0) GL11.glDeleteTextures(geometryTexture);
            if (materialTexture != 0) GL11.glDeleteTextures(materialTexture);
            if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
            geometryTexture = materialTexture = framebuffer = 0;
        }
    }

    /** Restores the fixed-function and MRT state that raw cache capture changes. */
    private static final class GlState {
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        private final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        private final boolean rasterizerDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int geometryUnitTexture;
        private final int materialUnitTexture;
        private final int geometryUnitSampler;
        private final int materialUnitSampler;
        private final int viewportX;
        private final int viewportY;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int[] drawBuffers;
        private final RingworldColorMaskScope colorMasks;
        private boolean restored;

        private GlState() {
            int savedGeometryTexture = 0;
            int savedMaterialTexture = 0;
            int savedGeometrySampler = 0;
            int savedMaterialSampler = 0;
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + GEOMETRY_UNIT);
                savedGeometryTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                savedGeometrySampler = SamplerBindings.get(GEOMETRY_UNIT);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + MATERIAL_UNIT);
                savedMaterialTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                savedMaterialSampler = SamplerBindings.get(MATERIAL_UNIT);
            } finally {
                GL13.glActiveTexture(activeTexture);
            }
            geometryUnitTexture = savedGeometryTexture;
            materialUnitTexture = savedMaterialTexture;
            geometryUnitSampler = savedGeometrySampler;
            materialUnitSampler = savedMaterialSampler;
            VIEWPORT.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
            viewportX = VIEWPORT.get(0);
            viewportY = VIEWPORT.get(1);
            viewportWidth = VIEWPORT.get(2);
            viewportHeight = VIEWPORT.get(3);
            int count = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            if (count <= 0) throw new CacheUnavailableException("SS cloud trace cache has no draw buffers");
            drawBuffers = new int[count];
            for (int index = 0; index < count; index++) drawBuffers[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
            colorMasks = RingworldColorMaskScope.capture();
        }

        private static GlState capture() {
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                    | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_FOG_BIT | GL11.GL_SCISSOR_BIT | GL11.GL_VIEWPORT_BIT
                    | GL11.GL_POLYGON_BIT);
            try {
                return new GlState();
            } catch (RuntimeException | Error failure) {
                GL11.glPopAttrib();
                throw failure;
            }
        }

        private void restore() {
            if (restored) return;
            restored = true;
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                GL11.glReadBuffer(readBuffer);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
                GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            } finally {
                try {
                    GL11.glPopAttrib();
                } finally {
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
                        try {
                            colorMasks.close();
                        } finally {
                            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GEOMETRY_UNIT);
                            GL11.glBindTexture(GL11.GL_TEXTURE_2D, geometryUnitTexture);
                            GL33.glBindSampler(GEOMETRY_UNIT, geometryUnitSampler);
                            GL13.glActiveTexture(GL13.GL_TEXTURE0 + MATERIAL_UNIT);
                            GL11.glBindTexture(GL11.GL_TEXTURE_2D, materialUnitTexture);
                            GL33.glBindSampler(MATERIAL_UNIT, materialUnitSampler);
                            if (rasterizerDiscard) GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                            else GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                            GL13.glActiveTexture(activeTexture);
                        }
                    }
                }
            }
        }
    }
}
