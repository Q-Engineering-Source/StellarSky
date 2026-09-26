package stellarium.client.ring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.ToIntFunction;

import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import stellarium.client.ring.cloud.CloudLodTransition;
import stellarium.world.ring.RingworldSunshade;

/** Owns the small GLSL 1.20 program used by the opaque closed cloud mesh. */
final class SSCloudProgram {
    private static final ResourceLocation VERTEX =
            new ResourceLocation("stellarium", "shaders/ringworld/cloud.vert");
    private static final ResourceLocation FRAGMENT =
            new ResourceLocation("stellarium", "shaders/ringworld/cloud.frag");

    private int program;
    private int meshOffset;
    private int stripZ;
    private int board;
    private int bands;
    private int bandEdge;
    private int coverage;
    private int sideFeather;
    private int motionFeather;
    private int weather;
    private int cloudLodEnabled;
    private int cloudTransitionWidths;
    private int cloudBottomBrightness;
    private RingworldCurvedRayUniforms curvedRayUniforms;
    private RingworldDistantDepthUniforms distantDepthUniforms;
    private RingworldBoardDepthUniforms boardDepthUniforms;

    void use() {
        if (program == 0) create();
        OpenGlHelper.glUseProgram(program);
        distantDepthUniforms.upload();
        boardDepthUniforms.upload();
    }

    void setUniforms(double meshOffsetX, double meshOffsetY, double meshOffsetZ,
                     double stripMinZRelative, double stripMaxZRelative,
                     double boardBaseYRelative, double boardThickness,
                     RingworldSunshade.CameraRelativeBands bandsValue,
                     double sideFeatherValue, double motionFeatherValue, double weatherValue) {
        requireFiniteFloat("mesh X offset", meshOffsetX);
        requireFiniteFloat("mesh Y offset", meshOffsetY);
        requireFiniteFloat("mesh Z offset", meshOffsetZ);
        requireFiniteFloat("strip minimum Z", stripMinZRelative);
        requireFiniteFloat("strip maximum Z", stripMaxZRelative);
        requireFiniteFloat("board base Y", boardBaseYRelative);
        requirePositiveFloat("board thickness", boardThickness);
        requireFiniteFloat("band heading X", bandsValue.directionX());
        requireFiniteFloat("band heading Z", bandsValue.directionZ());
        requirePositiveFloat("band spacing", bandsValue.spacingBlocks());
        requireFiniteFloat("band panel width", bandsValue.panelWidthBlocks());
        requireFiniteFloat("band edge", bandsValue.edgeRelativeToRenderOriginBlocks());
        requireNonNegativeFloat("side feather", sideFeatherValue);
        requireNonNegativeFloat("motion feather", motionFeatherValue);
        requireUnitInterval("weather", weatherValue);
        if (!(stripMaxZRelative > stripMinZRelative)) {
            throw new IllegalStateException("SS cloud strip has no representable Z extent");
        }
        if (bandsValue.panelWidthBlocks() < 0.0D || bandsValue.panelWidthBlocks() > bandsValue.spacingBlocks()) {
            throw new IllegalStateException("SS cloud received invalid sunshade panel dimensions");
        }

        uniform3f(meshOffset, meshOffsetX, meshOffsetY, meshOffsetZ);
        uniform2f(stripZ, stripMinZRelative, stripMaxZRelative);
        uniform2f(board, boardBaseYRelative, boardThickness);
        uniform4f(bands, bandsValue.directionX(), bandsValue.directionZ(), bandsValue.spacingBlocks(),
                bandsValue.panelWidthBlocks());
        uniform2f(bandEdge, bandsValue.edgeRelativeToRenderOriginBlocks(), bandsValue.edgeOrientation());
        uniform1i(coverage, coverage(bandsValue.coverage()));
        uniform1f(sideFeather, sideFeatherValue);
        uniform1f(motionFeather, motionFeatherValue);
        uniform1f(weather, weatherValue);
    }

    void dispose() {
        if (program != 0) OpenGlHelper.glDeleteProgram(program);
        program = 0;
    }

    void setLodUniforms(boolean enabled, CloudLodTransition transition, double bottomBrightness) {
        requireUnitInterval("cloud bottom brightness", bottomBrightness);
        uniform1i(cloudLodEnabled, enabled ? 1 : 0);
        uniform3f(cloudTransitionWidths, transition.fineWidth(), transition.midWidth(), transition.lowWidth());
        uniform1f(cloudBottomBrightness, bottomBrightness);
    }

    /** Uploads the optical frame after {@link #use()} binds this program. */
    void uploadCurvature(RingworldCurvatureFrame frame) {
        if (program == 0 || curvedRayUniforms == null) {
            throw new IllegalStateException("SS cloud curvature upload requires a linked program");
        }
        curvedRayUniforms.upload(frame);
    }

