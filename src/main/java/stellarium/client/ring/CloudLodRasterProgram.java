package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarium.client.ring.cloud.CloudCurvaturePolicy;
import stellarium.world.ring.RingworldSunshade;

/** Shader contract for the cached, instanced cloud-page raster path. */
final class CloudLodRasterProgram {
    private static final ResourceLocation VERTEX = new ResourceLocation("stellarium", "shaders/ringworld/cloud_lod.vert");
    private static final ResourceLocation FRAGMENT = new ResourceLocation("stellarium", "shaders/ringworld/cloud_lod.frag");

    private int program;
    private int rotation, origin, projection, radius, eye, viewport, pass, high, low, atlas, materialMode;
    private int pageOrigin, pageExtent, atlasInfo, cell, baseY, distanceBand, farBand, rain;
    private int groundHigh, groundLow, groundOffset;
    private int localMode, localEye, localBounds, localTransform, localAtlasOffset, localShadeOffset;
    private double physicalEyeX, localOriginZ;
    private int stripZ, board, bands, bandEdge, coverage, sideFeather, motionFeather, bottomBrightness, materialXRelative, observerYZ;
    private RingworldDistantDepthUniforms distant;
    private RingworldBoardDepthUniforms boardDepth;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    void use(SSCloudFrame cloudFrame, RingworldCurvatureFrame frame, float weather, RingworldMeshDistance.Selection ground,
             int viewportX, int viewportY) {
        if (program == 0) create();
        if (!Float.isFinite(weather) || weather < 0.0F || weather > 1.0F) {
            throw new IllegalArgumentException("Cloud LOD weather must be finite in [0, 1]");
        }
        if (ground == null || ground.width() <= 0 || ground.height() <= 0) {
            throw new IllegalArgumentException("Cloud LOD raster requires a ground distance selection");
        }
        OpenGlHelper.glUseProgram(program);
        GL20.glUniform1i(materialMode, CloudDebugSettings.materialMode() == CloudDebugSettings.MaterialMode.CACHED ? 1 : 0);
        double wind = cloudFrame.meshMotion().windOffsetBlocks();
        physicalEyeX = frame.opticalEye().x() - wind;
        localOriginZ = frame.renderOrigin().z();
        float[] localX = RingworldCurvedRayUniforms.split(physicalEyeX);
        GL20.glUniform2f(localEye, localX[0], localX[1]);
        GL20.glUniform3f(localShadeOffset, (float)(frame.opticalEye().x() - cloudFrame.snapshot().observer().x()),
                (float)-cloudFrame.snapshot().observer().y(), (float)(localOriginZ - cloudFrame.snapshot().observer().z()));
        double angle = (frame.opticalEye().x() - wind) / frame.geometry().radiusMeters();
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("Cloud LOD wind pose is not finite");
        split4(rotation, Math.cos(angle), Math.sin(angle));
        split4(origin, frame.renderOrigin().y(), frame.renderOrigin().z());
        float[] splitRadius = RingworldCurvedRayUniforms.split(frame.geometry().radiusMeters());
        GL20.glUniform2f(radius, splitRadius[0], splitRadius[1]);
        GL20.glUniform3f(eye, frame.cameraX(), frame.cameraY(), frame.cameraZ());
        frame.copyProjection(matrix);
        RingworldBoardClipProjection.removeFarPlane(matrix);
        GL20.glUniformMatrix4(projection, false, matrix);
        GL20.glUniform1i(high, 9);
        GL20.glUniform1i(low, 10);
        GL20.glUniform1i(atlas, 2);
        GL20.glUniform1i(groundHigh, 7);
        GL20.glUniform1i(groundLow, 8);
        GL20.glUniform2i(groundOffset, viewportX, viewportY);
        GL20.glUniform1f(rain, weather);
        uploadPointLocalShade(cloudFrame);
        distant.upload();
        boardDepth.upload();
    }

