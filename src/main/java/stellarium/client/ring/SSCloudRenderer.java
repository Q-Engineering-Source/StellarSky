package stellarium.client.ring;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import stellarium.StellarSky;
import stellarium.client.ClientSettings;
import stellarium.client.ring.cloud.CloudClipBounds;
import stellarium.client.ring.cloud.CloudFieldSettings;
import stellarium.client.ring.cloud.CloudGeometrySettings;
import stellarium.client.ring.cloud.CloudMask;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarium.client.ring.cloud.CloudWindowStreamer;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudLodTransition;
import stellarium.client.ring.cloud.CloudMesh;
import stellarium.client.ring.cloud.CloudMeshBuilder;
import stellarium.client.ring.cloud.CloudMeshCacheKey;
import stellarium.client.ring.cloud.CloudMotionFrame;
import stellarium.client.ring.cloud.CloudRayBudget;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldAirProfile;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldStripBounds;
import stellarium.world.ring.RingworldSunshade;

/**
 * Client-only opaque, closed box-cloud pass for an exact frozen ringworld frame.
 *
 * <p>The mesh is deliberately anchored in coarse 16-cell windows. Fractional
 * observer movement, the bounded +X wind remainder, illumination, and weather
 * only change uniforms; they do not churn geometry or VBO allocations.</p>
 */
public final class SSCloudRenderer {
    private static final int COARSE_ANCHOR_CELLS = 16;
    private static final int MIN_REQUESTED_VISIBLE_CELLS = 16;
    private static final int MAX_REQUESTED_VISIBLE_CELLS =
            CloudGeometrySettings.MAX_VISIBLE_CELL_RADIUS - COARSE_ANCHOR_CELLS;
    private static final int FLOAT_STRIDE_BYTES = CloudMesh.FLOATS_PER_VERTEX * Float.BYTES;
    private static final SSCloudRenderer INSTANCE = new SSCloudRenderer();

    private final SSCloudProgram program = new SSCloudProgram();
    private final SSCloudHorizonProgram horizonProgram = new SSCloudHorizonProgram();
    private final SSCloudTraceCachePolicy traceCachePolicy = new SSCloudTraceCachePolicy();
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    private final float[] projectionValues = new float[16];
    private final float[] modelViewValues = new float[16];
    private ExecutorService cacheExecutor;
    private CloudWindowStreamer cacheStreamer;
    private CloudWorldCache cache;
    private CloudWindowStreamer.Ready prepared;
    private CloudWindowStreamer.Target requestedCache;
    private CloudFieldSettings cacheSettings;
    private Object cacheScopeWorld;
    private Object cacheScopeScene;
    private long resourceGeneration;
    private CloudMeshCacheKey cachedKey;
    private Object cachedWorld;
    private Object cachedScene;
    private RingworldAirProfile cachedAirProfile;
    private int vertexBuffer;
    private int vertexArray;
    private int vertexCount;
    private boolean cachedCloseWindow;
    private SSCloudFrame currentFrame;
    private int reportedRequestedHorizon = -1;
    private double reportedEffectiveHorizon = Double.NaN;
    private long clientTicks;
    private boolean reportedTailFallback;

    private SSCloudRenderer() {
        createStreamer();
    }

    /** Called once for every client tick; this is a visual wind clock, not time authority. */
    public static void onClientTick() {
        if (INSTANCE.clientTicks >= CloudMotionFrame.MAX_SUPPORTED_CLIENT_TICKS) throw new IllegalStateException("SS cloud wind clock exhausted");
        INSTANCE.clientTicks++;
    }

    /** Called by the post-sky/pre-terrain render hook with that hook's original partial tick. */
    public static void renderCurrentWorld(float partialTicks) {
        try (var board = RingworldBoardMeshDistance.bindSelected()) {
            INSTANCE.render(partialTicks);
        }
    }

    /** Resource reload invalidation preserves the cosmetic wind phase. */
    public static void invalidate() {
        INSTANCE.clearCachedResources(true);
    }

    /** Releases GL objects on the client-world lifecycle boundary. */
    public static void dispose() {
        INSTANCE.clearCachedResources(true);
        INSTANCE.releaseStreamer();
    }

