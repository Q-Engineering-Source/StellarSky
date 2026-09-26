package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Headless proof tests for the weighted four-tier cloud DDA budget. */
public class CloudRayBudgetTest {
    @Test
    public void defaultFourTierPlanAtMinimumCellsFitsWithoutShorteningAnyFixedDistance() {
        double effective = CloudRayBudget.effectiveHorizon(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, 4.0D);
        assertEquals(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, effective, 0.0D);
        assertEquals(expectedProof(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, 4.0D),
                CloudRayBudget.requiredDdaSteps(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, 4.0D));
        assertTrue(CloudRayBudget.requiredDdaSteps(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, 4.0D)
                <= CloudRayBudget.MAX_STEPS);
    }

    @Test
    public void preparePreservesRequestedEndAndCarriesTheAggregateProof() {
        CloudRayBudget.PreparedHorizon prepared = CloudRayBudget.prepare(CloudRayBudget.DEFAULT_REQUESTED_HORIZON,
                new CloudGeometrySettings(4.0D, 1.0D, 0));
        assertEquals(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, prepared.requestedHorizon());
        assertEquals((double) CloudRayBudget.DEFAULT_REQUESTED_HORIZON, prepared.effectiveHorizon(), 0.0D);
        assertEquals(CloudRayBudget.MAX_STEPS, prepared.maximumDdaSteps());
        assertEquals(CloudRayBudget.requiredDdaSteps(CloudRayBudget.DEFAULT_REQUESTED_HORIZON, 4.0D),
                prepared.proofSteps());
    }

    @Test
    public void unsafeCustomFinalThreeDEndFailsRatherThanSilentlyShrinkingTheTiers() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudRayBudget.effectiveHorizon(CloudRayBudget.MAX_REQUESTED_HORIZON, 4.0D));
        assertThrows(IllegalArgumentException.class, () -> CloudRayBudget.prepare(CloudRayBudget.MAX_REQUESTED_HORIZON,
                new CloudGeometrySettings(4.0D, 1.0D, 0)));
    }

    @Test
    public void customFinalThreeDEndMustReachTheLowTier() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudRayBudget.effectiveHorizon(CloudRayBudget.MIN_REQUESTED_HORIZON - 1, 4.0D));
        assertThrows(IllegalArgumentException.class,
                () -> CloudRayBudget.effectiveHorizon(CloudRayBudget.MIN_REQUESTED_HORIZON, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> CloudRayBudget.effectiveHorizon(CloudRayBudget.MIN_REQUESTED_HORIZON, 0.0D));
    }

    private static int expectedProof(int horizon, double baseCellXZ) {
        double weightedCells = CloudLodLayout.FINE_3D_END_DISTANCE
                / (baseCellXZ * CloudLodLayout.LOD0_FINE_3D.xzScale())
                + (CloudLodLayout.MID_3D_END_DISTANCE - CloudLodLayout.FINE_3D_END_DISTANCE)
                / (baseCellXZ * CloudLodLayout.LOD1_MID_3D.xzScale())
                + (CloudLodLayout.LOW_3D_END_DISTANCE - CloudLodLayout.MID_3D_END_DISTANCE)
                / (baseCellXZ * CloudLodLayout.LOD2_LOW_3D.xzScale())
                + (horizon - CloudLodLayout.LOW_3D_END_DISTANCE)
                / (baseCellXZ * CloudLodLayout.LOD3_VERY_LOW_3D.xzScale());
        return (int) StrictMath.ceil(weightedCells * StrictMath.sqrt(2.0D))
                + CloudRayBudget.TOTAL_ALLOWANCE;
    }
}
