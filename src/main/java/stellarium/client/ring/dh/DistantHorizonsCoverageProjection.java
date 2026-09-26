package stellarium.client.ring.dh;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import stellarium.world.ring.terrain.TerrainCoverageMask;
import stellarium.world.ring.terrain.TerrainTileKey;

/** World-bound projection of completed frame evidence; does not change any visible geometry. */
public final class DistantHorizonsCoverageProjection {
    private final long epoch;
    private final Object dhWorld;

    public DistantHorizonsCoverageProjection(long epoch, Object dhWorld) {
        if (epoch <= 0) throw new IllegalArgumentException("Invalid world epoch");
        this.epoch = epoch;
        this.dhWorld = Objects.requireNonNull(dhWorld);
    }

    /** Use on coverage changes, not as a mandatory per-frame full-world scan. Do not persist this mask. */
    public TerrainCoverageMask project(TerrainTileKey target, DistantHorizonsFrameCoverage.Snapshot snapshot,
                                       Object currentFrame) {
        if (target.worldEpoch() != epoch) throw new IllegalArgumentException("Projection belongs to another world epoch");
        if (snapshot == null) return TerrainCoverageMask.project(target, List.of());
        var patches = new ArrayList<TerrainCoverageMask.Patch>();
        for (var input : snapshot.completedFor(dhWorld, currentFrame)) {
            var coverage = input.coverage();
            int level=DhSectionPos.getDetailLevel(coverage.sectionPos())-6;
            if(level<0 || level>24)continue;
            var key = TerrainTileKey.atBlock(epoch, level, DhSectionPos.getMinCornerBlockX(coverage.sectionPos()),
                    DhSectionPos.getMinCornerBlockZ(coverage.sectionPos()));
            var mask = new BitSet(4096);
            for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
                if (coverage.column(x,z).isReal()) mask.set(x * 64 + z);
            }
            if (!mask.isEmpty()) patches.add(new TerrainCoverageMask.Patch(key, mask));
            if(patches.size()==TerrainCoverageMask.MAX_PATCHES)break;
        }
        return TerrainCoverageMask.project(target, patches);
    }

    /** Current render scope already binds the exact world/frame; gather admitted patches once per view. */
    public static List<TerrainCoverageMask.Patch> currentPatches(long epoch,
            DistantHorizonsFrameCoverage.Snapshot snapshot,Object currentFrame) {
        if(snapshot==null)return List.of();
        var patches=new ArrayList<TerrainCoverageMask.Patch>();
        for(var input:snapshot.completed(currentFrame)) {
            var coverage=input.coverage();
            int level=DhSectionPos.getDetailLevel(coverage.sectionPos())-6;
            if(level<0 || level>24)continue;
            var key=TerrainTileKey.atBlock(epoch,level,DhSectionPos.getMinCornerBlockX(coverage.sectionPos()),
                    DhSectionPos.getMinCornerBlockZ(coverage.sectionPos()));
            var mask=new BitSet(4096);
            for(int i=0;i<4096;i++)if(coverage.column(i/64,i%64).isReal())mask.set(i);
            if(!mask.isEmpty())patches.add(new TerrainCoverageMask.Patch(key,mask));
            if(patches.size()==TerrainCoverageMask.MAX_PATCHES)break;
        }
        return List.copyOf(patches);
    }
}
