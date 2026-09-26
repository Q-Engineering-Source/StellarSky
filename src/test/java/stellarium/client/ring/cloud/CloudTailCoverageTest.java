package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import java.nio.FloatBuffer;

public class CloudTailCoverageTest {
    @Test
    public void ordinaryGroundViewKeepsCachedCoverage() {
        CloudTailCoverage.Coverage coverage = CloudTailCoverage.prepare(224, 256, 64,
                0.001, -12_500_000, 12_600_000, 49152, 0.45);
        assertFalse(coverage.fallbackNeeded());
        assertEquals(176_000, coverage.requiredDistance(), 1e-8);
    }

    @Test
    public void highAltitudeZoomPreparesSafeFallbackInsteadOfCrashingFrame() {
        // Captured crash altitude, with an explicit narrow-pixel fixture rather
        // than inventing the original unlogged effective OptiFine FOV.
        CloudTailCoverage.Coverage coverage = CloudTailCoverage.prepare(224, 256, 4965.65,
                0.0001, -12_500_000, 12_600_000, 49152, 0.45);
        assertTrue(coverage.fallbackNeeded());
        assertEquals(47_256_500, coverage.requiredDistance(), 1e-7);
        assertEquals(8_388_608, coverage.cachedDistance(), 0);
    }

    @Test
    public void actualPublishedPageClearanceWinsOverNominalMaximum() {
        CloudTailCoverage.Coverage coverage = CloudTailCoverage.prepare(224, 256, 4965.65,
                0.001, -4_000_000, 12_600_000, 49152, 0.45);
        assertTrue(coverage.fallbackNeeded());
        assertEquals(4_000_000, coverage.cachedDistance(), 0);
    }

    @Test
    public void transitionAndProxyAreBoundedAndEmptyCloudSettingStaysEmpty() {
        CloudTailCoverage.Coverage zoom = CloudTailCoverage.prepare(224, 256, 5000,
                .0001, -12_500_000, 12_600_000, 49152, .45);
        assertEquals(49152, zoom.transitionWidth(), 0);
        assertEquals(24, zoom.proxyRanks());
        CloudTailCoverage.Coverage huge = CloudTailCoverage.prepare(224, 256, 1e12,
                1.0, -12_500_000, 12_600_000, 49152, 0);
        assertEquals(8 * 49152, huge.transitionWidth(), 0);
        assertEquals(0, huge.proxyRanks());
    }

    @Test
    public void emptySlabDoesNotDemandTailAndOpticalEyeMayBeOutsideTheCache() {
        assertFalse(CloudTailCoverage.prepare(300, 256, 5000, .0001,
                -12_500_000, 12_600_000, 49152, .45).fallbackNeeded());
        CloudTailCoverage.Coverage outside = CloudTailCoverage.prepare(224, 256, 5000,
                .0001, 5, 1000, 49152, .45);
        assertEquals(0, outside.cachedDistance(), 0);
        assertTrue(outside.fallbackNeeded());
    }

    @Test
    public void extremeFiniteZoomSaturatesDiagnosticDemandWithoutCrashing() {
        CloudTailCoverage.Coverage coverage = CloudTailCoverage.prepare(224, 256, 1e20,
                Double.MIN_VALUE, -12_500_000, 12_600_000, 49152, .45);
        assertTrue(coverage.fallbackNeeded());
        assertEquals(Double.MAX_VALUE, coverage.requiredDistance(), 0);
        assertTrue(Double.isFinite(coverage.transitionWidth()));
    }

    @Test
    public void invalidInputsStillFailInsteadOfBeingCalledCapacityExhaustion() {
        assertThrows(IllegalArgumentException.class, () -> CloudTailCoverage.prepare(224, 256,
                Double.NaN, .001, -1, 1, 49152, .45));
        assertThrows(IllegalArgumentException.class, () -> CloudTailCoverage.prepare(224, 256,
                5000, 0, -1, 1, 49152, .45));
        assertThrows(IllegalArgumentException.class, () -> CloudTailCoverage.prepare(224, 256,
                5000, .001, 1, -1, 49152, .45));
    }

    @Test
    public void terminalHandoffReferenceDistinguishesKnownEmptyFromUnavailable() {
        CloudTailCoverage.Coverage plan = CloudTailCoverage.prepare(224, 256, 5000,
                .0001, -12_500_000, 12_600_000, 49152, .45);
        assertEquals(0, plan.referenceAcceptedRanks(true, false, plan.transitionWidth()));
        assertEquals(64, plan.referenceAcceptedRanks(true, true, plan.transitionWidth()));
        assertEquals(12, plan.referenceAcceptedRanks(true, false, plan.transitionWidth() * .5));
        assertEquals(44, plan.referenceAcceptedRanks(true, true, plan.transitionWidth() * .5));
        int accepted = plan.referenceAcceptedRanks(false, false, 0);
        int hits = 0;
        for (int rank = 0; rank < 64; rank++) if (rank < accepted) hits++;
        assertEquals(24, hits);
        CloudTailCoverage.Coverage clear = CloudTailCoverage.prepare(224, 256, 5000,
                .0001, -12_500_000, 12_600_000, 49152, 0);
        assertEquals(0, clear.referenceAcceptedRanks(false, false, 0));
    }

    @Test
    public void frozenUniformPayloadIsBitIdenticalForBothConsumers() {
        CloudTailCoverage.Coverage plan = CloudTailCoverage.prepare(224, 256, 5000,
                .0001, -12_500_000, 12_600_000, 49152, .45);
        FloatBuffer horizon = FloatBuffer.allocate(3), air = FloatBuffer.allocate(3);
        plan.copyUniforms(horizon); plan.copyUniforms(air);
        for (int i = 0; i < 3; i++) assertEquals(Float.floatToIntBits(horizon.get(i)), Float.floatToIntBits(air.get(i)));
        assertEquals(49152.0f, horizon.get(0), 0.0f);
        assertEquals(1.0f, horizon.get(1), 0.0f);
        assertEquals(24.0f, horizon.get(2), 0.0f);
    }

    @Test
    public void terminalReferencePreservesActualFarHitAndPhysicalStrip() {
        double t = CloudTailCoverage.referenceTerminalHit(5000, 0, -.000476, 0,
                224, 256, -8192, 8192, .0001, true);
        assertEquals(10_000_000, t, 1e-6);
        assertTrue(t > CloudLodLayout.MAX_CACHED_TAIL_DISTANCE);
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(5000, 8192, -.000476, 0,
                224, 256, -8192, 8192, .0001, true), 0);
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(5000, -8193, -.000476, 0,
                224, 256, -8192, 8192, .0001, true), 0);
        assertTrue(CloudTailCoverage.referenceTerminalHit(5000, -8191, -.000476, 0,
                224, 256, -8192, 8192, .0001, true) > 0);
    }

    @Test
    public void terminalReferenceOmitsSubpixelsParallelAndNumericallyUnsupportedRays() {
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(5000, 0, -.00005, 0,
                224, 256, -8192, 8192, .0001, true), 0);
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(5000, 0, 0, 1,
                224, 256, -8192, 8192, .0001, true), 0);
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(240, 0, 0, 1,
                224, 256, -8192, 8192, .0001, true), 0);
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(5000, 0, -1e-31, 0,
                224, 256, -8192, 8192, 1e-35, true), 0);
        assertEquals(-1, CloudTailCoverage.referenceTerminalHit(5000, 0, .000476, 0,
                224, 256, -8192, 8192, .0001, true), 0);
    }
}