    void stage(int stage, int viewportX, int viewportY, int width, int height) {
        if (stage < RingworldMeshDistance.HIGH || stage > RingworldMeshDistance.COLOR || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid cloud LOD raster stage or viewport");
        }
        GL20.glUniform1i(pass, stage);
        GL20.glUniform4f(viewport, viewportX, viewportY, width, height);
    }

    /** Uploads all page-specific atlas, material and distance eligibility inputs immediately before its draw. */
    void pageDrawParameters(CloudWorldCache.Page page, double finestCellSize, double cloudThickness,
                            double baseYValue, double minimumDistance, double maximumDistance,
                            double farStart, double farEnd) {
        if (page == null || !Double.isFinite(finestCellSize) || finestCellSize <= 0.0D
                || !Double.isFinite(cloudThickness) || cloudThickness <= 0.0D
                || !Double.isFinite(baseYValue) || !(maximumDistance > minimumDistance)
                || !(farEnd > farStart)) {
            throw new IllegalArgumentException("Invalid cloud LOD page draw parameters");
        }
        CloudLodLayout.AtlasLevel level = page.level();
        double pageCell = finestCellSize * level.xzScale();
        GL20.glUniform2f(localAtlasOffset, (float)(physicalEyeX - page.originX() * pageCell),
                (float)(localOriginZ - page.originZ() * pageCell));
        split4(pageOrigin, page.originX(), page.originZ());
        GL20.glUniform2i(pageExtent, level.width(), level.depth());
        GL20.glUniform4f(atlasInfo, level.offsetY(), level.depth(), level.layers(), level.xzScale());
        GL20.glUniform3f(cell, finite(finestCellSize, "cell size"), finite(cloudThickness, "cloud thickness"), level.yScale());
        GL20.glUniform1f(baseY, finite(baseYValue, "cloud base"));
        GL20.glUniform2f(distanceBand, finite(minimumDistance, "minimum distance"), finite(maximumDistance, "maximum distance"));
        GL20.glUniform2f(farBand, finite(farStart, "far handoff start"), finite(farEnd, "far handoff end"));
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
            if (candidate == 0) throw new IllegalStateException("Cannot allocate cloud LOD raster program");
            OpenGlHelper.glAttachShader(candidate, vertex);
            OpenGlHelper.glAttachShader(candidate, fragment);
            OpenGlHelper.glLinkProgram(candidate);
            if (OpenGlHelper.glGetProgrami(candidate, OpenGlHelper.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Cannot link cloud LOD raster: "
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

    void bindUniforms(ToIntFunction<String> lookup) {
        localMode = required(lookup, "uCloudLocalMode"); localEye = required(lookup, "uCloudLocalEyeX");
        localBounds = required(lookup, "uCloudLocalBounds"); localTransform = required(lookup, "uCloudLocalTransform");
        localAtlasOffset = required(lookup, "uCloudLocalAtlasOffset"); localShadeOffset = required(lookup, "uCloudLocalShadeOffset");
        rotation = required(lookup, "uCloudRotation"); origin = required(lookup, "uCloudOriginYZ");
        projection = required(lookup, "uCloudClipProjection"); radius = required(lookup, "uSSRadiusHiLo");
        eye = required(lookup, "uSSEyeRelative"); viewport = required(lookup, "uCloudViewport");
        pass = required(lookup, "uCloudPass"); high = required(lookup, "uCloudHigh"); low = required(lookup, "uCloudLow");
        materialMode = required(lookup, "uCloudMaterialMode");
        atlas = required(lookup, "uCloudAtlas"); pageOrigin = required(lookup, "uCloudPageOriginHiLo");
        pageExtent = required(lookup, "uCloudPageExtent");
        atlasInfo = required(lookup, "uCloudAtlasInfo"); cell = required(lookup, "uCloudCell");
        baseY = required(lookup, "uCloudBaseY"); distanceBand = required(lookup, "uCloudDistanceBand");
        farBand = required(lookup, "uCloudFarBand"); rain = required(lookup, "uCloudRain");
        groundHigh = required(lookup, "uCloudGroundHigh"); groundLow = required(lookup, "uCloudGroundLow");
        groundOffset = required(lookup, "uCloudGroundPixelOffset");
        stripZ = required(lookup, "uStripZ"); board = required(lookup, "uBoard");
        bands = required(lookup, "uBands"); bandEdge = required(lookup, "uBandEdge"); coverage = required(lookup, "uCoverage");
        sideFeather = required(lookup, "uSideFeather"); motionFeather = required(lookup, "uMotionFeather");
        bottomBrightness = required(lookup, "uCloudBottomBrightness");
        materialXRelative = required(lookup, "uCloudMaterialXRelativeHiLo"); observerYZ = required(lookup, "uCloudObserverYZ");
        distant = new RingworldDistantDepthUniforms(lookup);
        boardDepth = new RingworldBoardDepthUniforms(lookup);
    }

    private static int compile(ResourceLocation resource, int type) {
        int shader = OpenGlHelper.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Cannot allocate cloud LOD shader " + resource);
        try {
            byte[] source = RingworldShaderSource.readCurved(resource);
            ByteBuffer buffer = BufferUtils.createByteBuffer(source.length);
            buffer.put(source).flip();
            OpenGlHelper.glShaderSource(shader, buffer);
            OpenGlHelper.glCompileShader(shader);
            if (OpenGlHelper.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Cannot compile cloud LOD shader " + resource + ": "
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
        if (location < 0) throw new IllegalStateException("Cloud LOD shader lacks uniform " + name);
        return location;
    }

    private static float finite(double value, String name) {
        float result = (float) value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException(name + " is outside float range");
        return result;
    }

    private static void split4(int location, double a, double b) {
        float[] x = RingworldCurvedRayUniforms.split(a), y = RingworldCurvedRayUniforms.split(b);
        GL20.glUniform4f(location, x[0], x[1], y[0], y[1]);
    }

    private void uploadPointLocalShade(SSCloudFrame frame) {
        var snapshot = frame.snapshot();
        RingworldSunshade.CameraRelativeBands localBands = snapshot.sunshade().cameraRelativeBands(snapshot.phase(),
                snapshot.observer().x(), snapshot.observer().z());
        GL20.glUniform2f(stripZ, finite(stellarium.world.ring.RingworldStripBounds.BOARD_MIN_Z - snapshot.observer().z(), "strip min"),
                finite(stellarium.world.ring.RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE - snapshot.observer().z(), "strip max"));
        GL20.glUniform2f(board, finite(snapshot.sunshadeHeightBlocks() - snapshot.observer().y(), "board base"),
                finite(snapshot.sunshadeThicknessBlocks(), "board thickness"));
        GL20.glUniform4f(bands, finite(localBands.directionX(), "band x"), finite(localBands.directionZ(), "band z"),
                finite(localBands.spacingBlocks(), "band spacing"), finite(localBands.panelWidthBlocks(), "panel width"));
        GL20.glUniform2f(bandEdge, finite(localBands.edgeRelativeToRenderOriginBlocks(), "band edge"),
                finite(localBands.edgeOrientation(), "band orientation"));
        GL20.glUniform1i(coverage, switch (localBands.coverage()) { case EMPTY -> 0; case PARTIAL -> 1; case FULL -> 2; });
        GL20.glUniform1f(sideFeather, finite(snapshot.sunshade().sideFeatherBlocks(), "side feather"));
        GL20.glUniform1f(motionFeather, finite(snapshot.sunshade().featherBlocks(), "motion feather"));
        GL20.glUniform1f(bottomBrightness, finite(frame.bottomBrightness(), "bottom brightness"));
        split2(materialXRelative, frame.meshMotion().windOffsetBlocks() - snapshot.observer().x());
        GL20.glUniform2f(observerYZ, finite(snapshot.observer().y(), "observer y"), finite(snapshot.observer().z(), "observer z"));
    }

    private static void split2(int location, double value) {
        float[] split = RingworldCurvedRayUniforms.split(value);
        GL20.glUniform2f(location, split[0], split[1]);
    }
}
