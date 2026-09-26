package stellarium.client.ring;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import stellarium.StellarSky;
import stellarium.client.ring.cloud.CloudCurvaturePolicy;
import stellarium.client.ring.cloud.CloudLocalBounds;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import stellarium.client.ring.cloud.CloudLodGeometrySet;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudLodPatchMeshBuilder;
import stellarium.client.ring.cloud.CloudLodVisibility;
import stellarium.client.ring.cloud.CloudModelHandoff;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarium.render.util.SamplerBindings;

/**
 * Rasterizes immutable cloud cache pages as compact instanced quads.
 *
 * <p>The renderer owns the local/far cloud distance election on texture units 9/10.  The supplied
 * far callback is deliberately drawn first in every election pass, so the cache and the global
 * field select one physical cloud candidate rather than compositing two independent opaque layers.</p>
 */
final class CloudLodRasterRenderer {
    private static final int ATLAS_UNIT = 2;
    private static final int GROUND_HIGH_UNIT = 7;
    private static final int GROUND_LOW_UNIT = 8;
    private static final int HIGH_UNIT = 9;
    private static final int LOW_UNIT = 10;
    private static final int INSTANCE_STRIDE = CloudLodPatchMeshBuilder.FLOATS_PER_QUAD * Float.BYTES;

    private final CloudLodRasterProgram program = new CloudLodRasterProgram();
    private final CloudDebugProgram debugProgram = new CloudDebugProgram();
    private final RingworldMeshDistance selector = new RingworldMeshDistance(HIGH_UNIT, LOW_UNIT);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);
    private final FloatBuffer matrices = BufferUtils.createFloatBuffer(16);
    private final Map<CloudLodPatchMeshBuilder.PatchMesh, GpuMesh> meshes = new IdentityHashMap<>();
    private int atlasTexture;
    private CloudWorldCache uploadedCache;
    private long uploads, atlasUploads, frames;
    private long eligibleQuads, submittedQuads;
    private record LocalDraw(CloudCurvaturePolicy.Segment slab, boolean[] visible) {}
    private final List<List<LocalDraw>> localDraws = new ArrayList<>();

    boolean render(SSCloudFrame frame, CloudLodGeometrySet geometry, RingworldCurvatureFrame optics, float rain,
                   RingworldMeshDistance.Selection groundSelection, RingworldMeshDistance.Pass farCloud) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(optics, "optics");
        Objects.requireNonNull(groundSelection, "groundSelection");
        Objects.requireNonNull(farCloud, "farCloud");
        if (geometry.cache() != frame.cache()) {
            throw new IllegalArgumentException("Cloud raster geometry and frame must share one immutable cache");
        }
        if (geometry.radius() != optics.geometry().radiusMeters()) {
            throw new IllegalArgumentException("Cloud raster geometry radius differs from the frozen optical frame");
        }
        if (geometry.byteCount() > CloudLodGeometrySet.MAX_BYTES) {
            throw new IllegalStateException("Cloud raster geometry exceeded its admission budget");
        }
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int x = viewport.get(0), y = viewport.get(1), width = viewport.get(2), height = viewport.get(3);
        if (width != groundSelection.width() || height != groundSelection.height()) {
            throw new IllegalStateException("Cloud raster and ground selection viewport dimensions differ");
        }
        upload(geometry);
        boolean[][] visible = cull(geometry, frame, optics);
        int priorProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int priorVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int priorVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        RingworldColorMaskScope masks = RingworldColorMaskScope.capture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_LIGHTING_BIT | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT
                | GL11.GL_LINE_BIT | GL11.GL_POINT_BIT);
        try (AtlasBinding ignoredAtlas = new AtlasBinding(atlasTexture);
             GroundBinding ignoredGround = new GroundBinding(groundSelection);
             RingworldDistantDepthUniforms.TextureBinding ignoredDh = RingworldDistantDepthUniforms.bindTexture()) {
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_ALWAYS, 0.0F);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            program.use(frame, optics, rain, groundSelection, x, y);
            try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.CLOUD_NEAR)) {
                selector.render(x, y, width, height, (stage, high, low, offsetX, offsetY) -> {
                    // These nested intervals are deliberately not additive with CLOUD_NEAR.
                    // The parent includes selector setup/clears; stage scopes start at its callback.
                    try (var stageTiming = RingworldGpuProfile.measure(profileStage(stage, 0))) {
                        // This must precede the page geometry in every high/low/colour pass.
                        try (var farTiming = RingworldGpuProfile.measure(profileStage(stage, 1))) {
                            farCloud.draw(stage, high, low, offsetX, offsetY);
                        }
                        // The far callback owns a different program. Rebind before local pages.
                        program.use(frame, optics, rain, groundSelection, x, y);
                        program.stage(stage, offsetX, offsetY, width, height);
                        try (var volumeTiming = RingworldGpuProfile.measure(profileStage(stage, 2))) {
                            draw(geometry, frame, visible, 0, 4);
                        }
                        try (var sheetTiming = RingworldGpuProfile.measure(profileStage(stage, 3))) {
                            draw(geometry, frame, visible, 4, CloudLodLayout.ATLAS_LEVELS.size());
                        }
                    }
                    if (stage == RingworldMeshDistance.COLOR) {
                        drawDebug(geometry, frame, optics, visible, x, y, width, height);
                    }
                });
            }
        } finally {
            OpenGlHelper.glUseProgram(priorProgram);
            GL30.glBindVertexArray(priorVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, priorVbo);
            GL11.glPopAttrib();
            masks.close();
        }
        frames++;
        return true;
    }

    String stats() {
        return "cloudRaster uploads=" + uploads + ", atlasUploads=" + atlasUploads + ", frames=" + frames
                + ", residentPages=" + meshes.size() + ", submittedQuads=" + submittedQuads + '/' + eligibleQuads;
    }

    String status() {
        return "ready=" + (uploadedCache != null) + ", uploads=" + uploads + ", atlasUploads=" + atlasUploads
                + ", frames=" + frames + ", residentPages=" + meshes.size()
                + ", submittedQuads=" + submittedQuads + '/' + eligibleQuads;
    }

    void dispose() {
        for (GpuMesh mesh : meshes.values()) mesh.dispose();
        meshes.clear();
        if (atlasTexture != 0) GL11.glDeleteTextures(atlasTexture);
        atlasTexture = 0;
        uploadedCache = null;
        selector.dispose();
        program.dispose();
        debugProgram.dispose();
        CloudDebugSettings.publishStats(0, 0, 0);
    }

    private static RingworldGpuProfile.Stage profileStage(int stage, int component) {
        return switch (component) {
            case 0 -> switch (stage) {
                case RingworldMeshDistance.HIGH -> RingworldGpuProfile.Stage.CLOUD_HIGH;
                case RingworldMeshDistance.LOW -> RingworldGpuProfile.Stage.CLOUD_LOW;
                case RingworldMeshDistance.COLOR -> RingworldGpuProfile.Stage.CLOUD_COLOR;
                default -> throw new IllegalArgumentException("Invalid cloud timing stage " + stage);
            };
            case 1 -> switch (stage) {
                case RingworldMeshDistance.HIGH -> RingworldGpuProfile.Stage.CLOUD_HIGH_MODEL;
                case RingworldMeshDistance.LOW -> RingworldGpuProfile.Stage.CLOUD_LOW_MODEL;
                case RingworldMeshDistance.COLOR -> RingworldGpuProfile.Stage.CLOUD_COLOR_MODEL;
                default -> throw new IllegalArgumentException("Invalid cloud timing stage " + stage);
            };
            case 2 -> switch (stage) {
                case RingworldMeshDistance.HIGH -> RingworldGpuProfile.Stage.CLOUD_HIGH_VOLUME;
                case RingworldMeshDistance.LOW -> RingworldGpuProfile.Stage.CLOUD_LOW_VOLUME;
                case RingworldMeshDistance.COLOR -> RingworldGpuProfile.Stage.CLOUD_COLOR_VOLUME;
                default -> throw new IllegalArgumentException("Invalid cloud timing stage " + stage);
            };
            case 3 -> switch (stage) {
                case RingworldMeshDistance.HIGH -> RingworldGpuProfile.Stage.CLOUD_HIGH_SHEET;
                case RingworldMeshDistance.LOW -> RingworldGpuProfile.Stage.CLOUD_LOW_SHEET;
                case RingworldMeshDistance.COLOR -> RingworldGpuProfile.Stage.CLOUD_COLOR_SHEET;
                default -> throw new IllegalArgumentException("Invalid cloud timing stage " + stage);
            };
            default -> throw new IllegalArgumentException("Invalid cloud timing component " + component);
        };
    }

    private void draw(CloudLodGeometrySet geometry, SSCloudFrame frame, boolean[][] visible, int firstLevel, int endLevel) {
        double cellSize = frame.geometry().cellSizeBlocks();
        double thickness = frame.geometry().thicknessBlocks();
        double farStart = CloudModelHandoff.farStart(cellSize);
        double farEnd = CloudModelHandoff.farEnd(cellSize);
        for (int level = firstLevel; level < endLevel; level++) {
            CloudWorldCache.Page page = geometry.cache().page(CloudLodLayout.ATLAS_LEVELS.get(level));
            CloudLodPatchMeshBuilder.PatchMesh mesh = geometry.mesh(level);
            GpuMesh gpu = meshes.get(mesh);
            if (gpu == null) throw new IllegalStateException("Cloud page mesh was not atomically uploaded");
            boolean pageVisible = false;
            for (boolean batchVisible : visible[level]) pageVisible |= batchVisible;
            // Keep cache admission checks above, but skip page uniforms and VAO binds when
            // the already-frozen conservative culling result contains no drawable batch.
            if (!pageVisible) continue;
            CloudLodVisibility.DistanceBand band = CloudLodVisibility.bandForLevel(level, cellSize);
            program.pageDrawParameters(page, cellSize, thickness, frame.baseY(), band.minimumDistance(), band.maximumDistanceExclusive(),
                    farStart, farEnd);
            GL30.glBindVertexArray(gpu.vao);
            if (mesh.physicalCoordinates()) {
                for (LocalDraw local : localDraws.get(level)) {
                    program.localSlab(local.slab());
                    for (int batchIndex = 0; batchIndex < gpu.batchCount; batchIndex++) {
                        if (!local.visible()[batchIndex]) continue;
                        var batch = mesh.batch(batchIndex);
                        bindBatch(gpu.vbo, batch.firstQuad());
                        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, batch.quadCount());
                    }
                }
                continue;
            }
            program.localSlab(null);
            for (int batchIndex = 0; batchIndex < gpu.batchCount; batchIndex++) {
                if (!visible[level][batchIndex]) continue;
                CloudLodPatchMeshBuilder.Batch batch = mesh.batch(batchIndex);
                bindBatch(gpu.vbo, batch.firstQuad());
                GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, batch.quadCount());
            }
        }
    }

    private void drawDebug(CloudLodGeometrySet geometry, SSCloudFrame frame, RingworldCurvatureFrame optics,
                           boolean[][] visible, int x, int y, int width, int height) {
        CloudDebugSettings.Mode mode = CloudDebugSettings.mode();
        if (mode == CloudDebugSettings.Mode.OFF) return;
        int pages = 0, batches = 0;
        long quads = 0;
        // Diagnostics cannot replace the media distance attachment or write scene depth.
        try (var timing = RingworldGpuProfile.measure(RingworldGpuProfile.Stage.CLOUD_DEBUG);
             RingworldColorMaskScope ignored = RingworldColorMaskScope.capture()) {
            int drawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
            for (int output = 1; output < drawBuffers; output++) {
                GL30.glColorMaski(output, false, false, false, false);
            }
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glDisable(GL11.GL_POINT_SMOOTH);
            GL11.glDisable(GL32.GL_PROGRAM_POINT_SIZE);
            GL11.glPointSize(6.0F);
            GL11.glLineWidth(1.0F);
            for (int level = 0; level < 4; level++) {
                var mesh = geometry.mesh(level);
                GpuMesh gpu = meshes.get(mesh);
                var band = CloudLodVisibility.bandForLevel(level, frame.geometry().cellSizeBlocks());
                boolean pageDrawn = false;
                GL30.glBindVertexArray(gpu.vao);
                // Both modes reuse precisely the instance payload drawn by the filled cloud pass.
                for (int pass = 1; pass <= 2; pass++) {
                    if (pass == 1 ? !mode.drawsTriangles() : !mode.drawsVertices()) continue;
                    boolean points = pass == 2;
                    debugProgram.use(optics, frame.meshMotion().windOffsetBlocks(), points ? 1 : 2, x, y, width, height);
                    debugProgram.pageBand(band.minimumDistance(), band.maximumDistanceExclusive());
                    GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, points ? GL11.GL_FILL : GL11.GL_LINE);
                    if (mesh.physicalCoordinates()) {
                        for (LocalDraw local : localDraws.get(level)) {
                            debugProgram.localSlab(local.slab());
                            for (int batchIndex = 0; batchIndex < mesh.batchCount(); batchIndex++) {
                                if (!local.visible()[batchIndex]) continue;
                                var batch = mesh.batch(batchIndex);
                                bindBatch(gpu.vbo, batch.firstQuad());
                                GL31.glDrawArraysInstanced(points ? GL11.GL_POINTS : GL11.GL_TRIANGLE_STRIP, 0, 4, batch.quadCount());
                            }
                        }
                        continue;
                    }
                    debugProgram.localSlab(null);
                    for (int batchIndex = 0; batchIndex < mesh.batchCount(); batchIndex++) {
                        if (!visible[level][batchIndex]) continue;
                        var batch = mesh.batch(batchIndex);
                        bindBatch(gpu.vbo, batch.firstQuad());
                        GL31.glDrawArraysInstanced(points ? GL11.GL_POINTS : GL11.GL_TRIANGLE_STRIP, 0, 4, batch.quadCount());
                    }
                }
                for (int batchIndex = 0; batchIndex < mesh.batchCount(); batchIndex++) {
                    if (mesh.physicalCoordinates()) break;
                    if (!visible[level][batchIndex]) continue;
                    pageDrawn = true;
                    batches++;
                    quads += mesh.batch(batchIndex).quadCount();
                }
                if (mesh.physicalCoordinates()) {
                    for (LocalDraw local : localDraws.get(level)) for (int batch = 0; batch < mesh.batchCount(); batch++) {
                        if (!local.visible()[batch]) continue;
                        pageDrawn = true;
                        batches++;
                        quads += mesh.batch(batch).quadCount();
                    }
                }
                if (pageDrawn) pages++;
            }
        } finally {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glDepthMask(true);
        }
        CloudDebugSettings.publishStats(pages, batches, quads);
    }

    private void upload(CloudLodGeometrySet geometry) {
        Set<CloudLodPatchMeshBuilder.PatchMesh> active = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int level = 0; level < CloudLodLayout.ATLAS_LEVELS.size(); level++) active.add(geometry.mesh(level));
        Map<CloudLodPatchMeshBuilder.PatchMesh, GpuMesh> candidates = new IdentityHashMap<>();
        try {
            // Build every VBO first. The old map and atlas remain valid until all of the
            // current immutable package has crossed its GL admission boundary.
            for (CloudLodPatchMeshBuilder.PatchMesh patch : active) {
                if (!meshes.containsKey(patch)) candidates.put(patch, create(patch));
            }
            if (uploadedCache != geometry.cache()) uploadAtlas(geometry.cache());
        } catch (RuntimeException | Error failure) {
            for (GpuMesh candidate : candidates.values()) candidate.dispose();
            throw failure;
        }
        meshes.putAll(candidates);
        for (Iterator<Map.Entry<CloudLodPatchMeshBuilder.PatchMesh, GpuMesh>> iterator = meshes.entrySet().iterator();
             iterator.hasNext();) {
            Map.Entry<CloudLodPatchMeshBuilder.PatchMesh, GpuMesh> entry = iterator.next();
            if (!active.contains(entry.getKey())) {
                entry.getValue().dispose();
                iterator.remove();
            }
        }
    }

    private GpuMesh create(CloudLodPatchMeshBuilder.PatchMesh patch) {
        int priorArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int priorVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int buffer = 0, vao = 0;
        try {
            buffer = GL15.glGenBuffers();
            vao = GL30.glGenVertexArrays();
            if (buffer == 0 || vao == 0) throw new IllegalStateException("Cannot allocate cloud page mesh");
            FloatBuffer source = BufferUtils.createFloatBuffer(patch.floatCount());
            patch.writeTo(source);
            source.flip();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, source, GL15.GL_STATIC_DRAW);
            if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE) != patch.byteCount()) {
                throw new IllegalStateException("Cloud page mesh upload size differs from immutable source");
            }
            for (int corner = 0; corner < 4; corner++) {
                attribute(corner * 2, 3, corner * 6);
                attribute(corner * 2 + 1, 3, corner * 6 + 3);
            }
            attribute(8, 4, 24);
            attribute(9, 4, 28);
            attribute(10, 3, 32);
            attribute(11, 2, 35);
            for (int index = 0; index <= 11; index++) GL33.glVertexAttribDivisor(index, 1);
            GpuMesh result = new GpuMesh(buffer, vao, patch.batchCount());
            buffer = vao = 0;
            uploads++;
            return result;
        } finally {
            GL30.glBindVertexArray(priorVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, priorArray);
            if (buffer != 0) GL15.glDeleteBuffers(buffer);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
        }
    }

    private void uploadAtlas(CloudWorldCache cache) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int priorTexture;
        int priorSampler;
        int priorPbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] pixels = {GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS};
        int[] values = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) values[i] = GL11.glGetInteger(pixels[i]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_UNIT);
        priorTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        priorSampler = SamplerBindings.get(ATLAS_UNIT);
        // A replacement texture keeps the previously admitted cache intact if this upload fails.
        int candidate = GL11.glGenTextures();
        if (candidate == 0) throw new IllegalStateException("Cannot allocate authoritative cloud atlas");
        boolean published = false;
        int previousAtlas = atlasTexture;
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < pixels.length; i++) GL11.glPixelStorei(pixels[i], i == 0 ? 1 : 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, candidate);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            ByteBuffer source = cache.rgba8();
            ByteBuffer direct = BufferUtils.createByteBuffer(source.remaining());
            direct.put(source).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, CloudLodLayout.ATLAS_WIDTH,
                    CloudLodLayout.ATLAS_HEIGHT, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, direct);
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != CloudLodLayout.ATLAS_WIDTH
                    || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != CloudLodLayout.ATLAS_HEIGHT) {
                throw new IllegalStateException("Authoritative cloud atlas allocation dimensions differ");
            }
            atlasTexture = candidate;
            uploadedCache = cache;
            atlasUploads++;
            published = true;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, priorTexture);
            GL33.glBindSampler(ATLAS_UNIT, priorSampler);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, priorPbo);
            for (int i = 0; i < pixels.length; i++) GL11.glPixelStorei(pixels[i], values[i]);
            GL13.glActiveTexture(active);
            if (!published) GL11.glDeleteTextures(candidate);
            else if (previousAtlas != 0) GL11.glDeleteTextures(previousAtlas);
        }
    }

    private boolean[][] cull(CloudLodGeometrySet geometry, SSCloudFrame frame, RingworldCurvatureFrame optics) {
        float[] projection = new float[16], modelView = new float[16];
        optics.copyProjection(matrices); matrices.get(projection);
        optics.copyModelView(matrices); matrices.get(modelView);
        double angle = (optics.opticalEye().x() - frame.meshMotion().windOffsetBlocks()) / optics.geometry().radiusMeters();
        var visibility = CloudLodVisibility.prepare(Math.cos(angle), Math.sin(angle), optics.geometry().radiusMeters(),
                optics.cameraX(), -optics.renderOrigin().y(), -optics.renderOrigin().z(),
                optics.cameraX(), optics.cameraY(), optics.cameraZ(), projection, modelView);
        var localVisibility = CloudLodVisibility.prepare(1.0, 0.0, optics.geometry().radiusMeters(),
                0.0, 0.0, 0.0, optics.cameraX(), optics.cameraY(), optics.cameraZ(), projection, modelView);
        double tolerance = StellarSky.PROXY.getClientSettings().ownCloudCurvatureErrorMeters;
        double eyeX = optics.opticalEye().x() - frame.meshMotion().windOffsetBlocks();
        localDraws.clear();
        boolean[][] result = new boolean[CloudLodLayout.ATLAS_LEVELS.size()][];
        long eligible = 0, submitted = 0;
        for (int level = 0; level < result.length; level++) {
            var mesh = geometry.mesh(level);
            var band = CloudLodVisibility.bandForLevel(level, frame.geometry().cellSizeBlocks());
            boolean[] levelResult = new boolean[mesh.batchCount()];
            List<LocalDraw> localLevel = new ArrayList<>();
            localDraws.add(localLevel);
            if (mesh.physicalCoordinates()) {
                // One immutable slab/batch decision is shared by HIGH, LOW, COLOR and debug.
                for (var slab : CloudCurvaturePolicy.segments(geometry.radius(), tolerance,
                        band.maximumDistanceExclusive() * 1.01 + 1.0)) {
                    boolean[] slabVisible = new boolean[mesh.batchCount()];
                    boolean any = false;
                    for (int batch = 0; batch < slabVisible.length; batch++) {
                        var bounds = CloudLocalBounds.project(mesh.batch(batch).bounds(), slab, geometry.radius(),
                                eyeX, optics.cameraX(), optics.renderOrigin().y(), optics.renderOrigin().z());
                        slabVisible[batch] = bounds != null && localVisibility.visible(bounds,
                                band.minimumDistance(), band.maximumDistanceExclusive());
                        any |= slabVisible[batch];
                        levelResult[batch] |= slabVisible[batch];
                    }
                    if (any) localLevel.add(new LocalDraw(slab, slabVisible));
                }
            }
            for (int batch = 0; batch < levelResult.length; batch++) {
                var candidate = mesh.batch(batch);
                if (!mesh.physicalCoordinates()) levelResult[batch] = visibility.visible(candidate.bounds(), band.minimumDistance(), band.maximumDistanceExclusive());
                eligible += candidate.quadCount();
                if (levelResult[batch]) submitted += candidate.quadCount();
            }
            result[level] = levelResult;
        }
        eligibleQuads = eligible;
        submittedQuads = submitted;
        int slabCount = 0;
        long localSubmitted = 0;
        for (int level = 0; level < Math.min(4, localDraws.size()); level++) {
            for (LocalDraw draw : localDraws.get(level)) {
                slabCount++;
                for (int batch = 0; batch < draw.visible().length; batch++) {
                    if (draw.visible()[batch]) localSubmitted += geometry.mesh(level).batch(batch).quadCount();
                }
            }
        }
        CloudDebugSettings.publishCurvature(geometry.radius(), tolerance, slabCount, localSubmitted,
                geometry.mesh(0).physicalCoordinates());
        return result;
    }
    private static void attribute(int index, int size, int floatOffset) {
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, INSTANCE_STRIDE, (long) floatOffset * Float.BYTES);
        GL20.glEnableVertexAttribArray(index);
    }

    /** glDrawArraysInstanced has no base-instance in this compatibility path. */
    private static void bindBatch(int vbo, int firstQuad) {
        if (firstQuad < 0) throw new IllegalArgumentException("Cloud batch has negative instance origin");
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        long base = Math.multiplyExact((long) firstQuad, (long) INSTANCE_STRIDE);
        for (int corner = 0; corner < 4; corner++) {
            attributeAt(corner * 2, 3, base + (long) (corner * 6) * Float.BYTES);
            attributeAt(corner * 2 + 1, 3, base + (long) (corner * 6 + 3) * Float.BYTES);
        }
        attributeAt(8, 4, base + 24L * Float.BYTES);
        attributeAt(9, 4, base + 28L * Float.BYTES);
        attributeAt(10, 3, base + 32L * Float.BYTES);
        attributeAt(11, 2, base + 35L * Float.BYTES);
    }
    private static void attributeAt(int index, int size, long byteOffset) {
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, INSTANCE_STRIDE, byteOffset);
        GL20.glEnableVertexAttribArray(index);
    }

    private record GpuMesh(int vbo, int vao, int batchCount) {
        private void dispose() { GL15.glDeleteBuffers(vbo); GL30.glDeleteVertexArrays(vao); }
    }
    private static final class AtlasBinding implements AutoCloseable {
        private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int texture;
        private final int sampler;
        private AtlasBinding(int atlas) {
            if (atlas == 0) throw new IllegalStateException("Cloud raster atlas is unavailable");
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_UNIT);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            sampler = SamplerBindings.get(ATLAS_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL33.glBindSampler(ATLAS_UNIT, 0);
            GL13.glActiveTexture(active);
        }
        @Override public void close() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + ATLAS_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL33.glBindSampler(ATLAS_UNIT, sampler);
            GL13.glActiveTexture(active);
        }
    }

    private static final class GroundBinding implements AutoCloseable {
        private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int highTexture;
        private final int highSampler;
        private final int lowTexture;
        private final int lowSampler;
        private GroundBinding(RingworldMeshDistance.Selection selection) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_HIGH_UNIT);
            highTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            highSampler = SamplerBindings.get(GROUND_HIGH_UNIT);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_LOW_UNIT);
            lowTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            lowSampler = SamplerBindings.get(GROUND_LOW_UNIT);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_HIGH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, selection.high());
            GL33.glBindSampler(GROUND_HIGH_UNIT, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_LOW_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, selection.low());
            GL33.glBindSampler(GROUND_LOW_UNIT, 0);
            GL13.glActiveTexture(active);
        }
        @Override public void close() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_HIGH_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, highTexture);
            GL33.glBindSampler(GROUND_HIGH_UNIT, highSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_LOW_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lowTexture);
            GL33.glBindSampler(GROUND_LOW_UNIT, lowSampler);
            GL13.glActiveTexture(active);
        }
    }
}
