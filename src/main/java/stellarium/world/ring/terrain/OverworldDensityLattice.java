package stellarium.world.ring.terrain;

/** Owned vanilla base-density sample, before surface replacement, caves, structures and decoration. */
public final class OverworldDensityLattice {
    private final int chunkX, chunkZ, seaLevel;
    private final double[] density;

    public OverworldDensityLattice(int chunkX, int chunkZ, int seaLevel, double[] density) {
        if (seaLevel < 0 || seaLevel > 256 || density.length != 5 * 5 * 33) {
            throw new IllegalArgumentException("Unsupported Overworld density lattice");
        }
        Math.multiplyExact(chunkX, 16); Math.multiplyExact(chunkZ, 16);
        this.chunkX = chunkX; this.chunkZ = chunkZ; this.seaLevel = seaLevel;
        this.density = density.clone();
        for (double value : this.density) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite density");
    }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int seaLevel() { return seaLevel; }

    /** Linear base-density envelope, intentionally not a promise of the later decorated surface. */
    public Surface surface(int localX, int localZ) {
        if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) throw new IndexOutOfBoundsException("Chunk column");
        int cellX = localX / 4, cellZ = localZ / 4;
        double tx = (localX % 4) / 4.0, tz = (localZ % 4) / 4.0;
        for (int y = 255; y >= 0; y--) {
            int cellY = y / 8;
            double lower = horizontal(cellX,cellZ,cellY,tx,tz);
            double upper = horizontal(cellX,cellZ,cellY+1,tx,tz);
            if (lerp(lower,upper,(y%8)/8.0) > 0) {
                int groundTop = y + 1;
                return new Surface(groundTop,Math.max(groundTop,seaLevel),groundTop >= seaLevel);
            }
        }
        return new Surface(0,seaLevel,false);
    }
    private double horizontal(int x,int z,int y,double tx,double tz) {
        return lerp(lerp(at(x,z,y),at(x+1,z,y),tx),lerp(at(x,z+1,y),at(x+1,z+1,y),tx),tz);
    }
    private double at(int x,int z,int y) { return density[(x*5+z)*33+y]; }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    public record Surface(int baseGroundTop, int visibleTop, boolean land) {}
}
