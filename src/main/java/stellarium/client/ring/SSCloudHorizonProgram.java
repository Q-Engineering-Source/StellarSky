package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import stellarium.client.ring.cloud.CloudLodAtlas;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarium.render.util.SamplerBindings;
import stellarium.world.ring.RingworldSunshade;

/** Full-screen first-visible-cloud pass. It owns no framebuffer or projection. */
final class SSCloudHorizonProgram {
    private static final ResourceLocation VERTEX =
            new ResourceLocation("stellarium", "shaders/ringworld/horizon.vert");
    private static final ResourceLocation FRAGMENT =
            new ResourceLocation("stellarium", "shaders/ringworld/horizon.frag");
    private static final ResourceLocation RAW_FRAGMENT =
            new ResourceLocation("stellarium", "shaders/ringworld/horizon_raw.frag");
    private static final ResourceLocation COMPOSITE_FRAGMENT =
            new ResourceLocation("stellarium", "shaders/ringworld/horizon_composite.frag");
    private static final int ATLAS_TEXTURE_UNIT = 2;

    private int program;
    private int rawProgram;
    private int compositeProgram;
    private int atlasTexture;
    private Object atlasCache;
    private int stripZ, board, bands, bandEdge, coverage, sideFeather, motionFeather, weather;
    private int cloudOrigin0, cloudOrigin1, cloudOrigin2, cloudOrigin3, cloudOrigin4, cloudOrigin5, cloudOrigin6, cloudOrigin7, cloudOrigin8, cloudOrigin9, cloudOrigin10, cloudOrigin11, cloudOrigin12;
    private int cloudGeometry, cloudClip, cloudHorizon, cloudAtlas, cloudActive, cloudCullFlags, cloudPixelAngularSize;
    private int cloudTransitionWidths, cloudBottomBrightness, cloudTailPolicy;
    private final FloatBuffer tailPolicyValues = BufferUtils.createFloatBuffer(3);
    private final FloatBuffer inverseProjection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer inverseModelView = BufferUtils.createFloatBuffer(16);
    private int inverseProjectionUniform, inverseModelViewUniform, cameraRelativeUniform;
    private RingworldCurvedRayUniforms curvedRayUniforms;
    private RingworldDistantDepthUniforms distantDepthUniforms;
    private RingworldBoardDepthUniforms boardDepthUniforms;
    private final SSCloudTraceCacheTargets traceTargets = new SSCloudTraceCacheTargets();
    private SSCloudTraceCacheKey traceKey;
    private RawUniforms rawUniforms;
    private CompositeUniforms compositeUniforms;
    private boolean reportedTraceCacheUnavailable;
    private boolean traceCacheEnabled = true;
    private long traceCacheFirstNanos = Long.MIN_VALUE;
    private int traceCacheRawPasses;
    private int traceCacheReuses;
    private boolean traceCacheReportedFiveSeconds;
    private boolean traceCacheReportedSixtySeconds;

    void render(SSCloudFrame frame, RingworldSunshade.CameraRelativeBands bandsValue, double weatherValue) {
        if (!OpenGlHelper.shadersSupported) throw new IllegalStateException("SS horizon cloud renderer requires shaders");
        if (renderCached(frame, bandsValue, weatherValue)) return;
        renderFallback(frame, bandsValue, weatherValue);
    }

    /** Read by the renderer before it elects the bounded shared-wind pose. */
    boolean traceCacheEnabled() {
        return traceCacheEnabled;
    }

