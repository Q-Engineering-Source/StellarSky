package stellarium.client.ring;

import stellarium.client.ring.cloud.CloudLodLayout;

/** Bounded cosmetic wind sampling shared by near mesh, horizon trace and later air consumers. */
final class SSCloudTraceCachePolicy {
    private SSCloudFrame anchor;
    private SSCloudTraceCacheKey anchorKey;

    SSCloudFrame select(SSCloudFrame current, RingworldCurvatureFrame curvature) {
        if (curvature == null || current.closeWindow()) {
            clear();
            return current;
        }
        if (anchor != null) {
            double oldWind = anchor.meshMotion().windOffsetBlocks();
            double delta = current.meshMotion().windOffsetBlocks() - oldWind;
            if (delta >= 0.0 && delta <= windAllowance(current, curvature)) {
                SSCloudFrame held = current.withWind(oldWind);
                if (anchorKey.equals(SSCloudTraceCacheKey.capture(held, curvature))) return held;
            }
        }
        anchor = current;
        anchorKey = SSCloudTraceCacheKey.capture(current, curvature);
        return current;
    }

    void clear() { anchor = null; anchorKey = null; }

    static double windAllowance(SSCloudFrame frame, RingworldCurvatureFrame curvature) {
        double lower = Math.max(frame.baseY(), frame.clipBounds().lowerY());
        double upper = Math.min(frame.baseY() + frame.geometry().voxelHeightBlocks()
                * CloudLodLayout.LOD0_FINE_3D.layers(), frame.clipBounds().upperY());
        if (upper <= lower) return 0.0;
        double eyeY = curvature.opticalEye().y();
        // Distance to the entire cylindrical slab, not just yesterday's hit pixels:
        // previously empty pixels and newly exposed cloud silhouettes are covered too.
        double minimumDistance = Math.max(0.0, Math.max(lower - eyeY, eyeY - upper));
        double radius = curvature.geometry().radiusMeters();
        double scale = Math.max(Math.abs(1.0 - lower / radius), Math.abs(1.0 - upper / radius));
        if (!(scale > 0.0) || !Double.isFinite(scale)) return 0.0;
        // At 0.6 m/s this also bounds the maximum hold time to less than one second.
        return Math.min(0.5, frame.camera().displacementForQuarterPixel(minimumDistance) / scale);
    }
}
