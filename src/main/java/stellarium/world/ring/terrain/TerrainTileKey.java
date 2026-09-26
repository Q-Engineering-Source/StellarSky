package stellarium.world.ring.terrain;

/** 64x64 columns at 2^level block spacing. Epoch is session identity, never a disk-cache key. */
public record TerrainTileKey(long worldEpoch, int level, long x, long z) {
    public static final int WIDTH = 64;
    public static final int COLUMN_COUNT = WIDTH * WIDTH;

    public TerrainTileKey {
        if (worldEpoch <= 0 || level < 0 || level > 24) throw new IllegalArgumentException("Invalid terrain tile epoch/level");
        long width = (long) WIDTH << level;
        Math.multiplyExact(x, width);
        Math.multiplyExact(z, width);
        Math.multiplyExact(Math.addExact(x, 1), width);
        Math.multiplyExact(Math.addExact(z, 1), width);
    }

    public static TerrainTileKey atBlock(long epoch, int level, long blockX, long blockZ) {
        if (level < 0 || level > 24) throw new IllegalArgumentException("Invalid terrain level");
        long width = (long) WIDTH << level;
        return new TerrainTileKey(epoch, level, Math.floorDiv(blockX, width), Math.floorDiv(blockZ, width));
    }

    public long minBlockX() { return x * ((long) WIDTH << level); }
    public long minBlockZ() { return z * ((long) WIDTH << level); }
}
