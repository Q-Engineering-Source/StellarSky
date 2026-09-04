package stellarium.client.ring;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldDisplayLightField;
import stellarium.world.ring.RingworldRenderObserver;

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
        private boolean initialized;

        private Scope(RingworldDisplaySnapshot snapshot, boolean initialized) {
            setSnapshot(snapshot);
            this.initialized = initialized;
        }

        private void setSnapshot(RingworldDisplaySnapshot snapshot) {
            this.snapshot = snapshot;
            this.displayLightField = snapshot == null ? null : new RingworldDisplayLightField(snapshot);
        }
    }
}
