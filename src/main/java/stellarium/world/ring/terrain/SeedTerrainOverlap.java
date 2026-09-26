package stellarium.world.ring.terrain;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** CPU draw-only mask for uploaded preview footprints; never publishes REAL data or changes admission. */
public final class SeedTerrainOverlap {
    private final Map<TerrainTileKey,Entry> cache=new HashMap<>();
    private long builds;
    public long builds(){return builds;}
    public void clear(){cache.clear();}
    public Map<TerrainTileKey,TerrainCoverageMask> update(Set<TerrainTileKey> uploaded) {
        if(uploaded.size()>SeedPreviewDemand.MAX_TILES)throw new IllegalArgumentException("Preview draw budget exceeded");
        if(uploaded.isEmpty()){clear();return Map.of();}
        long epoch=uploaded.iterator().next().worldEpoch();
        for(var key:uploaded)if(key.worldEpoch()!=epoch)throw new IllegalArgumentException("Mixed preview worlds");
        cache.keySet().retainAll(uploaded);
        var masks=new HashMap<TerrainTileKey,TerrainCoverageMask>();
        var complete=new BitSet(4096);complete.set(0,4096);
        for(var target:uploaded) {
            var finer=new HashSet<TerrainTileKey>();
            for(var key:uploaded) {
                if(key.level()<target.level() && TerrainTileKey.atBlock(epoch,target.level(),key.minBlockX(),key.minBlockZ()).equals(target))
                    finer.add(key);
            }
            var previous=cache.get(target);
            if(previous==null || !previous.finer.equals(finer)) {
                var patches=finer.stream().map(key->new TerrainCoverageMask.Patch(key,complete)).toList();
                previous=new Entry(Set.copyOf(finer),TerrainCoverageMask.project(target,patches));
                cache.put(target,previous);builds++;
            }
            masks.put(target,previous.mask);
        }
        return Map.copyOf(masks);
    }
    private record Entry(Set<TerrainTileKey> finer,TerrainCoverageMask mask) {}
}
