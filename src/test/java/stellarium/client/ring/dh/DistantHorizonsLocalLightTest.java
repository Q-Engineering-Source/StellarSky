package stellarium.client.ring.dh;

import org.junit.Test;
import stellarium.world.ring.*;
import static org.junit.Assert.*;

public class DistantHorizonsLocalLightTest {
    @Test public void missingPhaseRemainsDarkBelowBoardButKeepsPhysicalBounds() {
        var origin = new RingworldRenderObserver(30000000, 100, -8190);
        var shade = new RingworldSunshade(1000, 500, 24000, 0, 0, 20, 10);
        var snapshot = new RingworldDisplaySnapshot(new Object(), new Object(), null, shade, null,
                512, 8, origin, 1, 0);
        var light = DistantHorizonsLocalLight.from(snapshot, origin);
        assertEquals(1, light.mode());
        assertArrayEquals(new double[]{412, 420, -2, 16382}, light.bounds(), 0);
    }

    @Test public void emptyBoardPreservesNativeLightEvenWithoutPhase() {
        var origin = new RingworldRenderObserver(0, 0, 0);
        var shade = new RingworldSunshade(1000, 0, 24000, 0, 0, 0);
        var snapshot = new RingworldDisplaySnapshot(new Object(), new Object(), null, shade, null,
                512, 8, origin, 1, 0);
        assertEquals(0, DistantHorizonsLocalLight.from(snapshot, origin).mode());
    }
}
