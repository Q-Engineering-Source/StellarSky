package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.BitSet;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import org.junit.Test;

public class TerrainCoverageMaskTest {
    private static TerrainCoverageMask.Patch patch(long x, long z, BitSet bits) {
        return new TerrainCoverageMask.Patch(new TerrainTileKey(1, 0, x, z), bits);
    }
    private static BitSet full() { var bits = new BitSet(4096); bits.set(0,4096); return bits; }

    @Test public void oneChildOnlyCoversItsOwnQuarterOfParent() {
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,1,0,0), List.of(patch(0,0,full())));
        assertEquals(1024, mask.coveredColumns());
        assertTrue(mask.covers(31,31));
        assertFalse(mask.covers(32,0));
        assertFalse(mask.covers(0,32));
    }
    @Test public void missingOneFineColumnRetainsWholeCoarseCell() {
        var bits = full(); bits.clear(0);
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,1,0,0), List.of(patch(0,0,bits)));
        assertEquals(1023, mask.coveredColumns());
        assertFalse(mask.covers(0,0));
        assertTrue(mask.covers(1,0));
    }
    @Test public void duplicateCoverageCannotFillAnUnknownNeighbour() {
        var bits = new BitSet(); bits.set(0); bits.set(1);
        var input = patch(0,0,bits);
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,1,0,0), List.of(input,input,input,input));
        assertEquals(0, mask.coveredColumns());
    }
    @Test public void complementaryInputsUnionBeforeParentAggregation() {
        var first = new BitSet(); first.set(0); first.set(1);
        var second = new BitSet(); second.set(64); second.set(65);
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,1,0,0), List.of(patch(0,0,first),patch(0,0,second)));
        assertEquals(1, mask.coveredColumns()); assertTrue(mask.covers(0,0));
    }
    @Test public void negativeCoordinatesAndHalfOpenEdgesRemainExact() {
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,1,-1,-1),
                List.of(patch(-1,-1,full()),patch(0,-1,full())));
        assertEquals(1024, mask.coveredColumns());
        assertFalse(mask.covers(31,63)); assertTrue(mask.covers(32,32)); assertTrue(mask.covers(63,63));
    }
    @Test public void patchesAndResultsOwnTheirMasks() {
        var bits = full(); var input = patch(0,0,bits); bits.clear(); input.columns().clear();
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,0,0,0), List.of(input));
        assertEquals(4096, mask.coveredColumns()); mask.columns().clear(); assertTrue(mask.covers(0,0));
    }
    @Test public void foreignEpochIsNeverAdmitted() {
        assertThrows(IllegalArgumentException.class, () -> TerrainCoverageMask.project(new TerrainTileKey(2,0,0,0),List.of(patch(0,0,full()))));
    }
    @Test public void coarseEvidenceCoversFineCellsWithoutExpandingItsWorldArea() {
        var bits=new BitSet();bits.set(0);
        var input=new TerrainCoverageMask.Patch(new TerrainTileKey(1,24,0,0),bits);
        assertEquals(4096,TerrainCoverageMask.project(new TerrainTileKey(1,0,0,0),List.of(input)).coveredColumns());
        assertEquals(0,TerrainCoverageMask.project(new TerrainTileKey(1,0,262144,0),List.of(input)).coveredColumns());
    }
    @Test public void mixedLevelsUnionWithoutCountingOverlapsTwice() {
        var coarseBits=new BitSet();coarseBits.set(0);
        var coarse=new TerrainCoverageMask.Patch(new TerrainTileKey(1,1,0,0),coarseBits);
        var fineBits=new BitSet();fineBits.set(0,4);fineBits.set(64,68);fineBits.set(128,132);fineBits.set(192,196);
        fineBits.clear(0);fineBits.clear(1);fineBits.clear(64);fineBits.clear(65);
        var fine=patch(0,0,fineBits);
        var target=new TerrainTileKey(1,2,0,0);
        assertEquals(1,TerrainCoverageMask.project(target,List.of(coarse,fine,fine,coarse)).coveredColumns());
        fineBits.clear(195);
        assertEquals(0,TerrainCoverageMask.project(target,List.of(coarse,patch(0,0,fineBits),coarse)).coveredColumns());
    }
    @Test public void hugeParentDoesNotExpandIntoBillionsOfFineCells() {
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,24,0,0), List.of(patch(0,0,full())));
        assertEquals(0,mask.coveredColumns());
    }
    @Test public void fourCompleteChildrenCoverTheirParent() {
        var mask = TerrainCoverageMask.project(new TerrainTileKey(1,1,0,0),
                List.of(patch(0,0,full()),patch(1,0,full()),patch(0,1,full()),patch(1,1,full())));
        assertEquals(4096,mask.coveredColumns());
    }
    @Test public void randomProjectionMatchesIndependentFineCellMembership() {
        var random = new Random(530020L);
        for (int level = 0; level <= 3; level++) {
            var known = new HashSet<Long>();
            var inputs = new ArrayList<TerrainCoverageMask.Patch>();
            int width = 64 << level;
            for (int tx = -2; tx < 0; tx++) for (int tz = -2; tz < 0; tz++) {
                var bits = new BitSet();
                for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) if (random.nextInt(10) != 0) {
                    bits.set(x*64+z);
                    known.add(pack(tx*64+x,tz*64+z));
                }
                inputs.add(patch(tx,tz,bits));
            }
            var mask = TerrainCoverageMask.project(new TerrainTileKey(1,level,-1,-1),inputs);
            int step = 1 << level;
            for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
                boolean complete = true;
                for (int dx = 0; dx < step; dx++) for (int dz = 0; dz < step; dz++) {
                    complete &= known.contains(pack(-width+x*step+dx,-width+z*step+dz));
                }
                assertEquals("level="+level+" x="+x+" z="+z,complete,mask.covers(x,z));
            }
        }
    }
    private static long pack(int x,int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    @Test public void mixedScaleProjectionMatchesExplicitBlockUnionAtNegativeCoordinates() {
        var random=new Random(530021);
        var known=new HashSet<Long>();
        var inputs=new ArrayList<TerrainCoverageMask.Patch>();
        for(int level=0;level<=2;level++) {
            int step=1<<level;
            for(int repeat=0;repeat<3;repeat++) {
                var bits=new BitSet();
                for(int x=0;x<64;x++)for(int z=0;z<64;z++)if(random.nextInt(3)==0) {
                    bits.set(x*64+z);
                    for(int dx=0;dx<step;dx++)for(int dz=0;dz<step;dz++)
                        known.add(pack(-64*step+x*step+dx,-64*step+z*step+dz));
                }
                inputs.add(new TerrainCoverageMask.Patch(new TerrainTileKey(1,level,-1,-1),bits));
            }
        }
        var mask=TerrainCoverageMask.project(new TerrainTileKey(1,2,-1,-1),inputs);
        for(int x=0;x<64;x++)for(int z=0;z<64;z++) {
            boolean expected=true;
            for(int dx=0;dx<4;dx++)for(int dz=0;dz<4;dz++)
                expected &= known.contains(pack(-256+x*4+dx,-256+z*4+dz));
            assertEquals("x="+x+" z="+z,expected,mask.covers(x,z));
        }
    }
}
