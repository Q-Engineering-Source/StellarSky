package stellarium.client.ring;

import java.util.Objects;

/** Complete raw-trace input identity. Foreground visibility, lighting and weather are composed afresh. */
final class SSCloudTraceCacheKey {
    private final SSCloudFrame frame;
    private final RingworldCurvatureFrame curvature;

    private SSCloudTraceCacheKey(SSCloudFrame frame, RingworldCurvatureFrame curvature) {
        this.frame = Objects.requireNonNull(frame, "frame");
        this.curvature = Objects.requireNonNull(curvature, "curvature");
        if (!curvature.belongsTo(frame.snapshot().world(), frame.snapshot().scene())) {
            throw new IllegalArgumentException("Cloud cache curvature belongs to another world or scene");
        }
    }

    /** First cache is admitted only for an explicit curved frame; the original straight path remains available. */
    static SSCloudTraceCacheKey capture(SSCloudFrame frame, RingworldCurvatureFrame curvature) {
        return new SSCloudTraceCacheKey(frame, curvature);
    }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof SSCloudTraceCacheKey other)) return false;
        SSCloudFrame b = other.frame;
        return frame.snapshot().world() == b.snapshot().world()
                && frame.snapshot().scene() == b.snapshot().scene()
                && frame.snapshot().observer().equals(b.snapshot().observer())
                && frame.cache() == b.cache()
                && frame.fieldSettings().equals(b.fieldSettings())
                && frame.geometry().equals(b.geometry()) && frame.clipBounds().equals(b.clipBounds())
                && frame.meshMotion().equals(b.meshMotion()) && frame.meshKey().equals(b.meshKey())
                && frame.baseY() == b.baseY() && frame.closeWindow() == b.closeWindow()
                && frame.horizon().equals(b.horizon()) && frame.transition().equals(b.transition())
                && frame.cullFine() == b.cullFine() && frame.cullMid() == b.cullMid()
                && frame.cullLow() == b.cullLow() && frame.cullVeryLow() == b.cullVeryLow()
                && frame.tailCoverage().equals(b.tailCoverage())
                && frame.camera().sameView(b.camera()) && curvature.sameOpticalFrame(other.curvature);
    }

    @Override public int hashCode() {
        // Identity fields are stable and sufficient for the one-entry cache; equals checks every input.
        return 31 * System.identityHashCode(frame.cache()) + System.identityHashCode(frame.snapshot().world());
    }
}