    private void create() {
        if (!OpenGlHelper.shadersSupported) {
            throw new IllegalStateException("SS cloud renderer requires OpenGL shader support");
        }
        int vertex = 0;
        int fragment = 0;
        int candidate = 0;
        try {
            vertex = compile(VERTEX, OpenGlHelper.GL_VERTEX_SHADER);
            fragment = compile(FRAGMENT, OpenGlHelper.GL_FRAGMENT_SHADER);
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) throw new IllegalStateException("Unable to allocate SS cloud shader program");
            OpenGlHelper.glAttachShader(candidate, vertex);
            OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to link SS cloud shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            detach(candidate, vertex);
            detach(candidate, fragment);
            final int linkedProgram = candidate;
            bindUniforms(name -> OpenGlHelper.glGetUniformLocation(linkedProgram, name));
            program = candidate;
            candidate = 0;
        } catch (RuntimeException exception) {
            dispose();
            throw exception;
        } finally {
            if (candidate != 0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex != 0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    private static int coverage(RingworldSunshade.BandCoverage value) {
        return switch (value) {
            case EMPTY -> 0;
            case PARTIAL -> 1;
            case FULL -> 2;
        };
    }

    void bindUniforms(ToIntFunction<String> lookup) {
        meshOffset = uniform(lookup, "uMeshOffset");
        stripZ = uniform(lookup, "uStripZ");
        board = uniform(lookup, "uBoard");
        bands = uniform(lookup, "uBands");
        bandEdge = uniform(lookup, "uBandEdge");
        coverage = uniform(lookup, "uCoverage");
        sideFeather = uniform(lookup, "uSideFeather");
        motionFeather = uniform(lookup, "uMotionFeather");
        weather = uniform(lookup, "uWeather");
        cloudLodEnabled = uniform(lookup, "uCloudLodEnabled");
        cloudTransitionWidths = uniform(lookup, "uCloudTransitionWidths");
        cloudBottomBrightness = uniform(lookup, "uCloudBottomBrightness");
        curvedRayUniforms = new RingworldCurvedRayUniforms(lookup);
        distantDepthUniforms = new RingworldDistantDepthUniforms(lookup);
        boardDepthUniforms = new RingworldBoardDepthUniforms(lookup);
    }

    private static int uniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("SS cloud shader lacks uniform " + name);
        return location;
    }

    private static int compile(ResourceLocation location, int type) {
        int shader = OpenGlHelper.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Unable to allocate SS cloud shader for " + location);
        try {
            byte[] source = location.equals(FRAGMENT) ? RingworldShaderSource.readWithCloudStyle(location)
                    : RingworldShaderSource.readCurved(location);
            ByteBuffer bytes = BufferUtils.createByteBuffer(source.length);
            bytes.put(source).flip();
            OpenGlHelper.glShaderSource(shader, bytes);
            OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Unable to compile SS cloud shader " + location + ": "
                        + OpenGlHelper.glGetShaderInfoLog(shader, 32768));
            }
            return shader;
        } catch (RuntimeException exception) {
            OpenGlHelper.glDeleteShader(shader);
            throw exception;
        }
    }

    private static byte[] read(ResourceLocation location) {
        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(location);
             InputStream stream = resource.getInputStream()) {
            return IOUtils.toByteArray(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read SS cloud shader " + location, exception);
        }
    }

    private static void detach(int program, int shader) {
        if (OpenGlHelper.openGL21) GL20.glDetachShader(program, shader);
        else ARBShaderObjects.glDetachObjectARB(program, shader);
    }

    private static void uniform1i(int location, int value) {
        if (OpenGlHelper.openGL21) GL20.glUniform1i(location, value);
        else ARBShaderObjects.glUniform1iARB(location, value);
    }

    private static void uniform1f(int location, double value) {
        if (OpenGlHelper.openGL21) GL20.glUniform1f(location, (float) value);
        else ARBShaderObjects.glUniform1fARB(location, (float) value);
    }

    private static void uniform2f(int location, double x, double y) {
        if (OpenGlHelper.openGL21) GL20.glUniform2f(location, (float) x, (float) y);
        else ARBShaderObjects.glUniform2fARB(location, (float) x, (float) y);
    }

    private static void uniform3f(int location, double x, double y, double z) {
        if (OpenGlHelper.openGL21) GL20.glUniform3f(location, (float) x, (float) y, (float) z);
        else ARBShaderObjects.glUniform3fARB(location, (float) x, (float) y, (float) z);
    }

    private static void uniform4f(int location, double x, double y, double z, double w) {
        if (OpenGlHelper.openGL21) GL20.glUniform4f(location, (float) x, (float) y, (float) z, (float) w);
        else ARBShaderObjects.glUniform4fARB(location, (float) x, (float) y, (float) z, (float) w);
    }

    private static void requireFiniteFloat(String name, double value) {
        if (!Double.isFinite(value) || !Float.isFinite((float) value)) {
            throw new IllegalStateException("SS cloud " + name + " is not representable as a finite float");
        }
    }

    private static void requirePositiveFloat(String name, double value) {
        requireFiniteFloat(name, value);
        if (value <= 0.0D || (float) value <= 0.0F) {
            throw new IllegalStateException("SS cloud " + name + " must be positive");
        }
    }

    private static void requireNonNegativeFloat(String name, double value) {
        requireFiniteFloat(name, value);
        if (value < 0.0D) throw new IllegalStateException("SS cloud " + name + " must not be negative");
    }

    private static void requireUnitInterval(String name, double value) {
        requireFiniteFloat(name, value);
        if (value < 0.0D || value > 1.0D) {
            throw new IllegalStateException("SS cloud " + name + " must be within [0, 1]");
        }
    }
}
