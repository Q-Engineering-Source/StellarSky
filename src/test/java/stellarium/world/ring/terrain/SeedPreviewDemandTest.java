package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import org.junit.Test;

public class SeedPreviewDemandTest {
    @Test public void nestedBandsStayBoundedAndContinuousAroundBothSignsOfOrigin() {
        for(long x:new long[]{-30_000_000,-524289,-262145,-65537,-16385,-1,0,4095,8192,30000,65535,262145,30_000_000}) {
            var demand=SeedPreviewDemand.around(7,x);
            assertEquals(44,demand.size());assertTrue(demand.size()<=SeedPreviewDemand.MAX_TILES-4);
            assertEquals(demand.size(),new HashSet<>(demand).size());
            var intervals=new ArrayList<long[]>();
            assertTrue(demand.stream().anyMatch(k->k.minBlockX()<=x&&k.minBlockX()+(64L<<k.level())>x));
            for(var key:demand) {
                assertEquals(7,key.worldEpoch());
                assertTrue(key.level()==6 || key.level()>=8 && key.level()<=12);
                if(key.minBlockZ()==0)intervals.add(new long[]{key.minBlockX(),key.minBlockX()+(64L<<key.level())});
            }
            intervals.sort(Comparator.comparingLong(a->a[0]));
            long end=intervals.getFirst()[1];
            for(var interval:intervals) {
                assertTrue("gap for observer "+x+" before "+interval[0],interval[0]<=end);
                end=Math.max(end,interval[1]);
            }
            assertTrue(end-x>=262144);assertTrue(x-intervals.getFirst()[0]>=262144);
        }
    }

    @Test public void edgeObserverRequestsContainingNearSideBeforeOppositeFarTile() {
        var demand=SeedPreviewDemand.nearestFirst(SeedPreviewDemand.around(1,-7),-6.125,8197.155);
        assertEquals(new TerrainTileKey(1,8,-1,0),demand.getFirst());
        double previous=-1;
        for(var key:demand) {
            double distance=SeedPreviewDemand.distanceSquared(key,-6.125,8197.155);
            assertTrue(distance>=previous);previous=distance;
        }
        var south=SeedPreviewDemand.nearestFirst(SeedPreviewDemand.around(1,-7),-6.125,-8197.155);
        assertEquals(new TerrainTileKey(1,8,-1,-1),south.getFirst());
    }
}
