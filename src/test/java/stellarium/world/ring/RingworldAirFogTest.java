package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldAirFogTest {
    @Test
    public void airFogEndpointsPreserveBaselineAndClearEvenExtendedFarPlanes() {
        assertEquals(new RingworldAirFog.LinearRange(100.0f, 200.0f),
                RingworldAirFog.linearRange(100.0f, 200.0f, 1.0));
        assertEquals(new RingworldAirFog.LinearRange(100.0f, 300.0f),
                RingworldAirFog.linearRange(100.0f, 200.0f, 0.5));
        RingworldAirFog.LinearRange vacuum = RingworldAirFog.linearRange(0.0f, 256.0f, 0.0);
        assertTrue(vacuum.start() > 30_000_000.0f);
        assertTrue(Float.isFinite(vacuum.end()));
        assertTrue(vacuum.end() > vacuum.start());
    }

    @Test
    public void densityScalesOpticalDepthAndInvalidFadeIsNotAHiddenFallback() {
        assertEquals(0.1f, RingworldAirFog.density(0.1f, 1.0, false), 0.0f);
        assertEquals(0.025f, RingworldAirFog.density(0.1f, 0.25, false), 0.0f);
        assertEquals(0.05f, RingworldAirFog.density(0.1f, 0.25, true), 0.0f);
        assertEquals(0.0f, RingworldAirFog.density(0.1f, 0.0, true), 0.0f);
        assertThrows(IllegalArgumentException.class, () -> RingworldAirFog.linearRange(0.0f, 100.0f, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RingworldAirFog.density(0.1f, -0.1, false));
    }
}
