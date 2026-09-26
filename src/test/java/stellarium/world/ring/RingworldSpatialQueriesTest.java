package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Production-level checks for the frozen spatial-air and board query contract. */
public class RingworldSpatialQueriesTest {
    private static final RingworldAirProfile AIR = new RingworldAirProfile(0.0, 192.0, 256.0);

    @Test
    public void densityAndDirectTransmissionRespectPhysicalHeightAndGaps() {
        RingworldSpatialQueries queries = queries(0L);
        assertEquals(1.0, queries.densityAt(64.0, 0.0), 0.0);
        assertEquals(0.0, queries.densityAt(-0.001, 0.0), 0.0);
        assertEquals(0.0, queries.densityAt(64.0, 8_192.0), 0.0);

        assertEquals(1.0, queries.directTransmissionAt(0.0, 110.0, 0.0), 0.0);
        assertEquals(0.0, queries.directTransmissionAt(0.0, 105.0, 0.0), 0.0);
        assertEquals(1.0, queries.directTransmissionAt(30.0, 105.0, 0.0), 0.0);
        assertEquals(0.0, queries.directTransmissionAt(0.0, 99.0, 0.0), 0.0);
        assertEquals(1.0, queries.directTransmissionAt(30.0, 99.0, 0.0), 0.0);
    }

    @Test
    public void clippingHandlesExteriorObserversParallelAndBoundaryRaysWithoutInfinity() {
        RingworldSpatialQueries queries = queries(0L);
        RingworldSpatialQueries.Ray rising = ray(0.0, -1.0, 0.0, 0.0, 1.0, 0.0);
        RingworldSpatialQueries.RayInterval interval = queries.clipAirRay(rising, 300.0).orElseThrow(AssertionError::new);
        assertEquals(1.0, interval.entryDistance(), 0.0);
        assertEquals(257.0, interval.exitDistance(), 0.0);

        assertFalse(queries.clipAirRay(ray(0.0, 64.0, 8_192.0, 1.0, 0.0, 0.0), 10.0).isPresent());
        assertFalse(queries.clipAirRay(ray(0.0, 256.0, 0.0, 1.0, 0.0, 0.0), 10.0).isPresent());
        RingworldSpatialQueries.RayInterval grazing = queries.clipAirRay(
                ray(0.0, 64.0, -8_192.0, 1.0, 0.0, 0.0), 10.0).orElseThrow(AssertionError::new);
        assertEquals(0.0, grazing.entryDistance(), 0.0);
        assertEquals(10.0, grazing.exitDistance(), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> queries.clipAirRay(ray(0.0, 64.0, 0.0, 1.0, 0.0, 0.0), Double.POSITIVE_INFINITY));
    }

    @Test
    public void firstBoardHitUsesExactPeriodicMaterialAtFarCoordinatesAndPhaseChanges() {
        RingworldSpatialQueries initial = queries(0L);
        RingworldSpatialQueries.Ray verticalCenter = ray(0.0, 200.0, 0.0, 0.0, -1.0, 0.0);
        assertEquals(90.0, initial.firstBoardHit(verticalCenter, 150.0).orElseThrow(AssertionError::new).distance(), 0.0);
        assertFalse(initial.firstBoardHit(ray(30.0, 200.0, 0.0, 0.0, -1.0, 0.0), 150.0).isPresent());
        assertEquals(10.0, initial.firstBoardHit(ray(30.0, 105.0, 0.0, -1.0, 0.0, 0.0), 60.0)
                .orElseThrow(AssertionError::new).distance(), 0.0);
        assertFalse(initial.firstBoardHit(ray(0.0, 105.0, 8_192.0, 1.0, 0.0, 0.0), 60.0).isPresent());
        assertEquals(90.0, initial.firstBoardHit(ray(10_000_000.0, 200.0, 0.0, 0.0, -1.0, 0.0), 150.0)
                .orElseThrow(AssertionError::new).distance(), 0.0);

        RingworldSpatialQueries moved = queries(25L);
        assertFalse(moved.firstBoardHit(verticalCenter, 150.0).isPresent());
        assertEquals(90.0, moved.firstBoardHit(ray(25.0, 200.0, 0.0, 0.0, -1.0, 0.0), 150.0)
                .orElseThrow(AssertionError::new).distance(), 0.0);
    }

    @Test
    public void queryConstructionRejectsAbsentPhaseAndNonNormalizedRays() {
        RingworldSunshade shade = shade();
        assertThrows(NullPointerException.class,
                () -> new RingworldSpatialQueries(AIR, shade, null, 100.0, 10.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSpatialQueries.Ray(0.0, 0.0, 0.0, 2.0, 0.0, 0.0));
    }

    private static RingworldSpatialQueries queries(long phaseTime) {
        RingworldSunshade shade = shade();
        return new RingworldSpatialQueries(AIR, shade, shade.phase(phaseTime, phaseTime, 1.0), 100.0, 10.0);
    }

    private static RingworldSunshade shade() {
        return new RingworldSunshade(100.0, 40.0, 100L, 0.0, 0.0, 0.0);
    }

    private static RingworldSpatialQueries.Ray ray(double x, double y, double z, double dx, double dy, double dz) {
        return new RingworldSpatialQueries.Ray(x, y, z, dx, dy, dz);
    }
}
