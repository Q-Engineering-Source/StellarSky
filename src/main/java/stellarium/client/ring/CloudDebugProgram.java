package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import stellarium.client.ring.cloud.CloudCurvaturePolicy;

/**
 * Reuses the cloud LOD vertex expansion with a deliberately small overlay
 * fragment program. The caller owns all draw state and the instance VBO.
 */
final class CloudDebugProgram {
    private static final ResourceLocation VERTEX = new ResourceLocation("stellarium", "shaders/ringworld/cloud_lod.vert");
    private static final ResourceLocation FRAGMENT = new ResourceLocation("stellarium", "shaders/ringworld/cloud_debug.frag");

    private int program;
    private int rotation, origin, projection, radius, eye, viewport, high, low, mode, distanceBand;
    private int groundHigh, groundLow, groundOffset;
    private int localMode, localEye, localBounds, localTransform;
    private RingworldDistantDepthUniforms distant;
    private RingworldBoardDepthUniforms boardDepth;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    void use(RingworldCurvatureFrame optics, double wind, int debugMode, int x, int y, int width, int height) {
        if (optics == null) throw new IllegalArgumentException("Cloud debug requires a curvature frame");
        if (!Double.isFinite(wind) || debugMode < 1 || debugMode > 2 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid cloud debug draw inputs");
        }
        if (program == 0) create();
        OpenGlHelper.glUseProgram(program);
        float[] localX = RingworldCurvedRayUniforms.split(optics.opticalEye().x() - wind);
        GL20.glUniform2f(localEye, localX[0], localX[1]);
        double angle = (optics.opticalEye().x() - wind) / optics.geometry().radiusMeters();
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("Cloud debug wind pose is not finite");
        split4(rotation, Math.cos(angle), Math.sin(angle));
        split4(origin, optics.renderOrigin().y(), optics.renderOrigin().z());
        float[] splitRadius = RingworldCurvedRayUniforms.split(optics.geometry().radiusMeters());
        GL20.glUniform2f(radius, splitRadius[0], splitRadius[1]);
        GL20.glUniform3f(eye, optics.cameraX(), optics.cameraY(), optics.cameraZ());
        optics.copyProjection(matrix);
        RingworldBoardClipProjection.removeFarPlane(matrix);
        GL20.glUniformMatrix4(projection, false, matrix);
        GL20.glUniform4f(viewport, x, y, width, height);
        GL20.glUniform1i(high, 9);
        GL20.glUniform1i(low, 10);
        GL20.glUniform1i(groundHigh, 7);
        GL20.glUniform1i(groundLow, 8);
        GL20.glUniform2i(groundOffset, x, y);
        GL20.glUniform1i(mode, debugMode);
        distant.upload();
        boardDepth.upload();
    }

    /** Limits each page overlay to the identical distance tier used by its opaque raster draw. */
    void pageBand(double minimumDistance, double maximumDistance) {
        if (!Double.isFinite(minimumDistance) || !Double.isFinite(maximumDistance)
                || !(maximumDistance > minimumDistance)) {
            throw new IllegalArgumentException("Cloud debug distance band must be finite and increasing");
        }
        float minimum = (float) minimumDistance, maximum = (float) maximumDistance;
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum)) {
            throw new IllegalArgumentException("Cloud debug distance band exceeds float range");
        }
        GL20.glUniform2f(distanceBand, minimum, maximum);
    }

    void bindUniforms(ToIntFunction<String> lookup) {
        localMode = required(lookup, "uCloudLocalMode"); localEye = required(lookup, "uCloudLocalEyeX");
        localBounds = required(lookup, "uCloudLocalBounds"); localTransform = required(lookup, "uCloudLocalTransform");
        rotation = required(lookup, "uCloudRotation"); origin = required(lookup, "uCloudOriginYZ");
        projection = required(lookup, "uCloudClipProjection"); radius = required(lookup, "uSSRadiusHiLo");
        eye = required(lookup, "uSSEyeRelative"); viewport = required(lookup, "uDebugViewport");
        high = required(lookup, "uCloudHigh"); low = required(lookup, "uCloudLow");
        mode = required(lookup, "uDebugMode"); distanceBand = required(lookup, "uDebugDistanceBand");
        groundHigh = required(lookup, "uCloudGroundHigh"); groundLow = required(lookup, "uCloudGroundLow");
        groundOffset = required(lookup, "uCloudGroundPixelOffset");
        distant = new RingworldDistantDepthUniforms(lookup);
        boardDepth = new RingworldBoardDepthUniforms(lookup);
    }

    void dispose() {
        if (program != 0) OpenGlHelper.glDeleteProgram(program);
        program = 0;
    }

    void localSlab(CloudCurvaturePolicy.Segment slab) {
        GL20.glUniform1i(localMode, slab == null ? 0 : slab.curved() ? 2 : 1);
        if (slab == null) return;
        GL20.glUniform2f(localBounds, (float)slab.minX(), (float)slab.maxX());
        GL20.glUniform4f(localTransform, (float)slab.slopeX(), (float)slab.interceptX(),
                (float)slab.slopeY(), (float)slab.interceptY());
    }

    private void create() {
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(VERTEX, OpenGlHelper.GL_VERTEX_SHADER);
            fragment = compile(FRAGMENT, OpenGlHelper.GL_FRAGMENT_SHADER);
            candidate = OpenGlHelper.glCreateProgram();
            if (candidate == 0) throw new IllegalStateException("Cannot allocate cloud debug program");
            OpenGlHelper.glAttachShader(candidate, vertex);
            OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Cannot link cloud debug shader: "
                        + OpenGlHelper.glGetProgramInfoLog(candidate, 32768));
            }
            GL20.glDetachShader(candidate, vertex);
            GL20.glDetachShader(candidate, fragment);
            int linked = candidate;
            bindUniforms(name -> OpenGlHelper.glGetUniformLocation(linked, name));
            program = candidate;
            candidate = 0;
        } finally {
            if (candidate != 0) OpenGlHelper.glDeleteProgram(candidate);
            if (vertex != 0) OpenGlHelper.glDeleteShader(vertex);
            if (fragment != 0) OpenGlHelper.glDeleteShader(fragment);
        }
    }

    private static int compile(ResourceLocation resource, int type) {
        int shader = OpenGlHelper.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Cannot allocate cloud debug shader " + resource);
        try {
            byte[] source = RingworldShaderSource.readCurved(resource);
            ByteBuffer buffer = BufferUtils.createByteBuffer(source.length);
            buffer.put(source).flip();
            OpenGlHelper.glShaderSource(shader, buffer);
            OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Cannot compile cloud debug shader " + resource + ": "
                        + OpenGlHelper.glGetShaderInfoLog(shader, 32768));
            }
            return shader;
        } catch (RuntimeException | Error failure) {
            OpenGlHelper.glDeleteShader(shader);
            throw failure;
        }
    }

    private static int required(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("Cloud debug shader lacks uniform " + name);
        return location;
    }

    private static void split4(int location, double a, double b) {
        float[] x = RingworldCurvedRayUniforms.split(a), y = RingworldCurvedRayUniforms.split(b);
        GL20.glUniform4f(location, x[0], x[1], y[0], y[1]);
    }
}
