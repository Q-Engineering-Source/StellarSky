package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class OverworldDensityLatticeTest {
    private static double[] plane(double height,double slopeX,double slopeZ) {
        var values = new double[825];
        for(int x=0;x<5;x++) for(int z=0;z<5;z++) for(int y=0;y<33;y++)
            values[(x*5+z)*33+y]=height+slopeX*x*4+slopeZ*z*4-y*8;
        return values;
    }
    @Test public void baseEnvelopeUsesActualSeaLevelAndStrictPositiveDensity() {
        var lattice=new OverworldDensityLattice(-2,3,71,plane(60,0,0));
        assertEquals(new OverworldDensityLattice.Surface(60,71,false),lattice.surface(0,0));
        assertEquals(-2,lattice.chunkX()); assertEquals(3,lattice.chunkZ());
        assertEquals(new OverworldDensityLattice.Surface(80,80,true),
                new OverworldDensityLattice(0,0,71,plane(80,0,0)).surface(15,15));
    }
    @Test public void interpolationKeepsAxesAndFourByEightSpacing() {
        var lattice=new OverworldDensityLattice(0,0,16,plane(16,3,1));
        assertEquals(27,lattice.surface(3,2).baseGroundTop());
        assertEquals(25,lattice.surface(2,3).baseGroundTop());
        assertEquals(76,lattice.surface(15,15).baseGroundTop());
    }
    @Test public void sampleOwnsDensityAndBoundsAreExplicit() {
        var values=plane(50,0,0);
        var lattice=new OverworldDensityLattice(0,0,63,values); Arrays.fill(values,-1);
        assertEquals(50,lattice.surface(0,0).baseGroundTop());
        assertThrows(IndexOutOfBoundsException.class,()->lattice.surface(16,0));
        assertThrows(IllegalArgumentException.class,()->new OverworldDensityLattice(0,0,63,new double[1]));
        values[0]=Double.NaN;
        assertThrows(IllegalArgumentException.class,()->new OverworldDensityLattice(0,0,63,values));
    }
    @Test public void noSolidAndTopSolidAreDistinct() {
        var values=new double[825]; Arrays.fill(values,-1);
        assertEquals(new OverworldDensityLattice.Surface(0,63,false),new OverworldDensityLattice(0,0,63,values).surface(0,0));
        Arrays.fill(values,1);
        assertEquals(256,new OverworldDensityLattice(0,0,63,values).surface(0,0).baseGroundTop());
    }
}
