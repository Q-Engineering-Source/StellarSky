package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class SeedTerrainResidencyTest {
    @Test public void oldParentRemainsAloneUntilWholeRequestedChildBranchUploads() {
        var parent=new TerrainTileKey(1,7,0,0);
        var children=new HashSet<TerrainTileKey>();
        for(int x=0;x<2;x++)for(int z=0;z<2;z++)children.add(new TerrainTileKey(1,6,x,z));
        var missing=new TerrainTileKey(1,6,1,1);
        var uploaded=new HashSet<>(children);uploaded.remove(missing);uploaded.add(parent);
        var selector=new SeedTerrainResidency();
        var waiting=selector.update(1,children,uploaded,0);
        assertEquals(4,waiting.resident().size());assertEquals(Set.of(parent),waiting.drawable());
        uploaded.add(missing);
        var complete=selector.update(1,children,uploaded,0);
        assertEquals(children,complete.resident());assertEquals(children,complete.drawable());
    }
    @Test public void knownEmptyOutsideStripDoesNotRequireUnrequestedChildren() {
        var parent=new TerrainTileKey(1,8,-1,-1);
        var left=new TerrainTileKey(1,7,-2,-1);var right=new TerrainTileKey(1,7,-1,-1);
        var wanted=Set.of(left,right);var selector=new SeedTerrainResidency();
        assertEquals(Set.of(parent),selector.update(1,wanted,Set.of(parent,left),-1).drawable());
        assertEquals(wanted,selector.update(1,wanted,Set.of(parent,left,right),-1).drawable());
    }
    @Test public void oldFineMeshCanBridgeARequestedCoarseTileThatIsNotReady() {
        var parent=new TerrainTileKey(1,8,0,0);var child=new TerrainTileKey(1,6,0,0);
        var selector=new SeedTerrainResidency();
        assertEquals(Set.of(child),selector.update(1,Set.of(parent),Set.of(child),0).drawable());
        assertEquals(Set.of(parent),selector.update(1,Set.of(parent),Set.of(child,parent),0).drawable());
    }
    @Test public void stableSetsReuseSelectionAndForeignSessionIsRejected() {
        var key=new TerrainTileKey(1,6,0,0);var selector=new SeedTerrainResidency();
        var first=selector.update(1,Set.of(key),Set.of(key),0);
        assertSame(first,selector.update(1,Set.of(key),Set.of(key),100));
        assertThrows(IllegalArgumentException.class,()->selector.update(2,Set.of(key),Set.of(key),0));
        selector.clear();assertNotSame(first,selector.update(1,Set.of(key),Set.of(key),0));
    }
    @Test public void oldMeshesOutsideCurrentDemandWindowAreReleased() {
        var current=new TerrainTileKey(1,6,10,0);var old=new TerrainTileKey(1,6,-10,0);
        var result=new SeedTerrainResidency().update(1,Set.of(current),Set.of(old),40960);
        assertTrue(result.resident().isEmpty());assertTrue(result.drawable().isEmpty());
    }
    @Test public void readyDemandIsProtectedWhileFallbackResidencyStaysBounded() {
        var wanted=new HashSet<TerrainTileKey>();var uploaded=new HashSet<TerrainTileKey>();
        for(int x=1;x<=44;x++) {
            var key=new TerrainTileKey(1,6,x,0);wanted.add(key);if(x<=40)uploaded.add(key);
        }
        for(int x=1;x<=9;x++)uploaded.add(new TerrainTileKey(1,6,x,-1));
        var result=new SeedTerrainResidency().update(1,wanted,uploaded,0);
        assertEquals(48,result.resident().size());
        for(int x=1;x<=40;x++)assertTrue(result.resident().contains(new TerrainTileKey(1,6,x,0)));
        assertFalse(result.resident().contains(new TerrainTileKey(1,6,9,-1)));
        assertTrue(result.resident().containsAll(result.drawable()));
    }
}
