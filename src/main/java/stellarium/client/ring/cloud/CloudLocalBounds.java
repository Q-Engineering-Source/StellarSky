package stellarium.client.ring.cloud;

/** Conservative bounds for the same clipped physical slabs expanded by cloud_lod.vert. */
public final class CloudLocalBounds {
    private CloudLocalBounds() {}

    public static CloudLodPatchMeshBuilder.Aabb project(CloudLodPatchMeshBuilder.Aabb b,
            CloudCurvaturePolicy.Segment s, double radius, double eyeX, double cameraX,
            double originY, double originZ) {
        double minX = Math.max(b.minX() - eyeX, (float)s.minX());
        double maxX = Math.min(b.maxX() - eyeX, (float)s.maxX());
        if (minX > maxX) return null;
        double lowX = Double.POSITIVE_INFINITY, highX = Double.NEGATIVE_INFINITY;
        double lowY = Double.POSITIVE_INFINITY, highY = Double.NEGATIVE_INFINITY;
        // Zero is the curved slab's only radial extremum in the normal near domain.
        for (double x : new double[]{minX, maxX, Math.clamp(0.0, minX, maxX)}) {
            for (double y : new double[]{b.minY(), b.maxY()}) {
                double baseX = s.curved() ? radius * Math.sin(x / radius)
                        : (float)s.slopeX() * x + (float)s.interceptX();
                double halfSin = Math.sin(x / (2.0 * radius));
                double rise = s.curved() ? 2.0 * radius * halfSin * halfSin
                        : (float)s.slopeY() * x + (float)s.interceptY();
                double displayedX = baseX * (1.0 - y / radius) + cameraX;
                double displayedY = y + rise * (1.0 - y / radius) - originY;
                lowX = Math.min(lowX, displayedX); highX = Math.max(highX, displayedX);
                lowY = Math.min(lowY, displayedY); highY = Math.max(highY, displayedY);
            }
        }
        if (s.curved() && Math.max(Math.abs(minX), Math.abs(maxX)) / radius >= Math.PI / 2.0) {
            double radial = Math.max(Math.abs(radius - b.minY()), Math.abs(radius - b.maxY()));
            lowX = cameraX - radial; highX = cameraX + radial;
            lowY = radius - radial - originY; highY = radius + radial - originY;
        }
        // Covers split floats, uniforms and the final float gl_Position conversion.
        double pad = 0.02 + Math.max(Math.abs(lowX), Math.abs(highX)) * 1.0e-6;
        return new CloudLodPatchMeshBuilder.Aabb(lowX - pad, highX + pad, lowY - pad, highY + pad,
                b.minZ() - originZ - pad, b.maxZ() - originZ + pad);
    }
}
