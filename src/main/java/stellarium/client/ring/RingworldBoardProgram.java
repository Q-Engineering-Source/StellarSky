package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import stellarium.world.ring.RingworldDisplaySnapshot;

/** Owns only the board program and its uniforms; no GL work occurs before first render. */
final class RingworldBoardProgram {
    private static final ResourceLocation VERTEX_SHADER =
            new ResourceLocation("stellarium", "shaders/ringworld/board.vert");
    private static final ResourceLocation FRAGMENT_SHADER =
            new ResourceLocation("stellarium", "shaders/ringworld/board.frag");
    private static final ResourceLocation MESH_VERTEX_SHADER =
            new ResourceLocation("stellarium", "shaders/ringworld/board_mesh.vert");

    private final boolean mesh;
    private int meshEye, meshOriginYZ, meshDistancePass, meshNearestHigh, meshNearestLow, meshPixelOffset;
    private int meshHeading, meshEdge;
    private int meshClipProjection;
    private final FloatBuffer meshProjection = BufferUtils.createFloatBuffer(16);

    private int program;
    private int viewport;
    private int heading;
    private int spacing;
    private int panelWidth;
    private int edgeRelative;
    private int edgeOrientation;
    private int coverage;
    private int baseYRelative;
    private int thickness;
    private int stripZBoundsRelative;
    private int dotsEnabled;
    private int dotPitch;
    private int dotRadius;
    private int dotBrightness;
    private int dotPerimeterInset;
    private int dotObserverPhase;
    private int dotNearbyPanelAnchorPhase;
    private int dotPanelStepPhase;
    private int dotNearbyLeadingRelative;
    private int dotOffProbability;
    private int dotSeedResidue;
    private int dotMaterialCyclePhase;
    private int dotPanelStepCyclePhase;
    private int dotNearbyPanelResidue;
    private int dotTangentPhase;
    private int dotTangentCyclePhase;
    private int dotPulseBrightness;
    private int dotEdgeProfileEnabled;
    private int dotEdgeBandFraction;
    private int dotEdgeBrightness;
    private int dotCenterBrightness;
    private int dotEdgeTransitionFraction;
    private RingworldCurvedRayUniforms curvedRayUniforms;
    private RingworldDistantDepthUniforms distantDepthUniforms;

    RingworldBoardProgram() { this(false); }

    RingworldBoardProgram(boolean mesh) { this.mesh = mesh; }

    void setMeshPass(int stage, int x, int y, int width, int height, int pixelOffsetX, int pixelOffsetY) {
        if (!mesh || stage < 0 || stage > 2) throw new IllegalArgumentException("Invalid board mesh pass");
        uniform4f(viewport, x, y, width, height);
        OpenGlHelper.glUniform1i(meshDistancePass, stage);
        OpenGlHelper.glUniform1i(meshNearestHigh, 5);
        OpenGlHelper.glUniform1i(meshNearestLow, 6);
        GL20.glUniform2i(meshPixelOffset, pixelOffsetX, pixelOffsetY);
    }

    void use() {
        if (program == 0) {
            create();
        }
        OpenGlHelper.glUseProgram(program);
    }

