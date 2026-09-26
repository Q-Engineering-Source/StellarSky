package stellarium.client.ring;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.block.material.Material;
import net.minecraft.init.MobEffects;
import net.minecraft.world.World;
import stellarium.StellarSky;
import stellarium.client.ClientSettings;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldDisplayLightField;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.client.ring.dh.DistantHorizonsDepthBridge;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverage;

/** Client render-entry scope for one immutable ringworld display snapshot. */
public final class RingworldRenderSnapshots {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private RingworldRenderSnapshots() {
    }

    public static void withSnapshot(Runnable original) {
        // Kirino chooses its partial tick after PREPARE/PRE_UPDATE. Open a scope
        // now, but freeze only when the actual camera-transform argument is used.
        runInScope(new Scope(null, false), original);
    }

    /**
     * Capture fails fast before changing the scope or invoking the renderer.
     * A successful capture, including an empty one, always delegates and restores its caller.
     */
    public static void withSnapshot(Supplier<RingworldDisplaySnapshot> capture, Runnable original) {
        RingworldDisplaySnapshot captured = capture.get();
        runInScope(new Scope(captured, true), original);
    }

    private static void runInScope(Scope scope, Runnable original) {
        Scope prior = CURRENT.get();
        scope.depth = prior == null ? 0 : prior.depth + 1;
        try {
            CURRENT.set(scope);
            original.run();
        } finally {
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
            }
        }
    }

    public static RingworldDisplaySnapshot current() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.snapshot;
    }

    public static RingworldDisplaySnapshot currentFor(Object world, Object scene) {
        RingworldDisplaySnapshot snapshot = current();
        return snapshot != null && snapshot.world() == world && snapshot.scene() == scene ? snapshot : null;
    }

    /** Only frames explicitly admitted by the complete world-pass coordinator are exposed to adapters. */
    public static RingworldCurvatureFrame currentCurvatureFrameFor(Object world, Object scene) {
        Scope scope = CURRENT.get();
        return scope != null && scope.curvatureFrame != null && scope.curvatureFrame.belongsTo(world, scene)
                ? scope.curvatureFrame : null;
    }

    /** The far geometry frame is shared by DH and our own media; it does not enable near-mesh adapters. */
    public static RingworldCurvatureFrame currentDistantCurvatureFrame() {
        Scope scope = CURRENT.get();
        return scope == null || !scope.distantCurvatureEnabled ? null : scope.distantCurvatureFrame;
    }

    static RingworldCurvatureFrame currentOpticalFrame() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.distantCurvatureFrame;
    }

    /** Physical lighting may use the camera frame without admitting near-mesh curvature. */
    public static RingworldCurvatureFrame currentLightingFrameFor(Object world, Object scene) {
        var frame = currentOpticalFrame();
        return frame != null && frame.belongsTo(world, scene) ? frame : null;
    }

    /** Borrowed depth metadata belongs to the render scope, so nested views restore the outer depth. */
    public static DistantHorizonsDepthBridge.Snapshot currentDistantDepth() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.distantDepth;
    }

    public static int scopeDepth() {
        Scope scope = CURRENT.get();
        return scope == null ? -1 : scope.depth;
    }

    public static DistantHorizonsFrameCoverage.Snapshot currentDistantCoverage() {
        Scope scope = CURRENT.get();
        return scope == null || scope.distantCoverage == null
                || scope.distantCoverage.frame() != currentDistantCurvatureFrame() ? null : scope.distantCoverage;
    }

    public static void captureDistantCoverage(DistantHorizonsFrameCoverage.Snapshot coverage) {
        Scope scope = CURRENT.get();
        if (scope == null) return;
        if (coverage != null && coverage.frame() != currentDistantCurvatureFrame()) {
            throw new IllegalArgumentException("Distant coverage does not belong to this optical frame");
        }
        scope.distantCoverage = coverage;
    }

    public static RingworldOwnMediaOcclusion.Snapshot currentOwnMediaOcclusion() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.ownMediaOcclusion;
    }

    public static void captureOwnMediaOcclusion(RingworldOwnMediaOcclusion.Snapshot snapshot) {
        Scope scope = CURRENT.get();
        if (scope == null) return;
        if (snapshot != null && snapshot.frame() != currentDistantCurvatureFrame()) {
            throw new IllegalArgumentException("Own-media texture must match the current optical frame");
        }
        scope.ownMediaOcclusion = snapshot;
    }

    public static void captureDistantDepth(DistantHorizonsDepthBridge.Snapshot depth) {
        Scope scope = CURRENT.get();
        if (scope == null) return;
        if (depth != null && depth.frame() != currentDistantCurvatureFrame()) {
            throw new IllegalArgumentException("Distant depth does not belong to this optical frame");
        }
        scope.distantDepth = depth;
    }

    public static RingworldCurvatureFrame currentDistantCurvatureFrameFor(Object world, Object scene) {
        RingworldCurvatureFrame frame = currentDistantCurvatureFrame();
        return frame != null && frame.belongsTo(world, scene) ? frame : null;
    }

    static void publishDistantCurvatureFrame(RingworldCurvatureFrame frame) {
        captureOpticalFrame(frame);
        CURRENT.get().distantCurvatureEnabled = true;
    }

    static void captureOpticalFrame(RingworldCurvatureFrame frame) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.snapshot == null
                || !frame.belongsTo(scope.snapshot.world(), scope.snapshot.scene())
                || !frame.renderOrigin().equals(scope.snapshot.observer())) {
            throw new IllegalStateException("Distant geometry frame must match the frozen world and observer");
        }
        scope.distantCurvatureFrame = frame;
        scope.distantCurvatureEnabled = false;
        scope.distantDepth = null;
        scope.distantCoverage = null;
        scope.ownMediaOcclusion = null;
        scope.deferredPreview = null;
    }

    /**
     * One pending late-stage payload for this exact optical pass.
     *
     * <p>The early own-media pass produces the preview ground's distance keys
     * before Distant Horizons has submitted anything, so the payload that settles
     * the visible preview can only be consumed after this frame's coverage record
     * is published. Every pass boundary clears it, so a second eye never inherits
     * the first eye's payload and a skipped early pass can never settle stale
     * geometry.</p>
     */
    public static Object currentDeferredPreview() {
        Scope scope = CURRENT.get();
        return scope == null || !scope.distantCurvatureEnabled ? null : scope.deferredPreview;
    }

    static void captureDeferredPreview(Object deferred) {
        Scope scope = CURRENT.get();
        if (scope == null) return;
        if (deferred != null && !scope.distantCurvatureEnabled) {
            throw new IllegalStateException("Deferred preview requires this pass's published distant curvature frame");
        }
        scope.deferredPreview = deferred;
    }

    /** Called once per optical pass, after every depth-writing adapter has passed its capability checks. */
    static void publishCurvatureFrame(RingworldCurvatureFrame frame) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.snapshot == null
                || !frame.belongsTo(scope.snapshot.world(), scope.snapshot.scene())
                || !frame.renderOrigin().equals(scope.snapshot.observer())) {
            throw new IllegalStateException("Curvature frame must belong to the current snapshot and render origin");
        }
        scope.curvatureFrame = frame;
    }

    /** Each stereo/viewport pass must be admitted independently; never reuse a prior optical camera. */
    public static void clearCurvatureFrame() {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.curvatureFrame = null;
            scope.distantCurvatureFrame = null;
            scope.distantCurvatureEnabled = false;
            scope.distantDepth = null;
            scope.distantCoverage = null;
            scope.ownMediaOcclusion = null;
            scope.deferredPreview = null;
        }
    }

    /**
     * Returns the one field frozen with this render only for its exact scene identity.
     * A nested, empty, or mismatched scope must not leak its caller's display field.
     */
    public static RingworldDisplayLightField currentDisplayLightFieldFor(Object world, Object scene) {
        Scope scope = CURRENT.get();
        return scope != null && scope.snapshot != null && scope.snapshot.world() == world && scope.snapshot.scene() == scene
                ? scope.displayLightField : null;
    }

    /**
     * Hot-path lookup for a scope whose scene identity was already frozen during capture.
     * Client rendering and scheduled scene replacement share one thread, so no mutable
     * capability lookup is needed for each receiver light query.
     */
    public static RingworldDisplayLightField currentDisplayLightFieldFor(Object world) {
        Scope scope = CURRENT.get();
        return scope != null && scope.snapshot != null && scope.snapshot.world() == world
                ? scope.displayLightField : null;
    }

    /**
     * Freezes client optical mode once for the render scope.  Callers pass
     * primitive settings snapshots so this class never keeps a mutable
     * ClientSettings object across passes or stereo eyes.
     */
    public static void captureFrameOptics(boolean renderAtmosphere, boolean lowPower) {
		captureFrameOptics(renderAtmosphere, lowPower, true);
	}

	/** Freezes the renderer decision together with the local-media gate. */
    public static void captureFrameOptics(boolean renderAtmosphere, boolean lowPower, boolean normalAirMedium) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.clientStateInitialized) return;
        scope.frameOptics = RingworldSpatialAirFrameOptics.freeze(scope.snapshot, renderAtmosphere, lowPower,
				normalAirMedium);
        scope.clientStateInitialized = true;
    }

	/**
	 * Production entry used before any sky branch consumes its optical state.
	 * It is intentionally scope-local and idempotent, so StellarRI can only
	 * reuse the decision rather than read mutable settings on a later pass.
	 */
	public static void captureCurrentFrameOptics() {
		Scope scope = CURRENT.get();
		if (scope == null || scope.clientStateInitialized) return;
		Minecraft minecraft = Minecraft.getMinecraft();
		ClientSettings settings = StellarSky.PROXY.getClientSettings();
		Entity viewer = minecraft.getRenderViewEntity();
		boolean normalAirMedium = viewer != null && !viewer.isInsideOfMaterial(Material.WATER)
				&& !viewer.isInsideOfMaterial(Material.LAVA)
				&& (!(viewer instanceof EntityLivingBase living) || !living.isPotionActive(MobEffects.BLINDNESS));
		captureFrameOptics(settings.renderAtmosphere, settings.lowPowerRenderer, normalAirMedium);
	}

    public static RingworldSpatialAirFrameOptics currentFrameOpticsFor(Object world, Object scene) {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.clientStateInitialized || scope.snapshot == null
                || scope.snapshot.world() != world || scope.snapshot.scene() != scene) return null;
        return scope.frameOptics;
    }

    /** Distinguishes an active but unusable render scope from ordinary non-render callers. */
    public static boolean isScopeActive() {
        return CURRENT.get() != null;
    }

    /** Freezes one result, including an absent snapshot, across all passes in this render. */
    public static void captureOnce(Supplier<RingworldDisplaySnapshot> capture) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.initialized) return;
        RingworldDisplaySnapshot snapshot = capture.get();
        scope.setSnapshot(snapshot);
        scope.initialized = true;
    }

    public static void captureCurrentWorldOnce(float partialTicks) {
        captureOnce(() -> captureCurrentWorld(partialTicks));
    }

    private static RingworldDisplaySnapshot captureCurrentWorld(float partialTicks) {
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.world;
        StellarScene scene = world == null ? null : StellarScene.getScene(world);
        if (scene == null || scene.getSettings().getRingworldSettings().sunshade() == null) return null;
        Entity observer = minecraft.getRenderViewEntity();
        if (observer == null || observer.world != world) return null;
        return scene.getRingworldDisplaySnapshot(RingworldRenderObserver.interpolate(
                observer.lastTickPosX, observer.lastTickPosY, observer.lastTickPosZ,
                observer.posX, observer.posY, observer.posZ, partialTicks));
    }

    private static final class Scope {
        private RingworldDisplaySnapshot snapshot;
        private RingworldDisplayLightField displayLightField;
        private RingworldSpatialAirFrameOptics frameOptics;
        private RingworldCurvatureFrame curvatureFrame;
        private RingworldCurvatureFrame distantCurvatureFrame;
        private boolean distantCurvatureEnabled;
        private DistantHorizonsDepthBridge.Snapshot distantDepth;
        private DistantHorizonsFrameCoverage.Snapshot distantCoverage;
        private RingworldOwnMediaOcclusion.Snapshot ownMediaOcclusion;
        private Object deferredPreview;
        private int depth;
        private boolean initialized;
        private boolean clientStateInitialized;

        private Scope(RingworldDisplaySnapshot snapshot, boolean initialized) {
            setSnapshot(snapshot);
            this.initialized = initialized;
        }

        private void setSnapshot(RingworldDisplaySnapshot snapshot) {
            this.snapshot = snapshot;
            this.displayLightField = snapshot == null ? null : new RingworldDisplayLightField(snapshot);
            this.frameOptics = null;
            this.curvatureFrame = null;
            this.distantCurvatureFrame = null;
            this.distantCurvatureEnabled = false;
            this.distantDepth = null;
            this.distantCoverage = null;
            this.ownMediaOcclusion = null;
            this.deferredPreview = null;
            this.clientStateInitialized = false;
        }
    }
}
