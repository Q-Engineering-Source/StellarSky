package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class TerrainTileCacheTest {
    private final TerrainTileKey key = new TerrainTileKey(1, 0, -1, 0);
    private static TerrainColumnState[] columns(TerrainColumnState state) {
        var result = new TerrainColumnState[4096];
        Arrays.fill(result, state);
        return result;
    }

    @Test public void negativeCoordinatesUseFloorDivisionAndLongBounds() {
        assertEquals(key, TerrainTileKey.atBlock(1, 0, -1, 0));
        assertEquals(-64, key.minBlockX());
        assertEquals(-128, TerrainTileKey.atBlock(1, 1, -1, 0).minBlockX());
        assertEquals(64L * Integer.MAX_VALUE, new TerrainTileKey(1, 0, Integer.MAX_VALUE, 0).minBlockX());
    }

    @Test public void realAirIsRealAndPartialCoverageDoesNotClaimNeighbours() {
        var cache = new TerrainTileCache(1, 2);
        var input = columns(TerrainColumnState.UNKNOWN);
        input[0] = TerrainColumnState.REAL_AIR;
        input[1] = TerrainColumnState.REAL_SOLID;
        assertTrue(cache.publish(cache.begin(key).orElseThrow(), input));
        var snapshot = cache.get(key).orElseThrow();
        assertTrue(snapshot.column(0).isReal());
        assertEquals(TerrainColumnState.REAL_SOLID, snapshot.column(1));
        assertEquals(TerrainColumnState.UNKNOWN, snapshot.column(2));
        input[0] = TerrainColumnState.UNKNOWN;
        assertEquals(TerrainColumnState.REAL_AIR, snapshot.column(0));
    }

    @Test public void boundaryInferenceAndUnknownCannotEraseRealButNewRealCanReplaceIt() {
        var cache = new TerrainTileCache(1, 1);
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.REAL_SOLID));
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.BOUNDARY_EMPTY));
        assertEquals(TerrainColumnState.REAL_SOLID, cache.get(key).orElseThrow().column(0));
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.UNKNOWN));
        assertEquals(TerrainColumnState.REAL_SOLID, cache.get(key).orElseThrow().column(0));
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.REAL_AIR));
        assertEquals(TerrainColumnState.REAL_AIR, cache.get(key).orElseThrow().column(0));
    }

    @Test public void newerRealOverridesExteriorBoundaryInference() {
        var cache = new TerrainTileCache(1, 1);
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.BOUNDARY_EMPTY));
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.REAL_SOLID));
        assertEquals(TerrainColumnState.REAL_SOLID, cache.get(key).orElseThrow().column(0));
    }

    @Test public void staleCompletionAndDuplicateCompletionCannotOverwriteNewerJob() {
        var cache = new TerrainTileCache(1, 1);
        var old = cache.begin(key).orElseThrow();
        var latest = cache.begin(key).orElseThrow();
        assertFalse(cache.publish(old, columns(TerrainColumnState.REAL_SOLID)));
        assertTrue(cache.publish(latest, columns(TerrainColumnState.REAL_AIR)));
        assertFalse(cache.publish(latest, columns(TerrainColumnState.REAL_SOLID)));
    }

    @Test public void readFailureRetainsLastValidSnapshot() {
        var cache = new TerrainTileCache(1, 1);
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.REAL_SOLID));
        var before = cache.get(key).orElseThrow();
        assertTrue(cache.fail(cache.begin(key).orElseThrow()));
        assertSame(before, cache.get(key).orElseThrow());
    }

    @Test public void fullCacheRefusesNewTileWithoutEvictingKnownCoverage() {
        var cache = new TerrainTileCache(1, 1);
        cache.publish(cache.begin(key).orElseThrow(), columns(TerrainColumnState.REAL_SOLID));
        assertTrue(cache.begin(new TerrainTileKey(1, 0, 0, 0)).isEmpty());
        assertEquals(1, cache.size());
        assertEquals(TerrainColumnState.REAL_SOLID, cache.get(key).orElseThrow().column(0));
    }

    @Test public void releaseAndWorldSwitchInvalidateOutstandingTasks() {
        var cache = new TerrainTileCache(1, 1);
        var old = cache.begin(key).orElseThrow();
        cache.release(key);
        var replacement = cache.begin(key).orElseThrow();
        assertFalse(cache.publish(old, columns(TerrainColumnState.REAL_SOLID)));
        assertTrue(cache.publish(replacement, columns(TerrainColumnState.REAL_AIR)));
        var pending = cache.begin(key).orElseThrow();
        cache.switchWorld(2);
        assertFalse(cache.publish(pending, columns(TerrainColumnState.REAL_SOLID)));
        assertEquals(0, cache.size());
        assertThrows(IllegalArgumentException.class, () -> cache.begin(key));
    }

    @Test public void malformedPayloadFailsWithoutConsumingCurrentTicket() {
        var cache = new TerrainTileCache(1, 1);
        var ticket = cache.begin(key).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> cache.publish(ticket, new TerrainColumnState[1]));
        assertThrows(NullPointerException.class, () -> cache.publish(ticket, new TerrainColumnState[4096]));
        assertTrue(cache.publish(ticket, columns(TerrainColumnState.UNKNOWN)));
    }
}