    /** The original full trace remains the explicit compatibility fallback. */
    private void renderFallback(SSCloudFrame frame, RingworldSunshade.CameraRelativeBands bandsValue, double weatherValue) {
        int previousProgram = currentProgram();
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousPixelUnpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_TEXTURE_UNIT);
        int previousAtlasBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(previousActive);
        RingworldColorMaskScope colorMasks = RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_FOG_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        try {
            ensureProgram();
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_TEXTURE_UNIT);
            ensureAtlas(frame);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            OpenGlHelper.glUseProgram(program);
            distantDepthUniforms.upload();
            boardDepthUniforms.upload();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
            uniform1i(cloudAtlas, ATLAS_TEXTURE_UNIT);
            GL13.glActiveTexture(previousActive);
            setUniforms(frame, bandsValue, weatherValue);
            try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.CLOUD_HORIZON)) {
                drawFullscreen();
            }
        } finally {
            OpenGlHelper.glUseProgram(previousProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_TEXTURE_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousAtlasBinding);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousPixelUnpack);
            GL13.glActiveTexture(previousActive);
            GL11.glPopAttrib();
            colorMasks.close();
        }
    }

    /**
     * Caches only the expensive cloud trace.  Foreground opaque limits, board
     * ownership, depth, colour, weather and light are evaluated every frame by
     * the composite program, so none of them can become stale in raw targets.
     */
    private boolean renderCached(SSCloudFrame frame, RingworldSunshade.CameraRelativeBands bandsValue, double weatherValue) {
        if (!traceCacheEnabled) return false;
        RingworldCurvatureFrame curvature = RingworldRenderSnapshots.currentDistantCurvatureFrameFor(
                frame.snapshot().world(), frame.snapshot().scene());
        if (curvature == null) return false;
        SSCloudTraceCacheKey requested = SSCloudTraceCacheKey.capture(frame, curvature);
        int viewportX = frame.camera().viewportX();
        int viewportY = frame.camera().viewportY();
        int width = frame.camera().viewportWidth();
        int height = frame.camera().viewportHeight();
        try {
            traceTargets.ensureSize(width, height);
        } catch (SSCloudTraceCacheTargets.CacheUnavailableException unavailable) {
            traceKey = null;
            traceCacheEnabled = false;
            if (!reportedTraceCacheUnavailable) {
                reportedTraceCacheUnavailable = true;
                stellarium.StellarSky.INSTANCE.getLogger().warn("SS cloud raw trace cache unavailable; using full horizon trace: {}",
                        unavailable.getMessage());
            }
            return false;
        }

        int previousProgram = currentProgram();
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousPixelUnpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_TEXTURE_UNIT);
        int previousAtlasBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousAtlasSampler = SamplerBindings.get(ATLAS_TEXTURE_UNIT);
        GL13.glActiveTexture(previousActive);
        RingworldColorMaskScope colorMasks = RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_FOG_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        try {
            ensureRawProgram();
            ensureCompositeProgram();
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_TEXTURE_UNIT);
            ensureAtlas(frame);
            boolean rawPass = !requested.equals(traceKey);
            if (rawPass) {
                OpenGlHelper.glUseProgram(rawProgram);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
                GL33.glBindSampler(ATLAS_TEXTURE_UNIT, 0);
                rawUniforms.upload(frame, curvature, viewportX, viewportY);
                GL13.glActiveTexture(previousActive);
                try (var capture = traceTargets.captureScope(viewportX, viewportY, width, height);
                     var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.CLOUD_HORIZON)) {
                    drawFullscreen();
                }
                traceKey = requested;
            }

            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            OpenGlHelper.glUseProgram(compositeProgram);
            compositeUniforms.upload(frame, curvature, bandsValue, weatherValue);
            try (var rawTextures = traceTargets.textureScope();
                 var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.CLOUD_COMPOSITE)) {
                drawFullscreen();
            }
            reportTraceCache(rawPass);
            return true;
        } finally {
            OpenGlHelper.glUseProgram(previousProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_TEXTURE_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousAtlasBinding);
            GL33.glBindSampler(ATLAS_TEXTURE_UNIT, previousAtlasSampler);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousPixelUnpack);
            GL13.glActiveTexture(previousActive);
            GL11.glPopAttrib();
            colorMasks.close();
        }
    }

    void bindAtlas(SSCloudFrame frame) {
        int previousPixelUnpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            ensureAtlas(frame);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousPixelUnpack);
        }
    }

    void dispose() {
        if (program != 0) OpenGlHelper.glDeleteProgram(program);
        if (rawProgram != 0) OpenGlHelper.glDeleteProgram(rawProgram);
        if (compositeProgram != 0) OpenGlHelper.glDeleteProgram(compositeProgram);
        if (atlasTexture != 0) GL11.glDeleteTextures(atlasTexture);
        program = rawProgram = compositeProgram = atlasTexture = 0;
        atlasCache = null;
        traceKey = null;
        rawUniforms = null;
        compositeUniforms = null;
        reportedTraceCacheUnavailable = false;
        traceCacheEnabled = true;
        traceCacheFirstNanos = Long.MIN_VALUE;
        traceCacheRawPasses = traceCacheReuses = 0;
        traceCacheReportedFiveSeconds = traceCacheReportedSixtySeconds = false;
        traceTargets.dispose();
    }

    /** Two bounded windows make reuse observable in the client log without frame-by-frame noise. */
    private void reportTraceCache(boolean rawPass) {
        if (rawPass) traceCacheRawPasses++; else traceCacheReuses++;
        long now = System.nanoTime();
        if (traceCacheFirstNanos == Long.MIN_VALUE) traceCacheFirstNanos = now;
        long elapsed = now - traceCacheFirstNanos;
        if (!traceCacheReportedFiveSeconds && elapsed >= 5_000_000_000L) {
            traceCacheReportedFiveSeconds = true;
            stellarium.StellarSky.INSTANCE.getLogger().info("SS cloud trace cache 5s: rawPasses={} reuses={}",
                    traceCacheRawPasses, traceCacheReuses);
        }
        if (!traceCacheReportedSixtySeconds && elapsed >= 60_000_000_000L) {
            traceCacheReportedSixtySeconds = true;
            stellarium.StellarSky.INSTANCE.getLogger().info("SS cloud trace cache 60s: rawPasses={} reuses={}",
                    traceCacheRawPasses, traceCacheReuses);
        }
    }

    private void setUniforms(SSCloudFrame frame, RingworldSunshade.CameraRelativeBands b, double weatherValue) {
        double observerY = frame.snapshot().observer().y();
        double observerZ = frame.snapshot().observer().z();
        uniform2f(stripZ, -8192.0D - observerZ, 8192.0D - observerZ);
        uniform2f(board, frame.snapshot().sunshadeHeightBlocks() - observerY,
                frame.snapshot().sunshadeThicknessBlocks());
        uniform4f(bands, b.directionX(), b.directionZ(), b.spacingBlocks(), b.panelWidthBlocks());
        uniform2f(bandEdge, b.edgeRelativeToRenderOriginBlocks(), b.edgeOrientation());
        uniform1i(coverage, coverage(b.coverage()));
        uniform1f(sideFeather, frame.snapshot().sunshade().sideFeatherBlocks());
        uniform1f(motionFeather, frame.snapshot().sunshade().featherBlocks());
        uniform1f(weather, weatherValue);
        frame.camera().copyInverseProjection(inverseProjection);
        frame.camera().copyInverseModelView(inverseModelView);
        uniformMatrix(inverseProjectionUniform, inverseProjection);
        uniformMatrix(inverseModelViewUniform, inverseModelView);
        uniform3f(cameraRelativeUniform, frame.camera().cameraX(), frame.camera().cameraY(), frame.camera().cameraZ());
        curvedRayUniforms.upload(RingworldRenderSnapshots.currentDistantCurvatureFrameFor(
                frame.snapshot().world(), frame.snapshot().scene()));
        setOrigins(frame);
        uniform4f(cloudCullFlags, frame.cullFine() ? 1.0D : 0.0D, frame.cullMid() ? 1.0D : 0.0D,
                frame.cullLow() ? 1.0D : 0.0D, frame.cullVeryLow() ? 1.0D : 0.0D);
        uniform1f(cloudPixelAngularSize, frame.pixelAngularSize());
        frame.tailCoverage().copyUniforms(tailPolicyValues);
        uniform3f(cloudTailPolicy, tailPolicyValues.get(0), tailPolicyValues.get(1), tailPolicyValues.get(2));
        uniform3f(cloudTransitionWidths, frame.transition().fineWidth(), frame.transition().midWidth(), frame.transition().lowWidth());
        uniform1f(cloudBottomBrightness, frame.bottomBrightness());
        uniform3f(cloudGeometry, frame.geometry().cellSizeBlocks(), frame.geometry().voxelHeightBlocks(),
                frame.baseY() - observerY);
        uniform4f(cloudClip, frame.clipBounds().lowerY() - observerY, frame.clipBounds().upperY() - observerY,
                frame.clipBounds().minWorldZ() - observerZ, frame.clipBounds().maxWorldZ() - observerZ);
        uniform1f(cloudHorizon, frame.horizon().effectiveHorizon());
        uniform1i(cloudActive, 1);
    }

    private void ensureAtlas(SSCloudFrame frame) {
        if (atlasTexture != 0 && atlasCache == frame.cache()) return;
        int candidate = GL11.glGenTextures();
        if (candidate == 0) throw new IllegalStateException("Unable to allocate SS cloud atlas");
        boolean uploaded = false;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, candidate);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            int maxTexture = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (maxTexture < CloudLodAtlas.WIDTH || maxTexture < CloudLodAtlas.HEIGHT) {
                throw new IllegalStateException("SS cloud cache atlas " + CloudLodAtlas.WIDTH + "x" + CloudLodAtlas.HEIGHT
                        + " exceeds GL_MAX_TEXTURE_SIZE=" + maxTexture + "; periodic fallback is forbidden");
            }
            ByteBuffer atlas = CloudLodAtlas.toRgba8(frame.cache());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, CloudLodAtlas.WIDTH,
                    CloudLodAtlas.HEIGHT, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, atlas);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != CloudLodAtlas.WIDTH
                    || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
                    != CloudLodAtlas.HEIGHT) {
                throw new IllegalStateException("SS cloud atlas dimensions do not match the immutable mask contract");
            }
            uploaded = true;
        } finally {
            if (!uploaded) GL11.glDeleteTextures(candidate);
        }
        if (atlasTexture != 0) GL11.glDeleteTextures(atlasTexture);
        atlasTexture = candidate;
        atlasCache = frame.cache();
    }

    /** Page origins are camera-relative blocks, preserving float precision at arbitrary world coordinates. */
    private void setOrigins(SSCloudFrame frame) {
        int[] uniforms = {cloudOrigin0, cloudOrigin1, cloudOrigin2, cloudOrigin3, cloudOrigin4,
                cloudOrigin5, cloudOrigin6, cloudOrigin7, cloudOrigin8, cloudOrigin9, cloudOrigin10, cloudOrigin11, cloudOrigin12};
        double observerX = frame.snapshot().observer().x();
        double observerZ = frame.snapshot().observer().z();
        double wind = frame.meshMotion().windOffsetBlocks();
        for (int i = 0; i < uniforms.length; i++) {
            CloudLodLayout.AtlasLevel level = CloudLodLayout.ATLAS_LEVELS.get(i);
            uniform2f(uniforms[i], frame.pageOriginX(level), frame.pageOriginZ(level));
        }
    }

    private void ensureProgram() {
        if (program != 0) return;
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(VERTEX, OpenGlHelper.GL_VERTEX_SHADER, false);
            fragment = compile(FRAGMENT, OpenGlHelper.GL_FRAGMENT_SHADER, true);
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) throw new IllegalStateException("Unable to allocate SS horizon cloud program");
            OpenGlHelper.glAttachShader(candidate, vertex); OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to link SS horizon cloud shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            detach(candidate, vertex); detach(candidate, fragment);
            final int linkedProgram = candidate;
            bindUniforms(name -> OpenGlHelper.glGetUniformLocation(linkedProgram, name));
            program = candidate; candidate = 0;
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        } finally {
            if (candidate != 0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex != 0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    private void ensureRawProgram() {
        if (rawProgram != 0) return;
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(VERTEX, OpenGlHelper.GL_VERTEX_SHADER, false);
            fragment = compileRaw(RAW_FRAGMENT, OpenGlHelper.GL_FRAGMENT_SHADER);
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) throw new IllegalStateException("Unable to allocate SS raw horizon cloud program");
            OpenGlHelper.glAttachShader(candidate, vertex); OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to link SS raw horizon cloud shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            detach(candidate, vertex); detach(candidate, fragment);
            final int linked = candidate;
            bindRawUniforms(name -> OpenGlHelper.glGetUniformLocation(linked, name));
            rawProgram = candidate; candidate = 0;
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        } finally {
            if (candidate != 0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex != 0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    private void ensureCompositeProgram() {
        if (compositeProgram != 0) return;
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(VERTEX, OpenGlHelper.GL_VERTEX_SHADER, false);
            fragment = compileStyle(COMPOSITE_FRAGMENT, OpenGlHelper.GL_FRAGMENT_SHADER);
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) throw new IllegalStateException("Unable to allocate SS cloud composite program");
            OpenGlHelper.glAttachShader(candidate, vertex); OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to link SS cloud composite shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            detach(candidate, vertex); detach(candidate, fragment);
            final int linked = candidate;
            bindCompositeUniforms(name -> OpenGlHelper.glGetUniformLocation(linked, name));
            compositeProgram = candidate; candidate = 0;
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        } finally {
            if (candidate != 0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex != 0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    void bindUniforms(ToIntFunction<String> lookup) {
        stripZ = uniform(lookup, "uStripZ"); board = uniform(lookup, "uBoard");
        bands = uniform(lookup, "uBands"); bandEdge = uniform(lookup, "uBandEdge"); coverage = uniform(lookup, "uCoverage");
        sideFeather = uniform(lookup, "uSideFeather"); motionFeather = uniform(lookup, "uMotionFeather"); weather = uniform(lookup, "uWeather");
        cloudOrigin0 = uniform(lookup, "uCloudOrigin0"); cloudOrigin1 = uniform(lookup, "uCloudOrigin1"); cloudOrigin2 = uniform(lookup, "uCloudOrigin2"); cloudOrigin3 = uniform(lookup, "uCloudOrigin3"); cloudOrigin4 = uniform(lookup, "uCloudOrigin4");
        cloudOrigin5 = uniform(lookup, "uCloudOrigin5"); cloudOrigin6 = uniform(lookup, "uCloudOrigin6"); cloudOrigin7 = uniform(lookup, "uCloudOrigin7"); cloudOrigin8 = uniform(lookup, "uCloudOrigin8"); cloudOrigin9 = uniform(lookup, "uCloudOrigin9"); cloudOrigin10 = uniform(lookup, "uCloudOrigin10"); cloudOrigin11 = uniform(lookup, "uCloudOrigin11"); cloudOrigin12 = uniform(lookup, "uCloudOrigin12"); cloudGeometry = uniform(lookup, "uCloudGeometry"); cloudClip = uniform(lookup, "uCloudClip");
        cloudHorizon = uniform(lookup, "uCloudHorizon"); cloudAtlas = uniform(lookup, "uCloudAtlas");
        cloudActive = uniform(lookup, "uCloudActive");
        cloudCullFlags = uniform(lookup, "uCloudCullFlags");
        cloudPixelAngularSize = uniform(lookup, "uCloudPixelAngularSize");
        cloudTailPolicy = uniform(lookup, "uCloudTailPolicy");
        cloudTransitionWidths = uniform(lookup, "uCloudTransitionWidths");
        cloudBottomBrightness = uniform(lookup, "uCloudBottomBrightness");
        inverseProjectionUniform = uniform(lookup, "uInverseProjection");
        inverseModelViewUniform = uniform(lookup, "uInverseModelView");
        cameraRelativeUniform = uniform(lookup, "uCameraRelative");
        curvedRayUniforms = new RingworldCurvedRayUniforms(lookup);
        distantDepthUniforms = new RingworldDistantDepthUniforms(lookup);
        boardDepthUniforms = new RingworldBoardDepthUniforms(lookup);
    }
    void bindRawUniforms(ToIntFunction<String> lookup) { rawUniforms = new RawUniforms(lookup); }
    void bindCompositeUniforms(ToIntFunction<String> lookup) { compositeUniforms = new CompositeUniforms(lookup); }
    private static int uniform(ToIntFunction<String> lookup, String name) { int result = lookup.applyAsInt(name); if (result < 0) throw new IllegalStateException("SS horizon shader lacks " + name); return result; }
    private static int compile(ResourceLocation location, int type, boolean cloudQuery) {
        int shader = OpenGlHelper.glCreateShader(type); if (shader == 0) throw new IllegalStateException("Unable to allocate " + location);
        try { byte[] source = cloudQuery ? RingworldShaderSource.readWithCloudQuery(location) : RingworldShaderSource.readCurved(location);
            ByteBuffer bytes = BufferUtils.createByteBuffer(source.length).put(source); bytes.flip(); OpenGlHelper.glShaderSource(shader, bytes); OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) throw new IllegalStateException("Unable to compile " + location + ": " + OpenGlHelper.glGetShaderInfoLog(shader, 32768)); return shader;
        } catch (RuntimeException exception) { OpenGlHelper.glDeleteShader(shader); throw exception; }
    }
    private static int compileRaw(ResourceLocation location, int type) {
        return compileSource(location, type, RingworldShaderSource.readWithRawCloudQuery(location));
    }
    private static int compileStyle(ResourceLocation location, int type) {
        return compileSource(location, type, RingworldShaderSource.readCloudComposite(location));
    }
    private static int compileSource(ResourceLocation location, int type, byte[] source) {
        int shader = OpenGlHelper.glCreateShader(type); if (shader == 0) throw new IllegalStateException("Unable to allocate " + location);
        try { ByteBuffer bytes = BufferUtils.createByteBuffer(source.length).put(source); bytes.flip(); OpenGlHelper.glShaderSource(shader, bytes); OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) throw new IllegalStateException("Unable to compile " + location + ": " + OpenGlHelper.glGetShaderInfoLog(shader, 32768)); return shader;
        } catch (RuntimeException exception) { OpenGlHelper.glDeleteShader(shader); throw exception; }
    }

    /** Uniforms whose values determine the raw trace cache key. */
    private static final class RawUniforms {
        private final int[] origins = new int[13];
        private final int geometry, clip, horizon, atlas, active, cull, pixelAngular, tail, transition, rawOffset;
        private final int inverseProjection, inverseModelView, camera;
        private final RingworldCurvedRayUniforms curved;
        private final FloatBuffer inverseProjectionValues = BufferUtils.createFloatBuffer(16);
        private final FloatBuffer inverseModelViewValues = BufferUtils.createFloatBuffer(16);
        private final FloatBuffer tailValues = BufferUtils.createFloatBuffer(3);

        private RawUniforms(ToIntFunction<String> lookup) {
            for (int index = 0; index < origins.length; index++) origins[index] = uniform(lookup, "uCloudOrigin" + index);
            geometry = uniform(lookup, "uCloudGeometry"); clip = uniform(lookup, "uCloudClip"); horizon = uniform(lookup, "uCloudHorizon");
            atlas = uniform(lookup, "uCloudAtlas"); active = uniform(lookup, "uCloudActive"); cull = uniform(lookup, "uCloudCullFlags");
            pixelAngular = uniform(lookup, "uCloudPixelAngularSize"); tail = uniform(lookup, "uCloudTailPolicy");
            transition = uniform(lookup, "uCloudTransitionWidths");
            rawOffset = uniform(lookup, "uCloudRawPixelOffset"); inverseProjection = uniform(lookup, "uInverseProjection");
            inverseModelView = uniform(lookup, "uInverseModelView"); camera = uniform(lookup, "uCameraRelative");
            curved = new RingworldCurvedRayUniforms(lookup);
        }

        private void upload(SSCloudFrame frame, RingworldCurvatureFrame curvature, int viewportX, int viewportY) {
            frame.camera().copyInverseProjection(inverseProjectionValues); frame.camera().copyInverseModelView(inverseModelViewValues);
            uniformMatrix(inverseProjection, inverseProjectionValues); uniformMatrix(inverseModelView, inverseModelViewValues);
            uniform3f(camera, frame.camera().cameraX(), frame.camera().cameraY(), frame.camera().cameraZ());
            uniform1i(atlas, ATLAS_TEXTURE_UNIT); uniform1i(active, 1); uniform2f(rawOffset, viewportX, viewportY);
            for (int index = 0; index < origins.length; index++) {
                CloudLodLayout.AtlasLevel level = CloudLodLayout.ATLAS_LEVELS.get(index);
                uniform2f(origins[index], frame.pageOriginX(level), frame.pageOriginZ(level));
            }
            double observerY = frame.snapshot().observer().y(), observerZ = frame.snapshot().observer().z();
            uniform3f(geometry, frame.geometry().cellSizeBlocks(), frame.geometry().voxelHeightBlocks(), frame.baseY() - observerY);
            uniform4f(clip, frame.clipBounds().lowerY() - observerY, frame.clipBounds().upperY() - observerY,
                    frame.clipBounds().minWorldZ() - observerZ, frame.clipBounds().maxWorldZ() - observerZ);
            uniform1f(horizon, frame.horizon().effectiveHorizon());
            uniform4f(cull, frame.cullFine() ? 1.0 : 0.0, frame.cullMid() ? 1.0 : 0.0,
                    frame.cullLow() ? 1.0 : 0.0, frame.cullVeryLow() ? 1.0 : 0.0);
            uniform1f(pixelAngular, frame.pixelAngularSize()); frame.tailCoverage().copyUniforms(tailValues);
            uniform3f(tail, tailValues.get(0), tailValues.get(1), tailValues.get(2));
            uniform3f(transition, frame.transition().fineWidth(), frame.transition().midWidth(), frame.transition().lowWidth());
            curved.upload(curvature);
        }
    }

    /** Uniforms intentionally evaluated after every raw-cache reuse. */
    private static final class CompositeUniforms {
        private final int stripZ, board, bands, bandEdge, coverage, sideFeather, motionFeather, weather;
        private final int inverseProjection, inverseModelView, camera, geometryTexture, materialTexture, bottom;
        private final RingworldCurvedRayUniforms curved;
        private final RingworldDistantDepthUniforms distant;
        private final RingworldBoardDepthUniforms boardDepth;
        private final FloatBuffer inverseProjectionValues = BufferUtils.createFloatBuffer(16);
        private final FloatBuffer inverseModelViewValues = BufferUtils.createFloatBuffer(16);

        private CompositeUniforms(ToIntFunction<String> lookup) {
            stripZ = uniform(lookup, "uStripZ"); board = uniform(lookup, "uBoard"); bands = uniform(lookup, "uBands");
            bandEdge = uniform(lookup, "uBandEdge"); coverage = uniform(lookup, "uCoverage"); sideFeather = uniform(lookup, "uSideFeather");
            motionFeather = uniform(lookup, "uMotionFeather"); weather = uniform(lookup, "uWeather");
            inverseProjection = uniform(lookup, "uInverseProjection"); inverseModelView = uniform(lookup, "uInverseModelView");
            camera = uniform(lookup, "uCameraRelative"); geometryTexture = uniform(lookup, "uCloudTraceGeometry");
            materialTexture = uniform(lookup, "uCloudTraceMaterial"); bottom = uniform(lookup, "uCloudBottomBrightness");
            curved = new RingworldCurvedRayUniforms(lookup); distant = new RingworldDistantDepthUniforms(lookup);
            boardDepth = new RingworldBoardDepthUniforms(lookup);
        }

        private void upload(SSCloudFrame frame, RingworldCurvatureFrame curvature,
                            RingworldSunshade.CameraRelativeBands b, double weatherValue) {
            double observerY = frame.snapshot().observer().y(), observerZ = frame.snapshot().observer().z();
            uniform2f(stripZ, -8192.0D - observerZ, 8192.0D - observerZ);
            uniform2f(board, frame.snapshot().sunshadeHeightBlocks() - observerY, frame.snapshot().sunshadeThicknessBlocks());
            uniform4f(bands, b.directionX(), b.directionZ(), b.spacingBlocks(), b.panelWidthBlocks());
            uniform2f(bandEdge, b.edgeRelativeToRenderOriginBlocks(), b.edgeOrientation());
            uniform1i(coverage, coverage(b.coverage())); uniform1f(sideFeather, frame.snapshot().sunshade().sideFeatherBlocks());
            uniform1f(motionFeather, frame.snapshot().sunshade().featherBlocks()); uniform1f(weather, weatherValue);
            frame.camera().copyInverseProjection(inverseProjectionValues); frame.camera().copyInverseModelView(inverseModelViewValues);
            uniformMatrix(inverseProjection, inverseProjectionValues); uniformMatrix(inverseModelView, inverseModelViewValues);
            uniform3f(camera, frame.camera().cameraX(), frame.camera().cameraY(), frame.camera().cameraZ());
            uniform1i(geometryTexture, 1); uniform1i(materialTexture, 2); uniform1f(bottom, frame.bottomBrightness());
            curved.upload(curvature); distant.upload(); boardDepth.upload();
        }
    }
    private static int coverage(RingworldSunshade.BandCoverage value) { return switch (value) { case EMPTY -> 0; case PARTIAL -> 1; case FULL -> 2; }; }
    private static int currentProgram() { return OpenGlHelper.openGL21 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB); }
    private static void detach(int p, int s) { if (OpenGlHelper.openGL21) GL20.glDetachShader(p, s); else ARBShaderObjects.glDetachObjectARB(p, s); }
    private static void uniform1i(int l, int v) { if (OpenGlHelper.openGL21) GL20.glUniform1i(l, v); else ARBShaderObjects.glUniform1iARB(l, v); }
    private static void uniform1f(int l, double v) { if (OpenGlHelper.openGL21) GL20.glUniform1f(l, (float) v); else ARBShaderObjects.glUniform1fARB(l, (float) v); }
    private static void uniform2f(int l, double x, double y) { if (OpenGlHelper.openGL21) GL20.glUniform2f(l, (float)x,(float)y); else ARBShaderObjects.glUniform2fARB(l,(float)x,(float)y); }
    private static void uniform3f(int l, double x, double y, double z) { if (OpenGlHelper.openGL21) GL20.glUniform3f(l,(float)x,(float)y,(float)z); else ARBShaderObjects.glUniform3fARB(l,(float)x,(float)y,(float)z); }
    private static void uniform4f(int l, double x, double y, double z, double w) { if (OpenGlHelper.openGL21) GL20.glUniform4f(l,(float)x,(float)y,(float)z,(float)w); else ARBShaderObjects.glUniform4fARB(l,(float)x,(float)y,(float)z,(float)w); }
    private static void uniformMatrix(int l, FloatBuffer value) { if (OpenGlHelper.openGL21) GL20.glUniformMatrix4(l, false, value); else ARBShaderObjects.glUniformMatrix4ARB(l, false, value); }
    private static void drawFullscreen() { GL11.glBegin(GL11.GL_QUADS); try { GL11.glVertex2f(-1, -1); GL11.glVertex2f(1, -1); GL11.glVertex2f(1, 1); GL11.glVertex2f(-1, 1); } finally { GL11.glEnd(); } }
}
