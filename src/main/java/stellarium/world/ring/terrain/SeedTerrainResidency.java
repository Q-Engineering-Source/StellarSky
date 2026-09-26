package stellarium.world.ring.terrain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import stellarium.world.ring.RingworldStripBounds;

/** Bounded preview residency and incomplete-refinement fallback; independent of the view frustum. */
public final class SeedTerrainResidency {
    private long epoch;
    private Set<TerrainTileKey> previousWanted=Set.of(),previousUploaded=Set.of();
    private Selection previous;
    public record Selection(Set<TerrainTileKey> resident,Set<TerrainTileKey> drawable) {
        public Selection {resident=Set.copyOf(resident);drawable=Set.copyOf(drawable);}
    }
    public void clear(){epoch=0;previous=null;previousWanted=Set.of();previousUploaded=Set.of();}

    public Selection update(long currentEpoch,Collection<TerrainTileKey> wantedInput,
                            Collection<TerrainTileKey> uploadedInput,double eyeX) {
        if(currentEpoch<=0||!Double.isFinite(eyeX))throw new IllegalArgumentException("Invalid preview scope");
        var wanted=Set.copyOf(wantedInput);var uploaded=Set.copyOf(uploadedInput);
        if(wanted.size()>SeedPreviewDemand.MAX_TILES||uploaded.size()>SeedPreviewDemand.MAX_TILES+1)
            throw new IllegalArgumentException("Preview residency budget exceeded");
        for(var keys:List.of(wanted,uploaded))for(var key:keys)
            if(key.worldEpoch()!=currentEpoch)throw new IllegalArgumentException("Foreign preview scope");
        if(epoch==currentEpoch&&wanted.equals(previousWanted)&&uploaded.equals(previousUploaded))return previous;
        var ready=new HashSet<>(uploaded);ready.retainAll(wanted);
        var resident=new HashSet<>(ready);
        long minX=wanted.stream().mapToLong(TerrainTileKey::minBlockX).min().orElse(0);
        long maxX=wanted.stream().mapToLong(k->k.minBlockX()+(64L<<k.level())).max().orElse(0);
        var fallback=new ArrayList<TerrainTileKey>();
        for(var key:uploaded)if(!wanted.contains(key)&&key.minBlockX()<maxX
                &&key.minBlockX()+(64L<<key.level())>minX&&!covers(key,ready))fallback.add(key);
        fallback.sort(Comparator.comparingDouble((TerrainTileKey k)->distance(k,eyeX))
                .thenComparingInt(TerrainTileKey::level).thenComparingLong(TerrainTileKey::x).thenComparingLong(TerrainTileKey::z));
        for(var key:fallback)if(resident.size()<SeedPreviewDemand.MAX_TILES)resident.add(key);
        var drawable=new HashSet<>(resident);
        for(var parent:fallback)if(resident.contains(parent)&&covers(parent,wanted)&&!covers(parent,ready)) {
            // The requested fine branch can replace the whole parent, but has not all uploaded yet.
            // Keep this parent visible and suppress its partial descendants as one atomic choice.
            drawable.removeIf(child->child.level()<parent.level()&&contains(parent,child));
        }
        previous=new Selection(resident,drawable);epoch=currentEpoch;
        previousWanted=wanted;previousUploaded=uploaded;return previous;
    }
    private static double distance(TerrainTileKey key,double x) {
        double min=key.minBlockX(),max=min+(64L<<key.level());
        return Math.max(0,Math.max(min-x,x-max));
    }
    private static boolean contains(TerrainTileKey parent,TerrainTileKey child) {
        return TerrainTileKey.atBlock(parent.worldEpoch(),parent.level(),child.minBlockX(),child.minBlockZ()).equals(parent);
    }

    /** Exact union of bounded tile rectangles clipped to the natural strip, without block expansion. */
    private static boolean covers(TerrainTileKey target,Set<TerrainTileKey> sources) {
        long width=64L<<target.level(),minX=target.minBlockX(),maxX=minX+width;
        long minZ=Math.max(target.minBlockZ(),RingworldStripBounds.BOARD_MIN_Z);
        long maxZ=Math.min(target.minBlockZ()+width,RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        if(minZ>=maxZ)return true;
        var rectangles=new ArrayList<long[]>();var boundaries=new TreeSet<Long>();
        boundaries.add(minX);boundaries.add(maxX);
        for(var key:sources) {
            long size=64L<<key.level();
            long x0=Math.max(minX,key.minBlockX()),x1=Math.min(maxX,key.minBlockX()+size);
            long z0=Math.max(minZ,key.minBlockZ()),z1=Math.min(maxZ,key.minBlockZ()+size);
            if(x0>=x1||z0>=z1)continue;
            rectangles.add(new long[]{x0,x1,z0,z1});boundaries.add(x0);boundaries.add(x1);
        }
        var xs=new ArrayList<>(boundaries);
        for(int i=1;i<xs.size();i++) {
            long left=xs.get(i-1),right=xs.get(i),covered=minZ;
            var spans=new ArrayList<long[]>();
            for(var r:rectangles)if(r[0]<=left&&r[1]>=right)spans.add(r);
            spans.sort(Comparator.comparingLong(r->r[2]));
            for(var r:spans) {if(r[2]>covered)return false;covered=Math.max(covered,r[3]);}
            if(covered<maxZ)return false;
        }
        return true;
    }
}
