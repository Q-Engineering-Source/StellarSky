package stellarium.world.ring.terrain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Comparator;
import stellarium.world.ring.RingworldStripBounds;

/** Fixed nested bands: planar height terrain nearby, progressively coarser land/ocean graphics afar. */
public final class SeedPreviewDemand {
    public static final int MAX_TILES=48;
    private SeedPreviewDemand() {}
    public static List<TerrainTileKey> around(long epoch,long blockX) {
        var wanted=new LinkedHashSet<TerrainTileKey>();
        for(int level=8;level<=12;level++) {
            long center=Math.floorDiv(blockX,64L<<level);
            // The L9 center is wholly covered by the three L8 bands, including at negative X.
            // Omit its two Z tiles to leave four slots for a near parent to split into four.
            for(int sign:(level==9?new int[]{-1,1}:new int[]{-1,0,1}))for(long z=-1;z<1;z++)
                wanted.add(new TerrainTileKey(epoch,level,center+sign,z));
        }
        long center=Math.floorDiv(blockX,4096L);
        for(int distance=1;distance<=2;distance++)for(int sign:new int[]{-1,1})for(long z=-2;z<2;z++)
            wanted.add(new TerrainTileKey(epoch,6,center+distance*sign,z));
        return List.copyOf(wanted);
    }

    /** Order by the natural ground footprint, not the empty Space part of a coarse tile. */
    public static List<TerrainTileKey> nearestFirst(List<TerrainTileKey> keys,double eyeX,double eyeZ) {
        if(!Double.isFinite(eyeX)||!Double.isFinite(eyeZ))throw new IllegalArgumentException("Non-finite preview eye");
        return keys.stream().sorted(Comparator.comparingDouble((TerrainTileKey key)->distanceSquared(key,eyeX,eyeZ))
                .thenComparingInt(TerrainTileKey::level).thenComparingLong(TerrainTileKey::x)
                .thenComparingLong(TerrainTileKey::z)).toList();
    }

    static double distanceSquared(TerrainTileKey key,double eyeX,double eyeZ) {
        long width=64L<<key.level();
        double minZ=Math.max(key.minBlockZ(),RingworldStripBounds.BOARD_MIN_Z);
        double maxZ=Math.min(key.minBlockZ()+width,RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        if(minZ>=maxZ)return Double.POSITIVE_INFINITY;
        double dx=Math.max(0,Math.max(key.minBlockX()-eyeX,eyeX-(key.minBlockX()+width)));
        double dz=Math.max(0,Math.max(minZ-eyeZ,eyeZ-maxZ));
        return dx*dx+dz*dz;
    }
}
