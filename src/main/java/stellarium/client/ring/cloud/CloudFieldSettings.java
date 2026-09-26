package stellarium.client.ring.cloud;

/** Immutable controls for the deterministic first-release procedural cloud field. */
public record CloudFieldSettings(long seed, double coverage, boolean worleyEnabled, double erosion, int layers) {
    public static final CloudFieldSettings DEFAULT = new CloudFieldSettings(0L, 0.45D, false, 0.20D);

    public CloudFieldSettings(long seed, double coverage, boolean worleyEnabled, double erosion) {
        this(seed, coverage, worleyEnabled, erosion, 8);
    }

    public CloudFieldSettings {
        requireLayers(layers);
        if (!Double.isFinite(coverage) || coverage < 0.0D || coverage > 1.0D
                || !Double.isFinite(erosion) || erosion < 0.0D || erosion > 1.0D) {
            throw new IllegalArgumentException("Cloud coverage and erosion must be finite values within [0, 1]");
        }
    }

    public static int requireLayers(int layers) {
        if (layers != 2 && layers != 4 && layers != 8) {
            throw new IllegalArgumentException("SS_Cloud_Layers must be 2, 4, or 8");
        }
        return layers;
    }
}
