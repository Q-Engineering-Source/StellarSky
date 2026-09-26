package stellarium.client.ring;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Board-specific facade for the private mesh-distance reduction targets.
 *
 * <p>The facade keeps the board selection associated with its world-render
 * scope and curvature frame. The reduction implementation itself is reusable
 * by other ringworld meshes with separate texture units and target pools.</p>
 */
public final class RingworldBoardMeshDistance {
    public static final int HIGH = RingworldMeshDistance.HIGH;
    public static final int LOW = RingworldMeshDistance.LOW;
    public static final int COLOR = RingworldMeshDistance.COLOR;

    private static final RingworldMeshDistance DISTANCE = new RingworldMeshDistance(5, 6);
    private static final Map<Integer, Selection> SELECTIONS = new HashMap<>();

    static void clearSelection() { SELECTIONS.remove(RingworldRenderSnapshots.scopeDepth()); }

    static Selection current() {
        Selection selected = SELECTIONS.get(RingworldRenderSnapshots.scopeDepth());
        return selected != null && selected.frame() == RingworldRenderSnapshots.currentDistantCurvatureFrame()
                ? selected : null;
    }

    static SelectedBinding bindSelected() {
        Selection selection = current();
        return new SelectedBinding(selection == null ? null
                : DISTANCE.bind(new RingworldMeshDistance.Selection(selection.high(), selection.low(),
                        selection.width(), selection.height())));
    }

    record Selection(RingworldCurvatureFrame frame, int high, int low, int width, int height) { }

    static final class SelectedBinding implements AutoCloseable {
        private final RingworldMeshDistance.Binding bindings;
        private SelectedBinding(RingworldMeshDistance.Binding bindings) { this.bindings = bindings; }
        @Override public void close() { if (bindings != null) bindings.close(); }
    }

    private RingworldBoardMeshDistance() {
    }

    /**
     * Executes high-key, low-key, and final colour passes. The callback is
     * invoked with a zero pixel offset for the private full-viewport targets
     * and the caller's original pixel origin for the final pass.
     */
    public static void render(int viewportX, int viewportY, int width, int height, Pass draw) {
        if (viewportX < 0 || viewportY < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid board mesh viewport " + viewportX + ',' + viewportY
                    + ' ' + width + 'x' + height);
        }
        Objects.requireNonNull(draw, "draw");
        int scopeDepth = RingworldRenderSnapshots.scopeDepth();
        RingworldMeshDistance.Selection selection = DISTANCE.render(viewportX, viewportY, width, height,
                draw::draw);
        SELECTIONS.put(scopeDepth, new Selection(RingworldRenderSnapshots.currentDistantCurvatureFrame(),
                selection.high(), selection.low(), selection.width(), selection.height()));
    }

    /** Deletes only reduction targets allocated by this helper. */
    public static void dispose() {
        SELECTIONS.clear();
        DISTANCE.dispose();
    }

    /** Shader/program adapter. Stages are {@link #HIGH}, {@link #LOW}, and {@link #COLOR}. */
    @FunctionalInterface
    public interface Pass {
        void draw(int stage, int highTexture, int lowTexture, int pixelOffsetX, int pixelOffsetY);
    }
}
