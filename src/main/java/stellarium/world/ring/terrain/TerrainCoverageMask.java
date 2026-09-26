package stellarium.world.ring.terrain;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Conservative spatial union of admitted complete-column evidence onto a 64x64 tile.
 * This is a candidate mask, not permission to remove geometry: frame lifetime and shader clipping
 * remain the rendering consumer's responsibility. No chunk reads, generation, or GL calls occur here.
 */
public final class TerrainCoverageMask {
    public static final int MAX_PATCHES = 16384;
    private final TerrainTileKey target;
    private final BitSet columns;

    private TerrainCoverageMask(TerrainTileKey target, BitSet columns) {
        this.target = target;
        this.columns = columns;
    }
    public TerrainTileKey target() { return target; }
    public int coveredColumns() { return columns.cardinality(); }
    public BitSet columns() { return (BitSet) columns.clone(); }
    public TerrainCoverageMask union(TerrainCoverageMask other) {
        if(!target.equals(other.target))throw new IllegalArgumentException("Different coverage targets");
        var merged=columns();merged.or(other.columns);return new TerrainCoverageMask(target,merged);
    }
    public boolean covers(int x, int z) {
        if (x < 0 || z < 0 || x >= 64 || z >= 64) throw new IndexOutOfBoundsException("Tile column");
        return columns.get(x * 64 + z);
    }

    public static TerrainCoverageMask project(TerrainTileKey target, List<Patch> patches) {
        Objects.requireNonNull(target);
        if (patches.size() > MAX_PATCHES) throw new IllegalArgumentException("Coverage patch budget exceeded");
        long width = 64L << target.level();
        long minX = target.minBlockX(), minZ = target.minBlockZ();
        long maxX = Math.addExact(minX, width), maxZ = Math.addExact(minZ, width);
        var levels = new TreeMap<Integer, HashMap<TerrainTileKey, BitSet>>();
        var covered = new BitSet(4096);
        for (var patch : patches) {
            if (patch.key.worldEpoch() != target.worldEpoch()) throw new IllegalArgumentException("Foreign world epoch");
            long x = patch.key.minBlockX(), z = patch.key.minBlockZ();
            long patchWidth=64L << patch.key.level();
            if (x >= maxX || z >= maxZ || x + patchWidth <= minX || z + patchWidth <= minZ) continue;
            if(patch.key.level()>=target.level()) {
                // At most 4096 lookups, even if one admitted cell spans millions of blocks.
                for(int cx=0;cx<64;cx++) for(int cz=0;cz<64;cz++) {
                    long px=(minX+((long)cx<<target.level())-x)>>patch.key.level();
                    long pz=(minZ+((long)cz<<target.level())-z)>>patch.key.level();
                    if(px>=0 && px<64 && pz>=0 && pz<64 && patch.columns.get((int)(px*64+pz)))
                        covered.set(cx*64+cz);
                }
            } else {
                levels.computeIfAbsent(patch.key.level(),ignored->new HashMap<>())
                        .computeIfAbsent(patch.key,ignored->new BitSet(4096)).or(patch.columns);
            }
        }
        // Union equal-scale evidence before promoting complete 2x2 groups. This cannot double-count
        // overlapping parent/child LODs, and never expands a coarse patch into individual blocks.
        while(!levels.isEmpty()) {
            var current=levels.pollFirstEntry();
            int level=current.getKey();
            if(level==target.level()) {
                var bits=current.getValue().get(target);
                if(bits!=null)covered.or(bits);
                continue;
            }
            var next=levels.computeIfAbsent(level+1,ignored->new HashMap<>());
            for(var entry:current.getValue().entrySet()) {
                var key=entry.getKey();
                var parent=new TerrainTileKey(key.worldEpoch(),level+1,Math.floorDiv(key.x(),2),Math.floorDiv(key.z(),2));
                int offsetX=(int)Math.floorMod(key.x(),2)*32,offsetZ=(int)Math.floorMod(key.z(),2)*32;
                var bits=entry.getValue();
                BitSet output=null;
                for(int cx=0;cx<64;cx+=2)for(int cz=0;cz<64;cz+=2) {
                    int i=cx*64+cz;
                    if(bits.get(i)&&bits.get(i+1)&&bits.get(i+64)&&bits.get(i+65)) {
                        if(output==null)output=next.computeIfAbsent(parent,ignored->new BitSet(4096));
                        output.set((offsetX+cx/2)*64+offsetZ+cz/2);
                    }
                }
            }
            if(next.isEmpty())levels.remove(level+1);
        }
        return new TerrainCoverageMask(target, covered);
    }

    /** One bit per complete 2^level square, x-major. Admission must prove its entire footprint. */
    public record Patch(TerrainTileKey key, BitSet columns) {
        public Patch {
            Objects.requireNonNull(key);
            Objects.requireNonNull(columns);
            if (columns.length() > 4096) throw new IllegalArgumentException("Column mask exceeds tile");
            columns = (BitSet) columns.clone();
        }
        @Override public BitSet columns() { return (BitSet) columns.clone(); }
    }
}