    void setUniforms(int viewportX, int viewportY, int viewportWidth, int viewportHeight,
                     double headingX, double headingZ, double spacingBlocks, double panelWidthBlocks,
                     double gapWidthBlocks, double edgeRelativeBlocks, int edgeOrientationValue, int coverageValue,
                     double baseYRelativeBlocks, int thicknessBlocks,
                     double minZRelativeBlocks, double maxZRelativeBlocks,
                     boolean dotsEnabledValue, double dotPitchBlocks, double dotRadiusBlocks,
                     double dotBrightnessValue, double dotPerimeterInsetBlocks,
                     double dotObserverXPhaseBlocks, double dotObserverZPhaseBlocks,
                     double dotNearbyPanelAnchorXPhaseBlocks, double dotNearbyPanelAnchorZPhaseBlocks,
                     double dotPanelStepXPhaseBlocks, double dotPanelStepZPhaseBlocks,
                     double dotNearbyLeadingRelativeBlocks,
                     float dotOffProbabilityValue, int dotSeedValue,
                     double dotNearbyMaterialXCyclePhaseBlocks, double dotNearbyMaterialZCyclePhaseBlocks,
                     double dotPanelStepXCyclePhaseBlocks, double dotPanelStepZCyclePhaseBlocks,
                     int dotNearbyPanelResidueValue,
                     double dotTangentPhaseBlocks, double dotTangentCyclePhaseBlocks,
                     float dotPulseBrightnessValue,
                     RingworldBoardDotWidthProfile.Profile dotWidthProfile) {
        RingworldDisplaySnapshot snapshot = RingworldRenderSnapshots.current();
        var frame = snapshot == null ? null
                : RingworldRenderSnapshots.currentDistantCurvatureFrameFor(snapshot.world(), snapshot.scene());
        if (mesh) {
            if (frame == null) throw new IllegalStateException("Board mesh requires the frozen curvature frame");
            frame.copyProjection(meshProjection);
            RingworldBoardClipProjection.removeFarPlane(meshProjection);
            GL20.glUniformMatrix4(meshClipProjection, false, meshProjection);
            GL20.glUniform3f(meshEye, frame.cameraX(), frame.cameraY(), frame.cameraZ());
            double originY = frame.renderOrigin().y(), originZ = frame.renderOrigin().z();
            uniform4f(meshOriginYZ, (float) originY, originY - (double) (float) originY,
                    (float) originZ, originZ - (double) (float) originZ);
            uniform4f(meshHeading, (float) headingX, headingX - (double) (float) headingX,
                    (float) headingZ, headingZ - (double) (float) headingZ);
            uniform2f(meshEdge, (float) edgeRelativeBlocks, edgeRelativeBlocks - (double) (float) edgeRelativeBlocks);
        } else {
            curvedRayUniforms.upload(frame);
        }
        distantDepthUniforms.upload();
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalStateException("Ringworld board renderer received an empty viewport");
        }
        if (thicknessBlocks <= 0 || coverageValue < 0 || coverageValue > 2) {
            throw new IllegalStateException("Ringworld board renderer received invalid immutable board input");
        }
        float headingXFloat = requireFiniteFloat("band heading X", headingX);
        float headingZFloat = requireFiniteFloat("band heading Z", headingZ);
        float spacingFloat = requirePositiveFloat("band spacing", spacingBlocks);
        float panelWidthFloat = requireFiniteFloat("panel width", panelWidthBlocks);
        float gapWidthFloat = requireFiniteFloat("gap width", gapWidthBlocks);
        float edgeRelativeFloat = requireFiniteFloat("edge relative position", edgeRelativeBlocks);
        float baseYRelativeFloat = requireFiniteFloat("board base height", baseYRelativeBlocks);
        float minZRelativeFloat = requireFiniteFloat("strip lower Z", minZRelativeBlocks);
        float maxZRelativeFloat = requireFiniteFloat("strip upper Z", maxZRelativeBlocks);
        float dotPitchFloat = requirePositiveFloat("dot pitch", dotPitchBlocks);
        float dotRadiusFloat = requirePositiveFloat("dot radius", dotRadiusBlocks);
        float dotBrightnessFloat = requireNonNegativeFloat("dot brightness", dotBrightnessValue);
        float dotPerimeterInsetFloat = requireNonNegativeFloat("dot perimeter inset", dotPerimeterInsetBlocks);
        float dotObserverXPhaseFloat = requireFiniteFloat("dot observer X phase", dotObserverXPhaseBlocks);
        float dotObserverZPhaseFloat = requireFiniteFloat("dot observer Z phase", dotObserverZPhaseBlocks);
        float dotNearbyPanelAnchorXPhaseFloat = requireFiniteFloat("dot panel anchor X phase",
                dotNearbyPanelAnchorXPhaseBlocks);
        float dotNearbyPanelAnchorZPhaseFloat = requireFiniteFloat("dot panel anchor Z phase",
                dotNearbyPanelAnchorZPhaseBlocks);
        float dotPanelStepXPhaseFloat = requireFiniteFloat("dot panel X phase step", dotPanelStepXPhaseBlocks);
        float dotPanelStepZPhaseFloat = requireFiniteFloat("dot panel Z phase step", dotPanelStepZPhaseBlocks);
        float dotNearbyLeadingRelativeFloat = requireFiniteFloat("nearby dot panel leading edge",
                dotNearbyLeadingRelativeBlocks);
        float dotOffProbabilityFloat = requireProbability("dot outage probability", dotOffProbabilityValue);
        float dotNearbyMaterialXCyclePhaseFloat = requireFiniteFloat("dot material X cycle phase",
                dotNearbyMaterialXCyclePhaseBlocks);
        float dotNearbyMaterialZCyclePhaseFloat = requireFiniteFloat("dot material Z cycle phase",
                dotNearbyMaterialZCyclePhaseBlocks);
        float dotPanelStepXCyclePhaseFloat = requireFiniteFloat("dot panel X cycle step",
                dotPanelStepXCyclePhaseBlocks);
        float dotPanelStepZCyclePhaseFloat = requireFiniteFloat("dot panel Z cycle step",
                dotPanelStepZCyclePhaseBlocks);
        float dotTangentPhaseFloat = requireFiniteFloat("dot tangent phase", dotTangentPhaseBlocks);
        float dotTangentCyclePhaseFloat = requireFiniteFloat("dot tangent cycle phase", dotTangentCyclePhaseBlocks);
        float dotPulseBrightnessFloat = requireNonNegativeFloat("dot pulse brightness", dotPulseBrightnessValue);
        if (dotWidthProfile == null) {
            throw new NullPointerException("dotWidthProfile");
        }
        float dotEdgeBandFractionFloat = requireNonNegativeFloat("dot edge band fraction",
                dotWidthProfile.edgeBandFraction());
        float dotEdgeBrightnessFloat = requireNonNegativeFloat("dot edge brightness",
                dotWidthProfile.edgeBrightnessMultiplier());
        float dotCenterBrightnessFloat = requireNonNegativeFloat("dot center brightness",
                dotWidthProfile.centerBrightnessMultiplier());
        float dotEdgeTransitionFractionFloat = requireNonNegativeFloat("dot edge transition fraction",
                dotWidthProfile.edgeTransitionFraction());
        if (dotNearbyPanelResidueValue < 0 || dotNearbyPanelResidueValue >= RingworldBoardDotOutage.CELL_PERIOD) {
            throw new IllegalStateException("Ringworld board dot panel residue is out of range");
        }
        if (!(maxZRelativeFloat > minZRelativeFloat)) {
            throw new IllegalStateException("Ringworld strip width is not representable by this GPU path");
        }
        if (panelWidthFloat < 0.0f || panelWidthFloat > spacingFloat || gapWidthFloat < 0.0f
                || gapWidthFloat > spacingFloat || (coverageValue == 1
                && (panelWidthFloat == 0.0f || gapWidthFloat == 0.0f))) {
            throw new IllegalStateException("Ringworld board panel width is not representable by this GPU path");
        }
        if (dotRadiusFloat > dotPitchFloat / 2.0f) {
            throw new IllegalStateException("Ringworld board dot radius must not exceed half its pitch");
        }
        uniform4f(viewport, viewportX, viewportY, viewportWidth, viewportHeight);
        uniform2f(heading, headingXFloat, headingZFloat);
        uniform1f(spacing, spacingFloat);
        uniform1f(panelWidth, panelWidthFloat);
        if (!mesh) uniform1f(edgeRelative, edgeRelativeFloat);
        OpenGlHelper.glUniform1i(edgeOrientation, edgeOrientationValue);
        OpenGlHelper.glUniform1i(coverage, coverageValue);
        uniform1f(baseYRelative, baseYRelativeFloat);
        OpenGlHelper.glUniform1i(thickness, thicknessBlocks);
        uniform2f(stripZBoundsRelative, minZRelativeFloat, maxZRelativeFloat);
        OpenGlHelper.glUniform1i(dotsEnabled, dotsEnabledValue ? 1 : 0);
        uniform1f(dotPitch, dotPitchFloat);
        uniform1f(dotRadius, dotRadiusFloat);
        uniform1f(dotBrightness, dotBrightnessFloat);
        uniform1f(dotPerimeterInset, dotPerimeterInsetFloat);
        uniform2f(dotObserverPhase, dotObserverXPhaseFloat, dotObserverZPhaseFloat);
        uniform2f(dotNearbyPanelAnchorPhase, dotNearbyPanelAnchorXPhaseFloat,
                dotNearbyPanelAnchorZPhaseFloat);
        uniform2f(dotPanelStepPhase, dotPanelStepXPhaseFloat, dotPanelStepZPhaseFloat);
        uniform1f(dotNearbyLeadingRelative, dotNearbyLeadingRelativeFloat);
        uniform1f(dotOffProbability, dotOffProbabilityFloat);
        OpenGlHelper.glUniform1i(dotSeedResidue, RingworldBoardDotOutage.seedResidue(dotSeedValue));
        uniform2f(dotMaterialCyclePhase, dotNearbyMaterialXCyclePhaseFloat, dotNearbyMaterialZCyclePhaseFloat);
        uniform2f(dotPanelStepCyclePhase, dotPanelStepXCyclePhaseFloat, dotPanelStepZCyclePhaseFloat);
        OpenGlHelper.glUniform1i(dotNearbyPanelResidue, dotNearbyPanelResidueValue);
        uniform1f(dotTangentPhase, dotTangentPhaseFloat);
        uniform1f(dotTangentCyclePhase, dotTangentCyclePhaseFloat);
        uniform1f(dotPulseBrightness, dotPulseBrightnessFloat);
        OpenGlHelper.glUniform1i(dotEdgeProfileEnabled, dotWidthProfile.enabled() ? 1 : 0);
        uniform1f(dotEdgeBandFraction, dotEdgeBandFractionFloat);
        uniform1f(dotEdgeBrightness, dotEdgeBrightnessFloat);
        uniform1f(dotCenterBrightness, dotCenterBrightnessFloat);
        uniform1f(dotEdgeTransitionFraction, dotEdgeTransitionFractionFloat);
    }

    void dispose() {
        if (program != 0) {
            OpenGlHelper.glDeleteProgram(program);
            program = 0;
        }
        curvedRayUniforms = null;
        distantDepthUniforms = null;
    }

    private void create() {
        if (!OpenGlHelper.shadersSupported) {
            throw new IllegalStateException("Ringworld board rendering requires OpenGL shader support");
        }
        int vertex = 0;
        int fragment = 0;
        int candidate = 0;
        try {
            vertex = compile(mesh ? MESH_VERTEX_SHADER : VERTEX_SHADER, OpenGlHelper.GL_VERTEX_SHADER);
            fragment = compile(FRAGMENT_SHADER, OpenGlHelper.GL_FRAGMENT_SHADER);
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) {
                throw new IllegalStateException("Unable to allocate ringworld board shader program");
            }
            OpenGlHelper.glAttachShader(candidate, vertex);
            OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to link ringworld board shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            detach(candidate, vertex);
            detach(candidate, fragment);
            int linkedProgram = candidate;
            bindUniforms(name -> OpenGlHelper.glGetUniformLocation(linkedProgram, name));
            program = candidate;
            candidate = 0;
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        } finally {
            if (candidate != 0) {
                OpenGlHelper.glDeleteProgram(candidate);
            }
            if (vertex != 0) {
                OpenGlHelper.glDeleteShader(vertex);
            }
            if (fragment != 0) {
                OpenGlHelper.glDeleteShader(fragment);
            }
        }
    }

    private int compile(ResourceLocation location, int type) {
        int shader = OpenGlHelper.glCreateShader(type);
        if (shader == 0) {
            throw new IllegalStateException("Unable to allocate ringworld board shader for " + location);
        }
        try {
            byte[] source = mesh ? RingworldShaderSource.readBoardMesh(location) : RingworldShaderSource.readCurved(location);
            ByteBuffer bytes = BufferUtils.createByteBuffer(source.length);
            bytes.put(source).flip();
            OpenGlHelper.glShaderSource(shader, bytes);
            OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to compile ringworld board shader " + location + ": "
                        + OpenGlHelper.glGetShaderInfoLog(shader, 32768));
            }
            return shader;
        } catch (RuntimeException exception) {
            OpenGlHelper.glDeleteShader(shader);
            throw exception;
        }
    }

    /** The same binding contract is executed against GL and offline linked-uniform metadata. */
    void bindUniforms(ToIntFunction<String> lookup) {
        if (mesh) {
            meshEye = requireUniform(lookup, "uSSEyeRelative");
            meshOriginYZ = requireUniform(lookup, "uMeshOriginYZ");
            meshDistancePass = requireUniform(lookup, "uMeshDistancePass");
            meshNearestHigh = requireUniform(lookup, "uMeshNearestHigh");
            meshNearestLow = requireUniform(lookup, "uMeshNearestLow");
            meshPixelOffset = requireUniform(lookup, "uMeshPixelOffset");
            meshHeading = requireUniform(lookup, "uMeshHeadingHiLo");
            meshEdge = requireUniform(lookup, "uMeshEdgeHiLo");
            meshClipProjection = requireUniform(lookup, "uMeshClipProjection");
        } else {
            curvedRayUniforms = new RingworldCurvedRayUniforms(lookup);
        }
        distantDepthUniforms = new RingworldDistantDepthUniforms(lookup);
        viewport = requireUniform(lookup, "uViewport");
        heading = requireUniform(lookup, "uBandHeading");
        spacing = requireUniform(lookup, "uBandSpacing");
        panelWidth = requireUniform(lookup, "uPanelWidth");
        if (!mesh) edgeRelative = requireUniform(lookup, "uEdgeRelative");
        edgeOrientation = requireUniform(lookup, "uEdgeOrientation");
        coverage = requireUniform(lookup, "uCoverage");
        baseYRelative = requireUniform(lookup, "uBaseYRelative");
        thickness = requireUniform(lookup, "uThickness");
        stripZBoundsRelative = requireUniform(lookup, "uStripZBoundsRelative");
        dotsEnabled = requireUniform(lookup, "uDotsEnabled");
        dotPitch = requireUniform(lookup, "uDotPitch");
        dotRadius = requireUniform(lookup, "uDotRadius");
        dotBrightness = requireUniform(lookup, "uDotBrightness");
        dotPerimeterInset = requireUniform(lookup, "uDotPerimeterInset");
        dotObserverPhase = requireUniform(lookup, "uDotObserverPhase");
        dotNearbyPanelAnchorPhase = requireUniform(lookup, "uDotNearbyPanelAnchorPhase");
        dotPanelStepPhase = requireUniform(lookup, "uDotPanelStepPhase");
        dotNearbyLeadingRelative = requireUniform(lookup, "uDotNearbyLeadingRelative");
        dotOffProbability = requireUniform(lookup, "uDotOffProbability");
        dotSeedResidue = requireUniform(lookup, "uDotSeedResidue");
        dotMaterialCyclePhase = requireUniform(lookup, "uDotMaterialCyclePhase");
        dotPanelStepCyclePhase = requireUniform(lookup, "uDotPanelStepCyclePhase");
        dotNearbyPanelResidue = requireUniform(lookup, "uDotNearbyPanelResidue");
        dotTangentPhase = requireUniform(lookup, "uDotTangentPhase");
        dotTangentCyclePhase = requireUniform(lookup, "uDotTangentCyclePhase");
        dotPulseBrightness = requireUniform(lookup, "uDotPulseBrightness");
        dotEdgeProfileEnabled = requireUniform(lookup, "uDotEdgeProfileEnabled");
        dotEdgeBandFraction = requireUniform(lookup, "uDotEdgeBandFraction");
        dotEdgeBrightness = requireUniform(lookup, "uDotEdgeBrightness");
        dotCenterBrightness = requireUniform(lookup, "uDotCenterBrightness");
        dotEdgeTransitionFraction = requireUniform(lookup, "uDotEdgeTransitionFraction");
    }

    private static int requireUniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) {
            throw new IllegalStateException("Ringworld board shader is missing required uniform " + name);
        }
        return location;
    }

    private static void detach(int program, int shader) {
        if (OpenGlHelper.openGL21) {
            GL20.glDetachShader(program, shader);
        } else {
            ARBShaderObjects.glDetachObjectARB(program, shader);
        }
    }

    private static float requireFiniteFloat(String description, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("Ringworld board " + description + " is not finite");
        }
        float converted = (float) value;
        if (!Float.isFinite(converted)) {
            throw new IllegalStateException("Ringworld board " + description + " cannot be represented as float");
        }
        return converted;
    }

    private static float requirePositiveFloat(String description, double value) {
        float converted = requireFiniteFloat(description, value);
        if (converted <= 0.0f) {
            throw new IllegalStateException("Ringworld board " + description + " underflowed or is not positive");
        }
        return converted;
    }

    private static float requireNonNegativeFloat(String description, double value) {
        float converted = requireFiniteFloat(description, value);
        if (converted < 0.0f) {
            throw new IllegalStateException("Ringworld board " + description + " is negative");
        }
        return converted;
    }

    private static float requireProbability(String description, float value) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalStateException("Ringworld board " + description + " must be finite in [0, 1]");
        }
        return value;
    }

    private static void uniform1f(int location, double value) {
        if (OpenGlHelper.openGL21) {
            GL20.glUniform1f(location, (float) value);
        } else {
            ARBShaderObjects.glUniform1fARB(location, (float) value);
        }
    }

    private static void uniform2f(int location, double x, double y) {
        if (OpenGlHelper.openGL21) {
            GL20.glUniform2f(location, (float) x, (float) y);
        } else {
            ARBShaderObjects.glUniform2fARB(location, (float) x, (float) y);
        }
    }

    private static void uniform4f(int location, double x, double y, double z, double w) {
        if (OpenGlHelper.openGL21) {
            GL20.glUniform4f(location, (float) x, (float) y, (float) z, (float) w);
        } else {
            ARBShaderObjects.glUniform4fARB(location, (float) x, (float) y, (float) z, (float) w);
        }
    }
}
