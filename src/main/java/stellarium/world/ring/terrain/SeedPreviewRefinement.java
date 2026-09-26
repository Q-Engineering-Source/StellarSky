package stellarium.world.ring.terrain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import stellarium.world.ring.RingworldStripBounds;

/** Rejected parents are query hints, never evidence that any child lacks real data. */
public final class SeedPreviewRefinement {
    private final LinkedHashSet<TerrainTileKey> rejected=new LinkedHashSet<>();
    public void clear(){rejected.clear();}
    public void reject(TerrainTileKey key) {
        rejected.add(key);
        while(rejected.size()>256)rejected.removeFirst();
    }
    public List<TerrainTileKey> plan(List<TerrainTileKey> roots,double eyeX,double eyeZ) {
        if(roots.size()>SeedPreviewDemand.MAX_TILES||!Double.isFinite(eyeX)||!Double.isFinite(eyeZ))
            throw new IllegalArgumentException("Invalid preview demand");
        if(roots.isEmpty()){clear();return List.of();}
        long epoch=roots.getFirst().worldEpoch();
        for(var root:roots)if(root.worldEpoch()!=epoch)throw new IllegalArgumentException("Mixed preview scope");
        rejected.removeIf(key->key.worldEpoch()!=epoch||roots.stream().noneMatch(root->contains(root,key)));
        var result=new LinkedHashSet<>(roots);
        for(var root:SeedPreviewDemand.nearestFirst(roots,eyeX,eyeZ))refine(root,0,result,eyeX,eyeZ);
        return SeedPreviewDemand.nearestFirst(List.copyOf(result),eyeX,eyeZ);
    }
    private void refine(TerrainTileKey parent,int depth,LinkedHashSet<TerrainTileKey> result,double eyeX,double eyeZ) {
        if(depth>=2||parent.level()==0||!rejected.contains(parent)||!result.contains(parent))return;
        var children=new ArrayList<TerrainTileKey>();
        for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
            var child=new TerrainTileKey(parent.worldEpoch(),parent.level()-1,parent.x()*2+x,parent.z()*2+z);
            long width=64L<<child.level();
            if(child.minBlockZ()>=RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE
                    ||child.minBlockZ()+width<=RingworldStripBounds.BOARD_MIN_Z)continue;
            // Match server range admission before replacing the parent request.
            if(Math.abs(child.minBlockX()+width*.5-eyeX)>width*8.0
                    ||Math.abs(child.minBlockZ()+width*.5-eyeZ)>width*8.0)return;
            children.add(child);
        }
        long added=children.stream().filter(key->!result.contains(key)).count();
        if(children.isEmpty()||result.size()-1+added>SeedPreviewDemand.MAX_TILES)return;
        result.remove(parent);result.addAll(children);
        for(var child:children)refine(child,depth+1,result,eyeX,eyeZ);
    }
    private static boolean contains(TerrainTileKey parent,TerrainTileKey child) {
        return parent.level()>=child.level()&&TerrainTileKey.atBlock(parent.worldEpoch(),parent.level(),
                child.minBlockX(),child.minBlockZ()).equals(parent);
    }
}
