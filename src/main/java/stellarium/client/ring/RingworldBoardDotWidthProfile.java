package stellarium.client.ring;

/**
 * Pure symmetric brightness profile across the finite Z width of the board.
 * It is evaluated once per dot center by board.frag, never as a per-fragment
 * gradient inside a maintenance light.
 */
final class RingworldBoardDotWidthProfile {
    private RingworldBoardDotWidthProfile() {
    }

    static Profile of(boolean enabled, float edgeBandPercent, float edgeBrightnessPercent,
                      float centerBrightnessPercent, float edgeTransitionPercent) {
        requirePercent("edgeBandPercent", edgeBandPercent, 50.0f);
        requirePercent("edgeBrightnessPercent", edgeBrightnessPercent, 100.0f);
        requirePercent("centerBrightnessPercent", centerBrightnessPercent, 100.0f);
        requirePercent("edgeTransitionPercent", edgeTransitionPercent, 50.0f);
        return new Profile(enabled, edgeBandPercent / 100.0f, edgeBrightnessPercent / 100.0f,
                centerBrightnessPercent / 100.0f, edgeTransitionPercent / 100.0f);
    }

    static float brightnessMultiplier(Profile profile, double dotCenterZBlocks,
                                      double minZBlocks, double maxZBlocks) {
        if (profile == null) {
            throw new NullPointerException("profile");
        }
        if (!Double.isFinite(dotCenterZBlocks) || !Double.isFinite(minZBlocks) || !Double.isFinite(maxZBlocks)
                || !(maxZBlocks > minZBlocks)) {
            throw new IllegalArgumentException("board profile coordinates must be finite with positive width");
        }
        if (!profile.enabled()) {
            return 1.0f;
        }
        // Zero is a deliberate "no bright edge band" setting, including an
        // exact wall coordinate. It must not retain a one-fragment edge glow.
        if (profile.edgeBandFraction() == 0.0f) {
            return profile.centerBrightnessMultiplier();
        }
        double width = maxZBlocks - minZBlocks;
        double edgeBand = width * profile.edgeBandFraction();
        double availableHalfWidth = Math.max(0.0, width * 0.5 - edgeBand);
        double transition = Math.min(width * profile.edgeTransitionFraction(), availableHalfWidth);
        double nearestEdge = Math.min(Math.max(0.0, dotCenterZBlocks - minZBlocks),
                Math.max(0.0, maxZBlocks - dotCenterZBlocks));
        if (nearestEdge <= edgeBand || transition == 0.0) {
            return nearestEdge <= edgeBand ? profile.edgeBrightnessMultiplier()
                    : profile.centerBrightnessMultiplier();
        }
        if (nearestEdge >= edgeBand + transition) {
            return profile.centerBrightnessMultiplier();
        }
        double progress = smoothstep((nearestEdge - edgeBand) / transition);
        return (float) (profile.edgeBrightnessMultiplier()
                + (profile.centerBrightnessMultiplier() - profile.edgeBrightnessMultiplier()) * progress);
    }

    private static double smoothstep(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private static void requirePercent(String name, float value, float maximum) {
        if (!Float.isFinite(value) || value < 0.0f || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite in [0, " + maximum + "]");
        }
    }

    record Profile(boolean enabled, float edgeBandFraction, float edgeBrightnessMultiplier,
                   float centerBrightnessMultiplier, float edgeTransitionFraction) {
        Profile {
            requireFraction("edgeBandFraction", edgeBandFraction, 0.5f);
            requireFraction("edgeBrightnessMultiplier", edgeBrightnessMultiplier, 1.0f);
            requireFraction("centerBrightnessMultiplier", centerBrightnessMultiplier, 1.0f);
            requireFraction("edgeTransitionFraction", edgeTransitionFraction, 0.5f);
        }
    }

    private static void requireFraction(String name, float value, float maximum) {
        if (!Float.isFinite(value) || value < 0.0f || value > maximum) {
            throw new IllegalArgumentException(name + " is out of range");
        }
    }
}
