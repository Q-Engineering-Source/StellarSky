package stellarium.client.ring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;

import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import stellarium.StellarSky;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldSunshade;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudWorldCache;

/** Owns only compositor textures/program; it never owns Minecraft's framebuffer. */
final class RingworldSpatialAirProgram {
    private static final ResourceLocation VERTEX = new ResourceLocation("stellarium", "shaders/ringworld/spatial_air.vert");
    private static final ResourceLocation FRAGMENT = new ResourceLocation("stellarium", "shaders/ringworld/spatial_air.frag");
    private static final ResourceLocation LOCAL_AIR_FRAGMENT = new ResourceLocation("stellarium", "shaders/ringworld/spatial_air_local.frag");
    private static final int MAX_DISTANCE_BLOCKS = 65_536;
    private static final int LOCAL_AIR_DISTANCE_BLOCKS = 2_048;

    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer inverseProjection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer inverseModelView = BufferUtils.createFloatBuffer(16);
    private final float[] inversionSource = new float[16];
    private final float[] inversionResult = new float[16];
    private final double[] cameraRelative = new double[3];
    private final boolean curvedOnly;
    private final boolean localOnly;
    private int program;
    private int sceneCopy;
    private int depthCopy;
    private int width;
    private int height;
    private int sceneSampler;
    private int depthSampler;
    private int inverseProjectionUniform;
    private int inverseModelViewUniform;
    private int localEyeRelativeUniform;
    private int snapshotFootYZUniform;
    private int cameraRelativeUniform;
    private int airProfileUniform;
    private int stripUniform;
    private int boardUniform;
    private int bandsUniform;
    private int bandEdgeUniform;
    private int coverageUniform;
    private int sideFeatherUniform;
    private int motionFeatherUniform;
    private int maxDistanceUniform;
    private int localAirDistanceUniform;
    private int bandMeanTransmissionUniform;
    private int sigmaTUniform;
    private int sigmaSUniform;
    private int sunRadianceUniform;
    private int cloudOrigin0Uniform, cloudOrigin1Uniform, cloudOrigin2Uniform, cloudOrigin3Uniform, cloudOrigin4Uniform,
            cloudOrigin5Uniform, cloudOrigin6Uniform, cloudOrigin7Uniform, cloudOrigin8Uniform, cloudOrigin9Uniform,
            cloudOrigin10Uniform, cloudOrigin11Uniform, cloudOrigin12Uniform,
            cloudGeometryUniform, cloudClipUniform, cloudHorizonUniform,
            cloudAtlasUniform, cloudActiveUniform, cloudCullFlagsUniform, cloudPixelAngularSizeUniform;
    private boolean reducedQualityReported;
    private int cloudTransitionWidthsUniform;
    private int cloudTailPolicyUniform;
    private final FloatBuffer tailPolicyValues = BufferUtils.createFloatBuffer(3);
    private boolean reportedCloudViewportMismatch;
    private RingworldCurvedRayUniforms curvedRayUniforms;
    private RingworldDistantDepthUniforms distantDepthUniforms;
    private RingworldOwnMediaDepthUniforms ownMediaDepthUniforms;

    RingworldSpatialAirProgram() {
        this(false, false);
    }

    RingworldSpatialAirProgram(boolean curvedOnly) {
        this(curvedOnly, false);
    }

    /**
     * Selects the compact curved near-air compositor.  It retains the frozen
     * curved depth sources, but only integrates the first local-air window.
     */
    RingworldSpatialAirProgram(boolean curvedOnly, boolean localOnly) {
        if (localOnly && !curvedOnly) {
            throw new IllegalArgumentException("local spatial air requires a curved optical frame");
        }
        this.curvedOnly = curvedOnly;
        this.localOnly = localOnly;
    }

