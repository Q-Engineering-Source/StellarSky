package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudLodTransitionTest {
    @Test
    public void defaultBandsKeepTheFixedCentersAndProvideRequestedOverlaps() {
        CloudLodTransition t = CloudLodTransition.DEFAULT;
        double[] lower = {448, 1856, 7680}, upper = {576, 2240, 8704};
        for (int b = 0; b < 3; b++) {
            assertEquals(lower[b], t.lowerBoundary(b), 0.0);
            assertEquals(upper[b], t.upperBoundary(b), 0.0);
            assertEquals((lower[b] + upper[b]) / 2.0, t.boundary(b, 0.5), 0.0);
        }
    }

    @Test
    public void pixelRanksAreStableUniqueBalancedAndRepeatInFramebufferPixelSpace() {
        boolean[] seen = new boolean[64];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            double rank = CloudLodTransition.pixelRank(x, y);
            assertFalse(seen[(int) (rank * 64)]);
            seen[(int) (rank * 64)] = true;
            assertEquals(rank, CloudLodTransition.pixelRank(x + 1920, y + 1080), 0.0);
            assertEquals(rank, CloudLodTransition.pixelRank(x - 8, y - 8), 0.0);
        }
        for (boolean rank : seen) assertTrue(rank);
    }

    @Test
    public void complementaryOwnershipFollowsSmoothstepNotLinearOrAnAbruptCircle() {
        CloudLodTransition t = CloudLodTransition.DEFAULT;
        for (int b = 0; b < 3; b++) for (int step = 0; step <= 100; step++) {
            double progress = step / 100.0;
            double distance = t.lowerBoundary(b) + t.width(b) * progress;
            int fine = 0, coarse = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                double split = t.boundary(b, CloudLodTransition.pixelRank(x, y));
                if (distance < split) fine++;
                if (distance >= split) coarse++;
            }
            assertEquals(64, fine + coarse);
            assertEquals(progress * progress * (3.0 - 2.0 * progress), coarse / 64.0, 0.5 / 64.0 + 1e-12);
        }
    }

    @Test
    public void maximumBandsStayOrderedAndZeroWidthsRestoreTheOldDistances() {
        CloudLodTransition t = new CloudLodTransition(256, 768, 2048);
        assertTrue(t.lowerBoundary(0) > 0);
        assertTrue(t.upperBoundary(0) < t.lowerBoundary(1));
        assertTrue(t.upperBoundary(1) < t.lowerBoundary(2));
        assertTrue(t.upperBoundary(2) < 16384);
        for (int b = 0; b < 3; b++) for (int r = 0; r <= 64; r++) {
            assertEquals(CloudLodTransition.HARD.lowerBoundary(b), CloudLodTransition.HARD.boundary(b, r / 64.0), 0.0);
        }
        assertThrows(IllegalArgumentException.class, () -> new CloudLodTransition(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CloudLodTransition(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CloudLodTransition(0, 0, 2049));
    }

    @Test
    public void runtimeBudgetCoversEveryPixelIncludingTheWidestSupportedTransition() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(12, 4, 64);
        for (CloudLodTransition t : new CloudLodTransition[]{CloudLodTransition.HARD,
                CloudLodTransition.DEFAULT, new CloudLodTransition(256, 768, 2048)}) {
            int proof = CloudRayBudget.prepare(16384, geometry, t).proofSteps();
            for (int r = 0; r <= 64; r++) {
                double f = t.boundary(0, r / 64.0), m = t.boundary(1, r / 64.0), l = t.boundary(2, r / 64.0);
                int crossings = (int) Math.ceil(Math.sqrt(2) * (f + (m-f)/2 + (l-m)/4 + (16384-l)/8) / 12)
                        + CloudRayBudget.TOTAL_ALLOWANCE;
                assertTrue(crossings <= proof);
            }
            assertTrue(proof <= CloudRayBudget.MAX_STEPS);
        }
        assertTrue(CloudRayBudget.prepare(16384, geometry, CloudLodTransition.DEFAULT).proofSteps()
                > CloudRayBudget.prepare(16384, geometry).proofSteps());
    }
}
