package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class RingworldThinAtmosphereTest {

    @Test
    public void unconfiguredCompatibilityProfileUsesTheNewZeroToFiveTwelveDefault() {
        RingworldAirProfile profile = RingworldThinAtmosphere.defaultProfile();
        assertEquals(0.0, profile.lowerY(), 0.0);
        assertEquals(384.0, profile.fullDensityTopY(), 0.0);
        assertEquals(512.0, profile.upperY(), 0.0);
        assertEquals(0.5, profile.densityAt(448.0, 0.0), 1.0e-12);
    }

    @Test
    public void playableStripKeepsFullAtmosphereFromItsLowerFaceThroughTheConfiguredFadeStart() {
        assertEquals(0.0, RingworldThinAtmosphere.fadeAt(-120.0, -8_192.0, 192.0), 0.0);
        assertEquals(1.0, RingworldThinAtmosphere.fadeAt(0.0, -8_192.0, 192.0), 0.0);
        assertEquals(1.0, RingworldThinAtmosphere.fadeAt(192.0, 8_191.999_999, 192.0), 0.0);
    }

    @Test
    public void profileSupportsAConfiguredVacuumFloorAndTopWithoutChangingTheFiniteStrip() {
        RingworldAirProfile profile = new RingworldAirProfile(32.0, 200.0, 300.0);
        assertEquals(0.0, profile.densityAt(31.999_999, 0.0), 0.0);
        assertEquals(1.0, profile.densityAt(32.0, 0.0), 0.0);
        assertEquals(1.0, profile.densityAt(200.0, 0.0), 0.0);
        assertEquals(0.5, profile.densityAt(250.0, 0.0), 1.0e-12);
        assertEquals(0.0, profile.densityAt(300.0, 0.0), 0.0);
        assertEquals(0.0, profile.densityAt(64.0, 8_192.0), 0.0);
    }

    @Test
    public void wallsExteriorAndThePhysicalTopHaveNoAtmosphere() {
        assertEquals(0.0, RingworldThinAtmosphere.fadeAt(64.0, -8_192.000_001, 192.0), 0.0);
        assertEquals(0.0, RingworldThinAtmosphere.fadeAt(64.0, 8_192.0, 192.0), 0.0);
        assertEquals(0.0, RingworldThinAtmosphere.fadeAt(256.0, 0.0, 192.0), 0.0);
    }

    @Test
    public void fadeUsesTheSpecifiedSmoothstepCurveBetweenPhysicalYCoordinates() {
        assertEquals(0.84375, RingworldThinAtmosphere.fadeAt(208.0, 0.0, 192.0), 1.0e-12);
        assertEquals(0.5, RingworldThinAtmosphere.fadeAt(224.0, 0.0, 192.0), 1.0e-12);
        assertEquals(0.0, RingworldThinAtmosphere.fadeAt(255.999_999, 0.0, 192.0), 1.0e-6);
    }

    @Test
    public void rejectsNonFiniteCoordinatesAndInvalidFadeStarts() {
        assertThrows(IllegalArgumentException.class,
                () -> RingworldThinAtmosphere.fadeAt(Double.NaN, 0.0, 192.0));
        assertThrows(IllegalArgumentException.class,
                () -> RingworldThinAtmosphere.fadeAt(0.0, Double.POSITIVE_INFINITY, 192.0));
        assertThrows(IllegalArgumentException.class,
                () -> RingworldThinAtmosphere.fadeAt(0.0, 0.0, Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> RingworldThinAtmosphere.fadeAt(0.0, 0.0, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> RingworldThinAtmosphere.fadeAt(0.0, 0.0, 256.0));
    }
}
