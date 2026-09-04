package stellarium.client.ring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

/** Owns only the board program and its uniforms; no GL work occurs before first render. */
final class RingworldBoardProgram {
    private static final ResourceLocation VERTEX_SHADER =
            new ResourceLocation("stellarium", "shaders/ringworld/board.vert");
    private static final ResourceLocation FRAGMENT_SHADER =
            new ResourceLocation("stellarium", "shaders/ringworld/board.frag");

    private int program;
    private int viewport;
    private int heading;
    private int spacing;
    private int panelWidth;
    private int gapWidth;
    private int edgeRelative;
    private int edgeOrientation;
    private int coverage;
    private int baseYRelative;
    private int thickness;
    private int stripZBoundsRelative;

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
                     double minZRelativeBlocks, double maxZRelativeBlocks) {
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
        if (!(maxZRelativeFloat > minZRelativeFloat)) {
            throw new IllegalStateException("Ringworld strip width is not representable by this GPU path");
        }
        if (panelWidthFloat < 0.0f || panelWidthFloat > spacingFloat || gapWidthFloat < 0.0f
                || gapWidthFloat > spacingFloat || (coverageValue == 1
                && (panelWidthFloat == 0.0f || gapWidthFloat == 0.0f))) {
            throw new IllegalStateException("Ringworld board panel width is not representable by this GPU path");
        }
        uniform4f(viewport, viewportX, viewportY, viewportWidth, viewportHeight);
        uniform2f(heading, headingXFloat, headingZFloat);
        uniform1f(spacing, spacingFloat);
        uniform1f(panelWidth, panelWidthFloat);
        uniform1f(gapWidth, gapWidthFloat);
        uniform1f(edgeRelative, edgeRelativeFloat);
        OpenGlHelper.glUniform1i(edgeOrientation, edgeOrientationValue);
        OpenGlHelper.glUniform1i(coverage, coverageValue);
        uniform1f(baseYRelative, baseYRelativeFloat);
        OpenGlHelper.glUniform1i(thickness, thicknessBlocks);
        uniform2f(stripZBoundsRelative, minZRelativeFloat, maxZRelativeFloat);
    }

    void dispose() {
        if (program != 0) {
            OpenGlHelper.glDeleteProgram(program);
            program = 0;
        }
    }

    private void create() {
        if (!OpenGlHelper.shadersSupported) {
            throw new IllegalStateException("Ringworld board rendering requires OpenGL shader support");
        }
        int vertex = 0;
        int fragment = 0;
        int candidate = 0;
        try {
            vertex = compile(VERTEX_SHADER, OpenGlHelper.GL_VERTEX_SHADER);
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
            program = candidate;
            candidate = 0;
            viewport = requireUniform("uViewport");
            heading = requireUniform("uBandHeading");
            spacing = requireUniform("uBandSpacing");
            panelWidth = requireUniform("uPanelWidth");
            gapWidth = requireUniform("uGapWidth");
            edgeRelative = requireUniform("uEdgeRelative");
            edgeOrientation = requireUniform("uEdgeOrientation");
            coverage = requireUniform("uCoverage");
            baseYRelative = requireUniform("uBaseYRelative");
            thickness = requireUniform("uThickness");
            stripZBoundsRelative = requireUniform("uStripZBoundsRelative");
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
            byte[] source = readResource(location);
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

    private static byte[] readResource(ResourceLocation location) {
        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(location);
             InputStream stream = resource.getInputStream()) {
            return IOUtils.toByteArray(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read ringworld board shader " + location, exception);
        }
    }

    private int requireUniform(String name) {
        int location = OpenGlHelper.glGetUniformLocation(program, name);
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
