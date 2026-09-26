package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class SeedTerrainOverlapTest {
    @Test public void onlyUploadedChildFootprintsRemoveTheirPartOfParent() {
        var selector=new SeedTerrainOverlap();
        var parent=new TerrainTileKey(1,7,-1,-1);
        var child=new TerrainTileKey(1,6,-2,-2);
        var alone=selector.update(Set.of(parent));assertEquals(0,alone.get(parent).coveredColumns());
        var partial=selector.update(Set.of(parent,child));
        assertEquals(1024,partial.get(parent).coveredColumns());
        assertTrue(partial.get(parent).covers(0,0));assertFalse(partial.get(parent).covers(32,0));
        assertEquals(0,partial.get(child).coveredColumns());
        var complete=new HashSet<TerrainTileKey>();complete.add(parent);
        for(int x=-2;x<0;x++)for(int z=-2;z<0;z++)complete.add(new TerrainTileKey(1,6,x,z));
        assertEquals(4096,selector.update(complete).get(parent).coveredColumns());
        complete.remove(child);
        assertEquals(3072,selector.update(complete).get(parent).coveredColumns());
    }
    @Test public void unchangedUploadsReuseMasksAndLostChildRestoresParentCoverage() {
        var selector=new SeedTerrainOverlap();var parent=new TerrainTileKey(1,8,0,0);var child=new TerrainTileKey(1,6,0,0);
        var first=selector.update(Set.of(parent,child));long builds=selector.builds();
        var again=selector.update(Set.of(child,parent));
        assertSame(first.get(parent),again.get(parent));assertEquals(builds,selector.builds());
        assertEquals(0,selector.update(Set.of(parent)).get(parent).coveredColumns());
        assertEquals(256,selector.update(Set.of(parent,child)).get(parent).coveredColumns());
    }
    @Test public void foreignEpochAndUnionTargetMismatchAreRejected() {
        var selector=new SeedTerrainOverlap();var a=new TerrainTileKey(1,6,0,0);var b=new TerrainTileKey(2,6,0,0);
        assertThrows(IllegalArgumentException.class,()->selector.update(Set.of(a,b)));
        var first=selector.update(Set.of(a)).get(a);var next=selector.update(Set.of(b)).get(b);
        assertEquals(0,next.coveredColumns());assertThrows(IllegalArgumentException.class,()->first.union(next));
    }
}
