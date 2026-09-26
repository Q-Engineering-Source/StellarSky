package stellarium.client.ring;

import java.util.Objects;

import stellarium.client.ring.cloud.CloudClipBounds;
import stellarium.client.ring.cloud.CloudFieldSettings;
import stellarium.client.ring.cloud.CloudGeometrySettings;
import stellarium.client.ring.cloud.CloudLodTransition;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudTailCoverage;
import stellarium.client.ring.cloud.CloudMask;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarium.client.ring.cloud.CloudMeshCacheKey;
import stellarium.client.ring.cloud.CloudMotionFrame;
import stellarium.client.ring.cloud.CloudRayBudget;
import stellarium.world.ring.RingworldDisplaySnapshot;

/** Immutable cloud inputs shared by early rasterization and the same world-last air frame. */
final class SSCloudFrame {
    private final RingworldDisplaySnapshot snapshot;
    private final CloudFieldSettings fieldSettings;
    private final CloudWorldCache cache;
    private final CloudGeometrySettings geometry;
    private final CloudClipBounds clipBounds;
    private final CloudMotionFrame meshMotion;
    private final CloudMeshCacheKey meshKey;
    private final CloudRayBudget.PreparedHorizon horizon;
    private final boolean closeWindow;
    private final double baseY;
    private final boolean cullFine;
    private final boolean cullMid;
    private final boolean cullLow;
    private final boolean cullVeryLow;
    private final CloudLodTransition transition;
    private final double bottomBrightness;
    private final CloudCameraFrame camera;
    private final CloudTailCoverage.Coverage tailCoverage;

    SSCloudFrame(RingworldDisplaySnapshot snapshot, CloudFieldSettings fieldSettings, CloudWorldCache cache,
                 CloudGeometrySettings geometry, CloudClipBounds clipBounds, CloudMotionFrame meshMotion,
                 CloudMeshCacheKey meshKey, CloudRayBudget.PreparedHorizon horizon, boolean closeWindow,
                 double baseY,
                 boolean cullFine, boolean cullMid, boolean cullLow, boolean cullVeryLow, CloudCameraFrame camera,
                 CloudLodTransition transition, double bottomBrightness) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.fieldSettings = Objects.requireNonNull(fieldSettings, "fieldSettings");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.clipBounds = Objects.requireNonNull(clipBounds, "clipBounds");
        this.meshMotion = Objects.requireNonNull(meshMotion, "meshMotion");
        this.meshKey = Objects.requireNonNull(meshKey, "meshKey");
        this.horizon = Objects.requireNonNull(horizon, "horizon");
        if (!Double.isFinite(baseY)) {
            throw new IllegalArgumentException("Invalid frozen SS cloud frame");
        }
        this.closeWindow = closeWindow;
        this.baseY = baseY;
        this.cullFine = cullFine;
        this.cullMid = cullMid;
        this.cullLow = cullLow;
        this.cullVeryLow = cullVeryLow;
        this.camera = Objects.requireNonNull(camera, "camera");
        this.transition = Objects.requireNonNull(transition, "transition");
        if (!Double.isFinite(bottomBrightness) || bottomBrightness < 0.0 || bottomBrightness > 1.0) {
            throw new IllegalArgumentException("Cloud bottom brightness must be in [0, 1]");
        }
        this.bottomBrightness = bottomBrightness;
        double lower = Math.max(baseY, clipBounds.lowerY());
        double upper = Math.min(baseY + geometry.voxelHeightBlocks() * CloudLodLayout.LOD0_FINE_3D.layers(),
                clipBounds.upperY());
        double cellWidth = geometry.cellSizeBlocks() * CloudLodLayout.LOD12_2D_TAIL.xzScale();
        double minimumX = pageOriginX(CloudLodLayout.LOD12_2D_TAIL) - camera.cameraX();
        double maximumX = minimumX + cellWidth * CloudLodLayout.LOD12_2D_TAIL.width();
        this.tailCoverage = CloudTailCoverage.prepare(lower, upper, snapshot.observer().y() + camera.cameraY(),
                camera.pixelAngularSize(), minimumX, maximumX, cellWidth, fieldSettings.coverage());
    }

    RingworldDisplaySnapshot snapshot() { return snapshot; }
    CloudFieldSettings fieldSettings() { return fieldSettings; }
    CloudMask mask() { return cache.fineMask(); }
    CloudWorldCache cache() { return cache; }
    CloudGeometrySettings geometry() { return geometry; }
    CloudClipBounds clipBounds() { return clipBounds; }
    CloudMotionFrame meshMotion() { return meshMotion; }
    CloudMeshCacheKey meshKey() { return meshKey; }
    CloudRayBudget.PreparedHorizon horizon() { return horizon; }
    boolean closeWindow() { return closeWindow; }
    double baseY() { return baseY; }
    boolean cullFine() { return cullFine; }
    boolean cullMid() { return cullMid; }
    boolean cullLow() { return cullLow; }
    boolean cullVeryLow() { return cullVeryLow; }
    double pixelAngularSize() { return camera.pixelAngularSize(); }
    CloudLodTransition transition() { return transition; }
    double bottomBrightness() { return bottomBrightness; }
    CloudCameraFrame camera() { return camera; }
    CloudTailCoverage.Coverage tailCoverage() { return tailCoverage; }

    /** Retains the current authority/light snapshot while sharing one bounded wind pose across cloud consumers. */
    SSCloudFrame withWind(double wind) {
        CloudMotionFrame motion = new CloudMotionFrame(meshMotion.anchorCellX(), meshMotion.anchorCellZ(),
                meshMotion.anchorCellX() * geometry.cellSizeBlocks() + wind - snapshot.observer().x(),
                meshMotion.meshOffsetZ(), wind);
        return new SSCloudFrame(snapshot, fieldSettings, cache, geometry, clipBounds, motion, meshKey,
                horizon, closeWindow, baseY, cullFine, cullMid, cullLow, cullVeryLow, camera,
                transition, bottomBrightness);
    }

    double pageOriginX(CloudLodLayout.AtlasLevel level) {
        return cache.page(level).originX() * (geometry.cellSizeBlocks() * level.xzScale())
                + meshMotion.windOffsetBlocks() - snapshot.observer().x();
    }

    double pageOriginZ(CloudLodLayout.AtlasLevel level) {
        return cache.page(level).originZ() * (geometry.cellSizeBlocks() * level.xzScale())
                - snapshot.observer().z();
    }

    boolean matches(Object world, Object scene, RingworldDisplaySnapshot expectedSnapshot) {
        return snapshot == expectedSnapshot && snapshot.world() == world && snapshot.scene() == scene;
    }
}
