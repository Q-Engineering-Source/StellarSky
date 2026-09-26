package stellarium.client.ring.cloud;

/** Physical overlap stays inside the authoritative tail page even with smaller cloud cells. */
public final class CloudModelHandoff {
    public static final double LOCAL_AIR_DISTANCE = 2_048.0D;
    public static final double GROUND_PROXY_START = 262_144.0D;
    private CloudModelHandoff() { }

    public static double farStart(double cellSize) { return 4_194_304.0D * scale(cellSize); }
    public static double farEnd(double cellSize) { return 6_291_456.0D * scale(cellSize); }
    private static double scale(double cellSize) {
        if (!Double.isFinite(cellSize) || cellSize <= 0.0D) throw new IllegalArgumentException("Invalid cloud cell size");
        return Math.min(1.0D, cellSize / 12.0D);
    }
    public static double sheetY(double baseY, CloudGeometrySettings geometry, CloudClipBounds clip) {
        return Math.max(clip.lowerY(), Math.min(clip.upperY(), baseY + geometry.thicknessBlocks() * 4.0D));
    }
    /** Smooth complementary coverage; independent cloud fields never form a union in the overlap. */
    public static double farWeight(double distance, double cellSize) {
        double x = Math.clamp((distance-farStart(cellSize))/(farEnd(cellSize)-farStart(cellSize)),0.0D,1.0D);
        return x*x*(3.0D-2.0D*x);
    }
    public static double rank(int x, int y) {
        int a=x&3, b=y&3;
        return ((((a&1)*2+(b&1))*4+(a&2)+((b&2)>>1))+0.5D)/16.0D;
    }
}
