package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class SeedPreviewRefinementTest {
    @Test public void nearParentHasRoomForAllFourChildren() {
        var planner=new SeedPreviewRefinement();
        var parent=new TerrainTileKey(7,6,0,1);
        planner.reject(parent);
        var wanted=planner.plan(SeedPreviewDemand.around(7,-7),-6.125,8197.155);
        assertFalse(wanted.contains(parent));
        for(int x=0;x<2;x++)for(int z=2;z<4;z++)assertTrue(wanted.contains(new TerrainTileKey(7,5,x,z)));
        assertTrue(wanted.size()<=48);
    }
    @Test public void rejectedCentralCoarseTileCanRefineWithinFullDemandBudget() {
        var planner=new SeedPreviewRefinement();
        var parent=new TerrainTileKey(7,8,-1,0);
        planner.reject(parent);
        var wanted=planner.plan(SeedPreviewDemand.around(7,-7),-6.125,8197.155);
        assertFalse(wanted.contains(parent));
        assertTrue(wanted.contains(new TerrainTileKey(7,7,-1,0)));
        assertTrue(wanted.size()<=48);
        assertEquals(new TerrainTileKey(7,7,-1,0),wanted.getFirst());
    }
    @Test public void rejectedParentIsReplacedByIndependentlyQueriedChildren() {
        var planner=new SeedPreviewRefinement();
        var parent=new TerrainTileKey(7,8,1,0);
        planner.reject(parent);
        var wanted=planner.plan(List.of(parent),0,0);
        assertEquals(List.of(new TerrainTileKey(7,7,2,0),new TerrainTileKey(7,7,3,0)),wanted);
    }
    @Test public void nestedRefinementStaysWithinBudgetAndPreservesUnsplitRoots() {
        var planner=new SeedPreviewRefinement();
        var roots=SeedPreviewDemand.around(7,0);
        for(var root:roots)planner.reject(root);
        var wanted=planner.plan(roots,0,0);
        assertTrue(wanted.size()<=48);
        for(var root:roots) {
            long width=64L<<root.level();
            for(long x=root.minBlockX();x<root.minBlockX()+width;x+=width/2) {
                final long sampleX=x;
                assertTrue(wanted.stream().anyMatch(k->k.minBlockX()<=sampleX&&k.minBlockX()+(64L<<k.level())>sampleX
                        &&k.minBlockZ()<=Math.max(-8192,root.minBlockZ())
                        &&k.minBlockZ()+(64L<<k.level())>Math.max(-8192,root.minBlockZ())));
            }
        }
    }
    @Test public void unrelatedScopeAndTooDistantChildrenAreNotUsed() {
        var planner=new SeedPreviewRefinement();
        var old=new TerrainTileKey(7,6,7,0);planner.reject(old);
        assertEquals(List.of(old),planner.plan(List.of(old),0,0));
        var fresh=new TerrainTileKey(8,6,7,0);
        assertEquals(List.of(fresh),planner.plan(List.of(fresh),0,0));
        planner.clear();
        assertEquals(List.of(old),planner.plan(List.of(old),0,0));
    }
}