    private void render(float partialTicks) {
        if (!Float.isFinite(partialTicks) || partialTicks < 0.0F || partialTicks > 1.0F) {
            throw new IllegalArgumentException("SS cloud partialTicks must be within [0, 1]");
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        RingworldDisplaySnapshot snapshot = RingworldRenderSnapshots.current();
        if (snapshot == null || snapshot.phase() == null || minecraft.world == null) return;
        StellarScene scene = StellarScene.getScene(minecraft.world);
        if (snapshot.world() != minecraft.world || snapshot.scene() != scene) return;

        ClientSettings settings = StellarSky.PROXY.getClientSettings();
        RingworldCurvatureFrame optics=RingworldRenderSnapshots.currentDistantCurvatureFrameFor(snapshot.world(),snapshot.scene());
        boolean connected=ProceduralRingModelRenderer.wantsConnection() && optics!=null;
        if (!connected && ProceduralRingModelRenderer.render(snapshot, settings, partialTicks)) {
            traceCachePolicy.clear();
            currentFrame = null;
            return;
        }
        if (!settings.renderOwnClouds && !connected) {
            traceCachePolicy.clear();
            currentFrame = null;
            return;
        }
        CloudFieldSettings fieldSettings = new CloudFieldSettings(settings.ownCloudSeed, settings.renderOwnClouds?settings.ownCloudCoverage:0.0D,
                settings.ownCloudWorleyEnabled, settings.ownCloudErosion, settings.ownCloudLayers);
        CloudGeometrySettings geometry = paddedGeometry(settings.ownCloudCellSize,
                settings.ownCloudCellHeight, settings.ownCloudRadiusCells, settings.ownCloudLayers);
        CloudClipBounds clipBounds = new CloudClipBounds(snapshot.airProfile().lowerY(),
                snapshot.airProfile().upperY(), RingworldStripBounds.BOARD_MIN_Z,
                RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        if (cacheScopeWorld != snapshot.world() || cacheScopeScene != snapshot.scene()) {
            cacheScopeWorld = snapshot.world();
            cacheScopeScene = snapshot.scene();
            resourceGeneration++;
            requestedCache = null;
            prepared = null;
            cache = null;
            if (cacheStreamer != null) cacheStreamer.invalidate();
        }
        CloudMotionFrame motion = CloudMotionFrame.at(clientTicks, partialTicks,
                snapshot.observer().x(), snapshot.observer().z(), geometry);
        CloudMotionFrame coarseMotion = coarseFrame(motion, snapshot.observer().x(), snapshot.observer().z(), geometry);
        boolean closeWindow = !connected && !settings.renderHorizonClouds;
        CloudWindowStreamer.Ready ready = cacheFor(fieldSettings, coarseMotion, geometry, clipBounds,
                settings.ownCloudBaseY, closeWindow, connected?optics.geometry().radiusMeters():0.0D);
        if (ready == null) {
            traceCachePolicy.clear();
            currentFrame = null; // explicit pending state: never expose a repeated or invalid page.
            if (connected) ProceduralRingModelRenderer.render(snapshot,settings,partialTicks);
            return;
        }
        CloudWorldCache currentCache = ready.cache();
        CloudMotionFrame meshMotion = motionForAnchor(coarseMotion, ready.target().anchorCellX(), ready.target().anchorCellZ(),
                snapshot.observer().x(), snapshot.observer().z(), geometry);
        RingworldSunshade.CameraRelativeBands bands = snapshot.sunshade().cameraRelativeBands(snapshot.phase(),
                snapshot.observer().x(), snapshot.observer().z());
        double weather = minecraft.world.getRainStrength(partialTicks);
        if (!Double.isFinite(weather)) throw new IllegalStateException("SS cloud weather is not finite");
        weather = Math.max(0.0D, Math.min(1.0D, weather));
        CloudLodTransition transition = new CloudLodTransition(settings.ownCloudLodFineTransitionBlocks,
                settings.ownCloudLodMidTransitionBlocks, settings.ownCloudLodLowTransitionBlocks);
        CloudRayBudget.PreparedHorizon horizon = CloudRayBudget.prepare(settings.ownCloudHorizonBlocks, geometry, transition);
        if (!connected) reportReducedHorizon(horizon, geometry);
        SSCloudFrame frame = new SSCloudFrame(snapshot, fieldSettings, currentCache, geometry, clipBounds, meshMotion,
                ready.meshKey(), horizon, closeWindow,
                settings.ownCloudBaseY, settings.ownCloudCullFine,
                settings.ownCloudCullMid, settings.ownCloudCullLow, settings.ownCloudCullVeryLow,
                captureCameraFrame(), transition, settings.ownCloudBottomBrightness);
        if (connected) {
            traceCachePolicy.clear();
            if (ready.rasterGeometry()==null) throw new IllegalStateException("Connected cloud publication lacks raster geometry");
            currentFrame=ProceduralRingModelRenderer.renderConnected(frame,ready.rasterGeometry(),settings,partialTicks)?frame:null;
            return;
        }
        frame = traceCachePolicy.select(frame, horizonProgram.traceCacheEnabled()
                ? RingworldRenderSnapshots.currentDistantCurvatureFrameFor(snapshot.world(), snapshot.scene()) : null);
        if (!closeWindow && frame.tailCoverage().fallbackNeeded() && !reportedTailFallback) {
            StellarSky.INSTANCE.getLogger().info("SS cloud tail uses a bounded pixel-coverage proxy: "
                    + "required={} blocks, cached={} blocks; no atlas growth or clamped-depth wall",
                    frame.tailCoverage().requiredDistance(), frame.tailCoverage().cachedDistance());
            reportedTailFallback = true;
        }
        if (settings.renderHorizonClouds) horizonProgram.render(frame, bands, weather);
        ensureMesh(frame, ready.mesh());
        if (vertexCount != 0) draw(frame, bands, weather);
        // Publish only after the atlas (when enabled) and matching near VBO are both valid.
        currentFrame = frame;
    }

    static SSCloudFrame currentFrameFor(Object world, Object scene, RingworldDisplaySnapshot snapshot) {
        SSCloudFrame frame = INSTANCE.currentFrame;
        return frame != null && frame.matches(world, scene, snapshot) ? frame : null;
    }

    static void bindAtlasFor(SSCloudFrame frame) {
        INSTANCE.horizonProgram.bindAtlas(frame);
    }

    private CloudWindowStreamer.Ready cacheFor(CloudFieldSettings settings, CloudMotionFrame motion,
                                                CloudGeometrySettings geometry, CloudClipBounds clipBounds,
                                                double cloudBaseY, boolean closeWindow, double rasterRadius) {
        if (cacheStreamer == null) createStreamer();
        if (!settings.equals(cacheSettings)) { cacheSettings = settings; resourceGeneration++; requestedCache = null; cache = null; cacheStreamer.invalidate(); }
        CloudWindowStreamer.Target target = new CloudWindowStreamer.Target(settings, geometry, motion.anchorCellX(), motion.anchorCellZ(),
                cloudBaseY, clipBounds, closeWindow, resourceGeneration,rasterRadius);
        request(target);
        Throwable cacheFailure = cacheStreamer.failure(target);
        CloudWindowStreamer.Ready ready = cacheStreamer.ready();
        if (ready != null && accepts(ready, target)) {
            prepared = ready; cache = ready.cache();
        }
        if (prepared != null && accepts(prepared, target)) return prepared;
        if (cacheFailure != null) throw new IllegalStateException("SS cloud cache generation failed", cacheFailure);
        return null;
    }

    private void request(CloudWindowStreamer.Target target) {
        if (!target.equals(requestedCache)) { requestedCache = target; cacheStreamer.request(target); }
    }
    static boolean accepts(CloudWindowStreamer.Ready ready, CloudWindowStreamer.Target target) {
        return ready.covers(target, COARSE_ANCHOR_CELLS);
    }
    private static CloudMotionFrame motionForAnchor(CloudMotionFrame current, long anchorX, long anchorZ,
                                                     double observerX, double observerZ, CloudGeometrySettings geometry) {
        return new CloudMotionFrame(anchorX, anchorZ, anchorX * geometry.cellSizeBlocks() + current.windOffsetBlocks() - observerX,
                anchorZ * geometry.cellSizeBlocks() - observerZ, current.windOffsetBlocks());
    }

    private CloudCameraFrame captureCameraFrame() {
        projection.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        modelView.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        for (int index = 0; index < 16; index++) { projectionValues[index] = projection.get(index); modelViewValues[index] = modelView.get(index); }
        try {
            return CloudCameraFrame.from(projectionValues, modelViewValues, viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("SS cloud camera frame is invalid", exception);
        }
    }

    private void reportReducedHorizon(CloudRayBudget.PreparedHorizon horizon, CloudGeometrySettings geometry) {
        if (horizon.effectiveHorizon() >= horizon.requestedHorizon()) return;
        if (reportedRequestedHorizon == horizon.requestedHorizon()
                && Double.compare(reportedEffectiveHorizon, horizon.effectiveHorizon()) == 0) return;
        StellarSky.INSTANCE.getLogger().warn("SS cloud horizon reduced from {} to {} blocks for {} block cells; "
                        + "this preserves the {}-step traversal bound and is not a performance claim",
                horizon.requestedHorizon(), horizon.effectiveHorizon(), geometry.cellSizeBlocks(),
                CloudRayBudget.MAX_STEPS);
        reportedRequestedHorizon = horizon.requestedHorizon();
        reportedEffectiveHorizon = horizon.effectiveHorizon();
    }

    static CloudMotionFrame coarseFrame(CloudMotionFrame motion, double observerX, double observerZ,
                                        CloudGeometrySettings geometry) {
        long anchorX = Math.floorDiv(motion.anchorCellX(), COARSE_ANCHOR_CELLS) * COARSE_ANCHOR_CELLS;
        long anchorZ = Math.floorDiv(motion.anchorCellZ(), COARSE_ANCHOR_CELLS) * COARSE_ANCHOR_CELLS;
        double offsetX = anchorX * geometry.cellSizeBlocks() + motion.windOffsetBlocks() - observerX;
        double offsetZ = anchorZ * geometry.cellSizeBlocks() - observerZ;
        return new CloudMotionFrame(anchorX, anchorZ, offsetX, offsetZ, motion.windOffsetBlocks());
    }

    /**
     * Reserves one complete coarse-anchor span around the requested view
     * radius. A mesh centered at the earliest/latest cell of that span still
     * covers the configured radius on both sides without a fractional rebuild.
     */
    static CloudGeometrySettings paddedGeometry(double cellSizeBlocks, double thicknessBlocks,
                                                int requestedVisibleCellRadius) {
        return paddedGeometry(cellSizeBlocks, thicknessBlocks, requestedVisibleCellRadius, 8);
    }

    static CloudGeometrySettings paddedGeometry(double cellSizeBlocks, double layerHeightBlocks,
                                                int requestedVisibleCellRadius, int layers) {
        if (requestedVisibleCellRadius < MIN_REQUESTED_VISIBLE_CELLS
                || requestedVisibleCellRadius > MAX_REQUESTED_VISIBLE_CELLS) {
            throw new IllegalArgumentException("SS cloud requested radius must be within ["
                    + MIN_REQUESTED_VISIBLE_CELLS + ", " + MAX_REQUESTED_VISIBLE_CELLS + "]");
        }
        return CloudGeometrySettings.forLayers(cellSizeBlocks, layerHeightBlocks, layers,
                requestedVisibleCellRadius + COARSE_ANCHOR_CELLS);
    }

    private void ensureMesh(SSCloudFrame frame, CloudMesh mesh) {
        CloudMeshCacheKey key = frame.meshKey();
        RingworldDisplaySnapshot snapshot = frame.snapshot();
        if (key.equals(cachedKey) && cachedCloseWindow == frame.closeWindow()
                && cachedWorld == snapshot.world() && cachedScene == snapshot.scene()
                && cachedAirProfile.equals(snapshot.airProfile())) {
            return;
        }
        upload(mesh);
        cachedKey = key;
        cachedWorld = snapshot.world();
        cachedScene = snapshot.scene();
        cachedAirProfile = snapshot.airProfile();
        cachedCloseWindow = frame.closeWindow();
    }

    private void upload(CloudMesh mesh) {
        int count = mesh.vertexCount();
        long bytes = (long) count * FLOAT_STRIDE_BYTES;
        if (bytes < 0L || bytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("SS cloud VBO exceeds the GL upload size contract");
        }
        if (count == 0) {
            deleteVertexBuffer();
            vertexCount = 0;
            return;
        }
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int candidate = GL15.glGenBuffers();
        if (candidate == 0) throw new IllegalStateException("Unable to allocate SS cloud VBO");
        boolean uploaded = false;
        try {
            FloatBuffer source = mesh.vertexBuffer();
            FloatBuffer direct = BufferUtils.createFloatBuffer(source.remaining());
            direct.put(source).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, candidate);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, direct, GL15.GL_STATIC_DRAW);
            if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE) != (int) bytes) {
                throw new IllegalStateException("SS cloud VBO upload size did not match its CPU mesh");
            }
            uploaded = true;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            if (!uploaded) GL15.glDeleteBuffers(candidate);
        }
        deleteVertexBuffer();
        vertexBuffer = candidate;
        vertexCount = count;
    }

    private void draw(SSCloudFrame frame, RingworldSunshade.CameraRelativeBands bands, double weather) {
        RingworldDisplaySnapshot snapshot = frame.snapshot();
        CloudMotionFrame motion = frame.meshMotion();
        if (!OpenGlHelper.shadersSupported) {
            throw new IllegalStateException("SS cloud renderer requires OpenGL shader support");
        }
        int previousProgram = OpenGlHelper.openGL21 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                : ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousClientActiveTexture = GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        if (vertexArray == 0) {
            vertexArray = GL30.glGenVertexArrays();
            if (vertexArray == 0) throw new IllegalStateException("Cannot allocate own-cloud VAO");
        }
        RingworldColorMaskScope colorMasks = RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_FOG_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL30.glBindVertexArray(vertexArray);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_BLEND);
            if (frame.cullFine()) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

            program.use();
            program.uploadCurvature(RingworldRenderSnapshots.currentDistantCurvatureFrameFor(snapshot.world(), snapshot.scene()));
            program.setUniforms(motion.meshOffsetX(), frame.baseY() - snapshot.observer().y(), motion.meshOffsetZ(),
                    RingworldStripBounds.BOARD_MIN_Z - snapshot.observer().z(),
                    RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE - snapshot.observer().z(),
                    snapshot.sunshadeHeightBlocks() - snapshot.observer().y(), snapshot.sunshadeThicknessBlocks(),
                    bands, snapshot.sunshade().sideFeatherBlocks(), snapshot.sunshade().featherBlocks(), weather);
            program.setLodUniforms(!frame.closeWindow(), frame.transition(), frame.bottomBrightness());

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, FLOAT_STRIDE_BYTES, 0L);
            GL11.glColorPointer(3, GL11.GL_FLOAT, FLOAT_STRIDE_BYTES, 3L * Float.BYTES);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.CLOUD_NEAR)) {
                GL11.glDrawArrays(GL11.GL_QUADS, 0, vertexCount);
            }
        } finally {
            OpenGlHelper.glUseProgram(previousProgram);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL13.glActiveTexture(previousActiveTexture);
            GL13.glClientActiveTexture(previousClientActiveTexture);
            GL11.glPopClientAttrib();
            // Actinium's client stack restores flags, not pointers; the VAO owns those.
            GL30.glBindVertexArray(previousVertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL11.glPopAttrib();
            colorMasks.close();
        }
    }

    private void clearCachedResources(boolean dropMask) {
        ProceduralRingModelRenderer.invalidate();
        traceCachePolicy.clear();
        program.dispose();
        horizonProgram.dispose();
        deleteVertexBuffer();
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray);
        vertexArray = 0;
        vertexCount = 0;
        cachedKey = null;
        cachedWorld = null;
        cachedScene = null;
        cachedAirProfile = null;
        cachedCloseWindow = false;
        currentFrame = null;
        cacheSettings = null;
        requestedCache = null;
        cache = null;
        prepared = null;
        cacheScopeWorld = null;
        cacheScopeScene = null;
        if (cacheStreamer != null) cacheStreamer.invalidate();
        reportedRequestedHorizon = -1;
        reportedEffectiveHorizon = Double.NaN;
        reportedTailFallback = false;
    }

    private void deleteVertexBuffer() {
        if (vertexBuffer != 0) GL15.glDeleteBuffers(vertexBuffer);
        vertexBuffer = 0;
    }

    private void createStreamer() {
        cacheExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "StellarSky-cloud-cache"); thread.setDaemon(true); return thread;
        });
        cacheStreamer = new CloudWindowStreamer(cacheExecutor);
    }

    private void releaseStreamer() {
        if (cacheStreamer != null) cacheStreamer.close();
        if (cacheExecutor != null) cacheExecutor.shutdownNow();
        cacheStreamer = null;
        cacheExecutor = null;
    }
}