    void render(int viewportX, int viewportY, int viewportWidth, int viewportHeight,
                RingworldSpatialAirFrameOptics optics, SSCloudFrame cloudFrame, RingworldCurvatureFrame curvatureFrame) {
        if (viewportWidth <= 0 || viewportHeight <= 0) throw new IllegalStateException("empty spatial-air viewport");
        if (cloudFrame != null && !cloudFrame.camera().matchesViewport(viewportX, viewportY, viewportWidth, viewportHeight)) {
            // Do not mix an already-drawn cloud frame with newly reconstructed
            // rays. Skip this invalid composition scope; the next frame recovers.
            if (!reportedCloudViewportMismatch) {
                StellarSky.INSTANCE.getLogger().warn("SS spatial-air skipped a frame with a changed cloud viewport");
                reportedCloudViewportMismatch = true;
            }
            return;
        }
        RingworldDisplaySnapshot snapshot = optics.snapshot();
        if (snapshot == null || optics.queries() == null) throw new IllegalStateException("unfrozen spatial-air frame");
        if (curvedOnly != (curvatureFrame != null)) {
            throw new IllegalStateException("spatial-air program variant does not match the frozen curvature frame");
        }
        ensureProgram();
        ensureTextures(viewportWidth, viewportHeight);
        try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.AIR_COPY)) {
            copyFramebuffer(viewportX, viewportY, viewportWidth, viewportHeight);
        }

        if (cloudFrame != null) {
            useCamera(cloudFrame.camera());
        } else {
            readInverseMatrices();
        }
        RingworldSunshade.CameraRelativeBands bands = snapshot.sunshade().cameraRelativeBands(snapshot.phase(),
                snapshot.observer().x(), snapshot.observer().z());
        double limitedDistance = MAX_DISTANCE_BLOCKS;
        if (!localOnly && bands.coverage() == RingworldSunshade.BandCoverage.PARTIAL) {
            double featureDistance = RingworldSpatialAirMath.clampDistanceBudget(limitedDistance,
                    bands.spacingBlocks(), bands.gapWidthBlocks());
			if (featureDistance < MAX_DISTANCE_BLOCKS && !reducedQualityReported) {
				StellarSky.INSTANCE.getLogger().warn("Ringworld spatial-air uses periodic mean lighting on long residual "
						+ "segments for short board periods; feature distance {} blocks. Opaque ray endpoints are not shortened.", featureDistance);
				reducedQualityReported = true;
			}
        }
        if (!localOnly && (!(limitedDistance > 0.0) || !Double.isFinite(limitedDistance))) {
            throw new IllegalStateException("spatial-air distance budget is invalid");
        }

        OpenGlHelper.glUseProgram(program);
        try {
            if (localOnly) {
                if (curvatureFrame == null) throw new IllegalStateException("local spatial air requires a curved optical frame");
                uniform3f(localEyeRelativeUniform, curvatureFrame.cameraX(), curvatureFrame.cameraY(), curvatureFrame.cameraZ());
            } else {
                curvedRayUniforms.upload(curvatureFrame);
            }
            distantDepthUniforms.upload();
            ownMediaDepthUniforms.upload();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneCopy);
            uniform1i(sceneSampler, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthCopy);
            uniform1i(depthSampler, 1);
            if (!curvedOnly) {
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                if (cloudFrame != null && !cloudFrame.closeWindow()) SSCloudRenderer.bindAtlasFor(cloudFrame);
                uniform1i(cloudAtlasUniform, 2);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            uniformMatrix(inverseProjectionUniform, inverseProjection);
            uniformMatrix(inverseModelViewUniform, inverseModelView);
            // X never enters a world-absolute float uniform. Moving panel X
            // phase is carried by camera-relative bands built in CPU double.
            uniform2f(snapshotFootYZUniform, snapshot.observer().y(), snapshot.observer().z());
            uniform3f(cameraRelativeUniform, cameraRelative[0], cameraRelative[1], cameraRelative[2]);
            uniform3f(airProfileUniform, snapshot.airProfile().lowerY(), snapshot.airProfile().fullDensityTopY(),
                    snapshot.airProfile().upperY());
            uniform2f(stripUniform, -8192.0 - snapshot.observer().z(), 8192.0 - snapshot.observer().z());
            uniform2f(boardUniform, snapshot.sunshadeHeightBlocks() - snapshot.observer().y(),
                    snapshot.sunshadeThicknessBlocks());
            uniform4f(bandsUniform, bands.directionX(), bands.directionZ(), bands.spacingBlocks(),
                    bands.panelWidthBlocks());
            uniform2f(bandEdgeUniform, bands.edgeRelativeToRenderOriginBlocks(), bands.edgeOrientation());
            uniform1i(coverageUniform, coverage(bands.coverage()));
            uniform1f(sideFeatherUniform, snapshot.sunshade().sideFeatherBlocks());
            uniform1f(motionFeatherUniform, snapshot.sunshade().featherBlocks());
            if (localOnly) {
                uniform1f(localAirDistanceUniform, LOCAL_AIR_DISTANCE_BLOCKS);
            } else {
                uniform1f(maxDistanceUniform, limitedDistance);
            }
            double bandMean = switch (bands.coverage()) {
                case EMPTY -> 1.0;
                case FULL -> 0.0;
                case PARTIAL -> RingworldSpatialAirMath.periodMeanTransmission(bands.spacingBlocks(),
                        bands.gapWidthBlocks(), snapshot.sunshade().featherBlocks());
            };
            uniform1f(bandMeanTransmissionUniform, bandMean);
			RingworldSpatialAirMath.OpticalCoefficients opticsCoefficients = RingworldSpatialAirMath.displayLinearOptics();
			uniform3f(sigmaTUniform, opticsCoefficients.sigmaTRed(), opticsCoefficients.sigmaTGreen(),
					opticsCoefficients.sigmaTBlue());
			uniform3f(sigmaSUniform, opticsCoefficients.sigmaSRed(), opticsCoefficients.sigmaSGreen(),
					opticsCoefficients.sigmaSBlue());
			uniform3f(sunRadianceUniform, opticsCoefficients.sunRed(), opticsCoefficients.sunGreen(),
					opticsCoefficients.sunBlue());
            if (!curvedOnly) setCloudUniforms(snapshot, cloudFrame);
            try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.AIR_DRAW)) {
                drawFullscreen();
            }
        } finally {
            OpenGlHelper.glUseProgram(0);
        }
    }

    void dispose() {
        if (program != 0) OpenGlHelper.glDeleteProgram(program);
        if (sceneCopy != 0) GL11.glDeleteTextures(sceneCopy);
        if (depthCopy != 0) GL11.glDeleteTextures(depthCopy);
        program = sceneCopy = depthCopy = width = height = 0;
        curvedRayUniforms = null;
        distantDepthUniforms = null;
        ownMediaDepthUniforms = null;
        reducedQualityReported = false;
    }

    private void ensureTextures(int width, int height) {
        if (sceneCopy != 0 && this.width == width && this.height == height) return;
        if (sceneCopy != 0) GL11.glDeleteTextures(sceneCopy);
        if (depthCopy != 0) GL11.glDeleteTextures(depthCopy);
        sceneCopy = GL11.glGenTextures();
        depthCopy = GL11.glGenTextures();
        if (sceneCopy == 0 || depthCopy == 0) throw new IllegalStateException("unable to allocate spatial-air copies");
        this.width = width;
        this.height = height;
        configureCopyTexture(sceneCopy, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, width, height);
        configureCopyTexture(depthCopy, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, width, height);
    }

    private void copyFramebuffer(int x, int y, int width, int height) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneCopy);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthCopy);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height);
    }

    private static void configureCopyTexture(int texture, int internalFormat, int format, int type, int width, int height) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type,
                (java.nio.ByteBuffer) null);
    }

    private void ensureProgram() {
        if (program != 0) return;
        if (!localOnly && (RingworldSpatialAirMath.MAX_PARTITIONS != 8
                || RingworldSpatialAirMath.SOURCE_EVALUATIONS_PER_PARTITION != 2
                || RingworldSpatialAirMath.MAX_SOURCE_EVALUATIONS != 16)) {
			throw new IllegalStateException("spatial-air shader/source evaluation contract changed without shader review");
		}
        if (!OpenGlHelper.shadersSupported || !OpenGlHelper.openGL21) {
            throw new IllegalStateException("spatial-air compositor requires OpenGL 2.1 shaders");
        }
        int vertex = 0;
        int fragment = 0;
        int candidate = 0;
        try {
            long vertexStart = System.nanoTime();
            CompiledShader compiledVertex = compile(VERTEX, OpenGlHelper.GL_VERTEX_SHADER, false);
			vertex = compiledVertex.shader;
            long vertexCompileNanos = System.nanoTime() - vertexStart;
            long fragmentStart = System.nanoTime();
            CompiledShader compiledFragment = compile(localOnly ? LOCAL_AIR_FRAGMENT : FRAGMENT,
                    OpenGlHelper.GL_FRAGMENT_SHADER, !localOnly);
			fragment = compiledFragment.shader;
            long fragmentCompileNanos = System.nanoTime() - fragmentStart;
            long linkStart = System.nanoTime();
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) throw new IllegalStateException("unable to allocate spatial-air program");
            OpenGlHelper.glAttachShader(candidate, vertex);
            OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("unable to link spatial-air shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            long linkNanos = System.nanoTime() - linkStart;
            detach(candidate, vertex);
            detach(candidate, fragment);
            long bindingStart = System.nanoTime();
            final int linkedProgram = candidate;
            bindUniforms(name -> OpenGlHelper.glGetUniformLocation(linkedProgram, name));
            long bindingNanos = System.nanoTime() - bindingStart;
            program = candidate;
            candidate = 0;
            StellarSky.INSTANCE.getLogger().info("SS spatial-air program linked variant={} vertexStageMs={} "
                            + "fragmentStageMs={} sourceAssembleMs={} sourceSubmitMs={} driverCompileMs={} "
                            + "linkMs={} bindingMs={} shaderBytes={}",
                    localOnly ? "curved-local-air" : curvedOnly ? "curved-only" : "legacy", nanosToMillis(vertexCompileNanos),
                    nanosToMillis(fragmentCompileNanos),
                    nanosToMillis(compiledVertex.sourceNanos + compiledFragment.sourceNanos),
                    nanosToMillis(compiledVertex.submitNanos + compiledFragment.submitNanos),
                    nanosToMillis(compiledVertex.compileNanos + compiledFragment.compileNanos),
                    nanosToMillis(linkNanos), nanosToMillis(bindingNanos),
                    compiledVertex.sourceBytes + compiledFragment.sourceBytes);
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        } finally {
            if (candidate != 0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex != 0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    private CompiledShader compile(ResourceLocation location, int type, boolean spatialAirFragment) {
        int shader = OpenGlHelper.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("unable to allocate spatial-air shader " + location);
        try {
            long sourceStart = System.nanoTime();
            byte[] source = spatialAirFragment ? RingworldShaderSource.readSpatialAir(location, curvedOnly)
                    : RingworldShaderSource.readCurved(location);
            java.nio.ByteBuffer bytes = BufferUtils.createByteBuffer(source.length).put(source);
            bytes.flip();
            long sourceNanos = System.nanoTime() - sourceStart;
            long submitStart = System.nanoTime();
            OpenGlHelper.glShaderSource(shader, bytes);
            long submitNanos = System.nanoTime() - submitStart;
            long compileStart = System.nanoTime();
            OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("unable to compile spatial-air shader " + location + ": "
                        + OpenGlHelper.glGetShaderInfoLog(shader, 32768));
            }
            return new CompiledShader(shader, source.length, sourceNanos, submitNanos,
                    System.nanoTime() - compileStart);
        } catch (RuntimeException exception) {
            OpenGlHelper.glDeleteShader(shader);
            throw exception;
        }
    }

    private void readInverseMatrices() {
        projection.clear(); modelView.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        invert(projection, inverseProjection);
        invert(modelView, inverseModelView);
        double inverseW = inverseModelView.get(15);
        if (!Double.isFinite(inverseW) || Math.abs(inverseW) < 1.0e-12) {
            throw new IllegalStateException("invalid spatial-air camera origin");
        }
        cameraRelative[0] = inverseModelView.get(12) / inverseW;
        cameraRelative[1] = inverseModelView.get(13) / inverseW;
        cameraRelative[2] = inverseModelView.get(14) / inverseW;
        if (!Double.isFinite(cameraRelative[0]) || !Double.isFinite(cameraRelative[1]) || !Double.isFinite(cameraRelative[2])) {
            throw new IllegalStateException("non-finite spatial-air camera origin");
        }
    }

    /** Uses the exact cloud-frame query camera when compositor and cloud viewport agree. */
    private void useCamera(CloudCameraFrame camera) {
        camera.copyInverseProjection(inverseProjection);
        camera.copyInverseModelView(inverseModelView);
        cameraRelative[0] = camera.cameraX();
        cameraRelative[1] = camera.cameraY();
        cameraRelative[2] = camera.cameraZ();
    }

    private void invert(FloatBuffer source, FloatBuffer target) {
        float[] m = inversionSource;
        float[] inverse = inversionResult;
        for (int index = 0; index < 16; index++) m[index] = source.get(index);
        inverse[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        inverse[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        inverse[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        inverse[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        inverse[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        inverse[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        inverse[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        inverse[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        inverse[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
        inverse[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
        inverse[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
        inverse[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];
        inverse[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6];
        inverse[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6];
        inverse[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5];
        inverse[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5];
        float determinant = m[0]*inverse[0] + m[1]*inverse[4] + m[2]*inverse[8] + m[3]*inverse[12];
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-8f) throw new IllegalStateException("non-invertible spatial-air camera matrix");
        target.clear();
        for (float value : inverse) target.put(value / determinant);
        target.flip();
    }

    void bindUniforms(ToIntFunction<String> lookup) {
        if (localOnly) {
            // The compact tangent integrator has no use for radius/origin.
            // Its exact DH endpoint still needs the frozen display eye.
            localEyeRelativeUniform = uniform(lookup, "uSSEyeRelative");
        } else {
            curvedRayUniforms = new RingworldCurvedRayUniforms(lookup);
        }
        distantDepthUniforms = new RingworldDistantDepthUniforms(lookup);
        ownMediaDepthUniforms = new RingworldOwnMediaDepthUniforms(lookup);
        sceneSampler = uniform(lookup, "uSceneColor"); depthSampler = uniform(lookup, "uSceneDepth");
        inverseProjectionUniform = uniform(lookup, "uInverseProjection"); inverseModelViewUniform = uniform(lookup, "uInverseModelView");
        snapshotFootYZUniform = uniform(lookup, "uSnapshotFootYZ"); cameraRelativeUniform = uniform(lookup, "uCameraRelative");
        airProfileUniform = uniform(lookup, "uAirProfile"); stripUniform = uniform(lookup, "uStripZ");
        boardUniform = uniform(lookup, "uBoard"); bandsUniform = uniform(lookup, "uBands"); bandEdgeUniform = uniform(lookup, "uBandEdge");
        coverageUniform = uniform(lookup, "uCoverage");
        sideFeatherUniform = uniform(lookup, "uSideFeather");
        if (localOnly) {
            motionFeatherUniform = uniform(lookup, "uMotionFeather");
            localAirDistanceUniform = uniform(lookup, "uLocalAirDistance");
            bandMeanTransmissionUniform = uniform(lookup, "uBandMeanTransmission");
            sigmaTUniform = uniform(lookup, "uSigmaT"); sigmaSUniform = uniform(lookup, "uSigmaS");
            sunRadianceUniform = uniform(lookup, "uSunRadiance");
            return;
        }
        maxDistanceUniform = uniform(lookup, "uMaxDistance");
        bandMeanTransmissionUniform = uniform(lookup, "uBandMeanTransmission");
        motionFeatherUniform = uniform(lookup, "uMotionFeather");
        sigmaTUniform = uniform(lookup, "uSigmaT"); sigmaSUniform = uniform(lookup, "uSigmaS");
        sunRadianceUniform = uniform(lookup, "uSunRadiance");
        if (curvedOnly) return;
        cloudOrigin0Uniform = uniform(lookup, "uCloudOrigin0"); cloudOrigin1Uniform = uniform(lookup, "uCloudOrigin1"); cloudOrigin2Uniform = uniform(lookup, "uCloudOrigin2"); cloudOrigin3Uniform = uniform(lookup, "uCloudOrigin3"); cloudOrigin4Uniform = uniform(lookup, "uCloudOrigin4");
        cloudOrigin5Uniform = uniform(lookup, "uCloudOrigin5"); cloudOrigin6Uniform = uniform(lookup, "uCloudOrigin6"); cloudOrigin7Uniform = uniform(lookup, "uCloudOrigin7"); cloudOrigin8Uniform = uniform(lookup, "uCloudOrigin8"); cloudOrigin9Uniform = uniform(lookup, "uCloudOrigin9"); cloudOrigin10Uniform = uniform(lookup, "uCloudOrigin10"); cloudOrigin11Uniform = uniform(lookup, "uCloudOrigin11"); cloudOrigin12Uniform = uniform(lookup, "uCloudOrigin12"); cloudGeometryUniform = uniform(lookup, "uCloudGeometry");
        cloudClipUniform = uniform(lookup, "uCloudClip");
        cloudHorizonUniform = uniform(lookup, "uCloudHorizon"); cloudAtlasUniform = uniform(lookup, "uCloudAtlas");
        cloudActiveUniform = uniform(lookup, "uCloudActive");
        cloudCullFlagsUniform = uniform(lookup, "uCloudCullFlags");
        cloudPixelAngularSizeUniform = uniform(lookup, "uCloudPixelAngularSize");
        cloudTailPolicyUniform = uniform(lookup, "uCloudTailPolicy");
        cloudTransitionWidthsUniform = uniform(lookup, "uCloudTransitionWidths");
    }

    private static int uniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("spatial-air shader lacks uniform " + name);
        return location;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static final class CompiledShader {
        private final int shader;
        private final int sourceBytes;
        private final long sourceNanos;
        private final long submitNanos;
        private final long compileNanos;

        private CompiledShader(int shader, int sourceBytes, long sourceNanos, long submitNanos, long compileNanos) {
            this.shader = shader;
            this.sourceBytes = sourceBytes;
            this.sourceNanos = sourceNanos;
            this.submitNanos = submitNanos;
            this.compileNanos = compileNanos;
        }
    }

    private void setCloudUniforms(RingworldDisplaySnapshot snapshot, SSCloudFrame frame) {
        if (frame == null || frame.closeWindow()) {
            // Near-only clouds are already represented by scene depth. Do not
            // invent invisible horizon clouds in the analytic air query.
            uniform1i(cloudActiveUniform, 0);
            uniform1f(cloudHorizonUniform, 0.0D);
            return;
        }
        double observerY = snapshot.observer().y();
        double observerZ = snapshot.observer().z();
        setCloudOrigins(snapshot, frame);
        uniform4f(cloudCullFlagsUniform, frame.cullFine() ? 1.0D : 0.0D, frame.cullMid() ? 1.0D : 0.0D,
                frame.cullLow() ? 1.0D : 0.0D, frame.cullVeryLow() ? 1.0D : 0.0D);
        uniform1f(cloudPixelAngularSizeUniform, frame.pixelAngularSize());
        frame.tailCoverage().copyUniforms(tailPolicyValues);
        uniform3f(cloudTailPolicyUniform, tailPolicyValues.get(0), tailPolicyValues.get(1), tailPolicyValues.get(2));
        uniform3f(cloudTransitionWidthsUniform, frame.transition().fineWidth(), frame.transition().midWidth(), frame.transition().lowWidth());
        uniform3f(cloudGeometryUniform, frame.geometry().cellSizeBlocks(), frame.geometry().voxelHeightBlocks(),
                frame.baseY() - observerY);
        uniform4f(cloudClipUniform, frame.clipBounds().lowerY() - observerY, frame.clipBounds().upperY() - observerY,
                frame.clipBounds().minWorldZ() - observerZ, frame.clipBounds().maxWorldZ() - observerZ);
        uniform1f(cloudHorizonUniform, frame.horizon().effectiveHorizon());
        uniform1i(cloudActiveUniform, 1);
    }
    private void setCloudOrigins(RingworldDisplaySnapshot snapshot, SSCloudFrame frame) {
        int[] uniforms = {cloudOrigin0Uniform, cloudOrigin1Uniform, cloudOrigin2Uniform, cloudOrigin3Uniform,
                cloudOrigin4Uniform, cloudOrigin5Uniform, cloudOrigin6Uniform, cloudOrigin7Uniform,
                cloudOrigin8Uniform, cloudOrigin9Uniform, cloudOrigin10Uniform, cloudOrigin11Uniform, cloudOrigin12Uniform};
        double wind = frame.meshMotion().windOffsetBlocks();
        for (int i = 0; i < uniforms.length; i++) {
            CloudLodLayout.AtlasLevel level = CloudLodLayout.ATLAS_LEVELS.get(i);
            uniform2f(uniforms[i], frame.pageOriginX(level), frame.pageOriginZ(level));
        }
    }
    private static int coverage(RingworldSunshade.BandCoverage coverage) {
        return switch (coverage) {
            case EMPTY -> 0;
            case PARTIAL -> 1;
            case FULL -> 2;
        };
    }
    private static void detach(int program, int shader) { GL20.glDetachShader(program, shader); }
    private static void uniform1i(int location, int value) { GL20.glUniform1i(location, value); }
    private static void uniform1f(int location, double value) { GL20.glUniform1f(location, (float) value); }
    private static void uniform2f(int location, double x, double y) { GL20.glUniform2f(location, (float) x, (float) y); }
    private static void uniform3f(int location, double x, double y, double z) { GL20.glUniform3f(location, (float) x, (float) y, (float) z); }
    private static void uniform4f(int location, double x, double y, double z, double w) { GL20.glUniform4f(location, (float) x, (float) y, (float) z, (float) w); }
    private static void uniformMatrix(int location, FloatBuffer matrix) { GL20.glUniformMatrix4(location, false, matrix); }
    private static void drawFullscreen() { GL11.glBegin(GL11.GL_QUADS); GL11.glVertex2f(-1, -1); GL11.glVertex2f(1, -1); GL11.glVertex2f(1, 1); GL11.glVertex2f(-1, 1); GL11.glEnd(); }
}
