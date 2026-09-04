package stellarium.world.ring;

import java.util.Objects;
import javax.annotation.Nullable;

/** Immutable view input; clock endpoints/phase are absent together while authority is unavailable. */
public record RingworldDisplaySnapshot(Object world,
                                       Object scene,
                                       @Nullable RingworldClockMirror.DisplayTime displayTime,
                                       RingworldSunshade sunshade,
                                       @Nullable RingworldSunshade.Phase phase,
                                       int sunshadeHeightBlocks,
                                       int sunshadeThicknessBlocks,
                                       RingworldRenderObserver observer,
                                       double atmosphereFade,
                                       double atmosphereGeometryHeight) {
    public RingworldDisplaySnapshot {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(sunshade, "sunshade");
        if ((displayTime == null) != (phase == null)) {
            throw new IllegalArgumentException("Clock endpoints and phase must be present or absent together");
        }
        Objects.requireNonNull(observer, "observer");
        if (!Double.isFinite(atmosphereFade) || atmosphereFade < 0.0 || atmosphereFade > 1.0
                || !Double.isFinite(atmosphereGeometryHeight) || atmosphereGeometryHeight < 0.0) {
            throw new IllegalArgumentException("Invalid frozen ringworld atmosphere input");
        }
        // Capability of the current GLSL float board path, not a limit on the server's physical field.
        if (sunshadeHeightBlocks < 0 || sunshadeThicknessBlocks <= 0
                || (long) sunshadeHeightBlocks + sunshadeThicknessBlocks > Integer.MAX_VALUE
                || (!sunshade.isEmpty() && (long) sunshadeHeightBlocks + sunshadeThicknessBlocks > (1L << 24))) {
            throw new IllegalArgumentException("GLSL board rendering requires an upper face within 16777216 blocks");
        }
    }
}
