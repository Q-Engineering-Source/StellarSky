package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import stellarium.world.ring.RingworldSunshade;

/** Scalar production formulas used to keep the shader's finite composition numerically stable. */
public class RingworldSpatialAirMathTest {
    @Test
    public void periodMeanMatchesActualSunshadeSamplesWithoutShorteningTheRay() {
        RingworldSunshade shade = new RingworldSunshade(400.0, 300.0, 1728000L, 0.0, 0.0, 25.0);
        double sum = 0.0;
        int samples = 16000;
        for (int i = 0; i < samples; i++)
            sum += shade.transmittance(0L, (i + 0.5) * 400.0 / samples, 0.0);
        assertEquals(sum / samples, RingworldSpatialAirMath.periodMeanTransmission(400.0, 100.0, 25.0), 1.0e-8);
        assertEquals(0.25, RingworldSpatialAirMath.periodMeanTransmission(400.0, 100.0, 0.0), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> RingworldSpatialAirMath.periodMeanTransmission(400.0, 100.0, 51.0));
    }

    @Test
    public void verticalMassMatchesTheSmoothFadeEndpointsAndIsFinite() {
        assertEquals(32.0, RingworldSpatialAirMath.verticalSunMass(192.0, 192.0, 256.0), 1.0e-12);
        assertEquals(0.0, RingworldSpatialAirMath.verticalSunMass(256.0, 192.0, 256.0), 0.0);
        assertEquals(96.0, RingworldSpatialAirMath.verticalSunMass(128.0, 192.0, 256.0), 1.0e-12);
    }

    @Test
    public void stableSegmentAverageAndShortPeriodResolutionThresholdAreFinite() {
        assertEquals(1.0, RingworldSpatialAirMath.attenuationAverage(1.0e-10), 1.0e-10);
        assertTrue(RingworldSpatialAirMath.henyeyGreenstein(0.0, 0.35) > 0.0);
        assertEquals(25.0, RingworldSpatialAirMath.clampDistanceBudget(65_536.0, 400.0, 100.0), 0.0);
		assertEquals(1.0, RingworldSpatialAirMath.clampDistanceBudget(65_536.0, 400.0, 396.0), 0.0);
        assertEquals(65_536.0, RingworldSpatialAirMath.clampDistanceBudget(65_536.0, 400.0, 0.0), 0.0);
    }

    @Test
    public void productionUniformCalibrationIsBoundedAndFitsTheFixedPartitionBudget() {
        RingworldSpatialAirMath.OpticalCoefficients optics = RingworldSpatialAirMath.displayLinearOptics();
        assertTrue(optics.sigmaSRed() <= optics.sigmaTRed());
        assertTrue(optics.sigmaSGreen() <= optics.sigmaTGreen());
        assertTrue(optics.sigmaSBlue() <= optics.sigmaTBlue());
		assertEquals(5.0, optics.sunRed(), 0.0);
		assertEquals(10.0, optics.sunGreen(), 0.0);
		assertEquals(20.0, optics.sunBlue(), 0.0);
        assertEquals(8, RingworldSpatialAirMath.MAX_PARTITIONS);
        assertEquals(2, RingworldSpatialAirMath.SOURCE_EVALUATIONS_PER_PARTITION);
        assertEquals(16, RingworldSpatialAirMath.MAX_SOURCE_EVALUATIONS);
    }
}
