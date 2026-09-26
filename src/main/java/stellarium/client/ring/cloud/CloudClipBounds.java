package stellarium.client.ring.cloud;

/** Immutable world-space finite air bounds used to close, rather than discard, cloud cells. */
public record CloudClipBounds(double lowerY, double upperY, double minWorldZ, double maxWorldZ) {
    public CloudClipBounds {
        requireFinite("lowerY", lowerY);
        requireFinite("upperY", upperY);
        requireFinite("minWorldZ", minWorldZ);
        requireFinite("maxWorldZ", maxWorldZ);
        if (upperY <= lowerY || maxWorldZ <= minWorldZ) {
            throw new IllegalArgumentException("Cloud clip bounds must have positive Y and Z extent");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
