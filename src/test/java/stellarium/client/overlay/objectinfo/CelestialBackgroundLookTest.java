package stellarium.client.overlay.objectinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import stellarapi.api.lib.math.Vector3;

public class CelestialBackgroundLookTest {
    @Test
    public void ringBackgroundLookInvertsTheActualSkyRendererBasis() {
        for (double[] mc : new double[][] {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                {0.6, 0.8, 0}
        }) {
            Vector3 legacyGround = new Vector3(mc[0], -mc[2], mc[1]);
            Vector3 background = CelestialTargetTracker.backgroundGroundLook(legacyGround, true);
            // SkyRenderer maps (E,N,Z) to Minecraft (-E,Z,N).
            assertEquals(mc[0], -background.getX(), 0.0);
            assertEquals(mc[1], background.getZ(), 0.0);
            assertEquals(mc[2], background.getY(), 0.0);
            assertEquals(mc[0], legacyGround.getX(), 0.0);
            assertEquals(-mc[2], legacyGround.getY(), 0.0);
        }
    }

    @Test
    public void nonRingKeepsTheExistingLookInputAndRingKeepsAltitude() {
        Vector3 legacy = new Vector3(0.3, -0.4, 0.5);
        assertSame(legacy, CelestialTargetTracker.backgroundGroundLook(legacy, false));
        assertEquals(legacy.getZ(), CelestialTargetTracker.backgroundGroundLook(legacy, true).getZ(), 0.0);
    }
}
