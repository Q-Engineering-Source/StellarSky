package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure contracts for deterministic material-cell outages and optional local pulse brightness. */
public class RingworldBoardDotOutageTest {
    private static final double PITCH = 256.0;

    @Test
    public void zeroProbabilityLeavesEveryDotLitAndOneTurnsEveryDotOff() {
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                RingworldBoardDotOutage.DotId dot = RingworldBoardDotOutage.dotId(x * PITCH, z * PITCH,
                        PITCH, x - z, RingworldBoardDotOutage.FACE_UNDERSIDE);
                assertTrue(RingworldBoardDotOutage.isLit(dot, 0.0f, 0));
                assertFalse(RingworldBoardDotOutage.isLit(dot, 1.0f, 0));
            }
        }
    }

    @Test
    public void seedAndMaterialDotIdAreDeterministicWhileAdjacentPanelsCanDiffer() {
        RingworldBoardDotOutage.DotId first = RingworldBoardDotOutage.dotId(-512.0, 768.0, PITCH, -14,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId sameFirst = RingworldBoardDotOutage.dotId(-512.0, 768.0, PITCH, -14,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId nextPanel = RingworldBoardDotOutage.dotId(-512.0, 768.0, PITCH, -13,
                RingworldBoardDotOutage.FACE_UNDERSIDE);

        assertEquals(first, sameFirst);
        assertEquals(RingworldBoardDotOutage.sample(first, 17), RingworldBoardDotOutage.sample(sameFirst, 17), 0.0f);
        assertNotEquals(first, nextPanel);
        assertNotEquals(RingworldBoardDotOutage.sample(first, 17), RingworldBoardDotOutage.sample(first, 18));
    }

    @Test
    public void quarterProbabilityProducesAReasonableLargeSampleOutageRate() {
        int total = 0;
        int off = 0;
        for (int panel = -32; panel < 32; panel++) {
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 8; z++) {
                    RingworldBoardDotOutage.DotId dot = RingworldBoardDotOutage.dotId(x * PITCH, z * PITCH,
                            PITCH, panel, RingworldBoardDotOutage.FACE_UNDERSIDE);
                    total++;
                    if (!RingworldBoardDotOutage.isLit(dot, 0.25f, 0)) off++;
                }
            }
        }
        double outageRate = off / (double) total;
        assertTrue("outageRate=" + outageRate, outageRate > 0.22 && outageRate < 0.28);
    }

    @Test
    public void negativeCoordinatesAndCycleWrapKeepTheSameMaterialCellId() {
        double cycle = PITCH * RingworldBoardDotOutage.CELL_PERIOD;
        RingworldBoardDotOutage.DotId negative = RingworldBoardDotOutage.dotId(-1.0, -PITCH - 1.0, PITCH, -1,
                RingworldBoardDotOutage.FACE_STRIP_SIDE);
        RingworldBoardDotOutage.DotId wrapped = RingworldBoardDotOutage.dotId(cycle - 1.0, cycle - PITCH - 1.0,
                PITCH, RingworldBoardDotOutage.CELL_PERIOD - 1L, RingworldBoardDotOutage.FACE_STRIP_SIDE);
        assertEquals(negative, wrapped);
    }

    @Test
    public void nearestCenterIdentityKeepsAllFourQuadrantsOfABoundaryCenteredDotTogether() {
        double epsilon = 0.01;
        RingworldBoardDotOutage.DotId lowerLeft = RingworldBoardDotOutage.dotId(-epsilon, -epsilon, PITCH, 4,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId lowerRight = RingworldBoardDotOutage.dotId(epsilon, -epsilon, PITCH, 4,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId upperLeft = RingworldBoardDotOutage.dotId(-epsilon, epsilon, PITCH, 4,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId upperRight = RingworldBoardDotOutage.dotId(epsilon, epsilon, PITCH, 4,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        assertEquals(lowerLeft, lowerRight);
        assertEquals(lowerLeft, upperLeft);
        assertEquals(lowerLeft, upperRight);
        assertEquals(RingworldBoardDotOutage.sample(lowerLeft, 7), RingworldBoardDotOutage.sample(upperRight, 7),
                0.0f);
    }

    @Test
    public void sideRowsHashOnlyTheirActualHorizontalDotAxis() {
        double epsilon = 0.01;
        RingworldBoardDotOutage.DotId stripBeforeCenter = RingworldBoardDotOutage.stripSideDotId(-epsilon, PITCH, 4);
        RingworldBoardDotOutage.DotId stripAfterCenter = RingworldBoardDotOutage.stripSideDotId(epsilon, PITCH, 4);
        RingworldBoardDotOutage.DotId panelBeforeCenter = RingworldBoardDotOutage.panelSideDotId(-epsilon, PITCH, 4);
        RingworldBoardDotOutage.DotId panelAfterCenter = RingworldBoardDotOutage.panelSideDotId(epsilon, PITCH, 4);
        assertEquals(stripBeforeCenter, stripAfterCenter);
        assertEquals(panelBeforeCenter, panelAfterCenter);
        assertEquals(0, stripBeforeCenter.cellZ());
        assertEquals(0, panelBeforeCenter.cellZ());
    }

    @Test
    public void opposingPhysicalWallFacesCarryIndependentStableFaceSalts() {
        RingworldBoardDotOutage.DotId negativeStrip = RingworldBoardDotOutage.stripSideDotId(0.0, PITCH, 4, false);
        RingworldBoardDotOutage.DotId positiveStrip = RingworldBoardDotOutage.stripSideDotId(0.0, PITCH, 4, true);
        RingworldBoardDotOutage.DotId leadingPanel = RingworldBoardDotOutage.panelSideDotId(0.0, PITCH, 4, false);
        RingworldBoardDotOutage.DotId trailingPanel = RingworldBoardDotOutage.panelSideDotId(0.0, PITCH, 4, true);
        assertNotEquals(negativeStrip, positiveStrip);
        assertNotEquals(leadingPanel, trailingPanel);
        assertNotEquals(RingworldBoardDotOutage.sample(negativeStrip, 0),
                RingworldBoardDotOutage.sample(positiveStrip, 0));
    }

    @Test
    public void faceSaltKeepsUndersideAndSideOutagePatternsIndependent() {
        RingworldBoardDotOutage.DotId underside = RingworldBoardDotOutage.dotId(0.0, 0.0, PITCH, 2,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId side = RingworldBoardDotOutage.panelSideDotId(0.0, PITCH, 2);
        assertNotEquals(underside, side);
        assertNotEquals(RingworldBoardDotOutage.sample(underside, 0), RingworldBoardDotOutage.sample(side, 0));
    }

    @Test
    public void disabledPulseIsOneAndEnabledPulseIsBoundedWithoutRevivingAnOffDot() {
        assertEquals(1.0f, RingworldBoardDotOutage.pulseBrightness(false, Long.MAX_VALUE, 1.0f, 10.0f), 0.0f);
        for (long tick = 0; tick < 400; tick += 7) {
            float brightness = RingworldBoardDotOutage.pulseBrightness(true, tick, 0.5f, 10.0f);
            assertTrue(brightness >= 0.65f && brightness <= 1.0f);
        }
        RingworldBoardDotOutage.DotId dot = RingworldBoardDotOutage.dotId(0.0, 0.0, PITCH, 0,
                RingworldBoardDotOutage.FACE_UNDERSIDE);
        assertFalse("A pulse is only a brightness multiplier after outage selection",
                RingworldBoardDotOutage.isLit(dot, 1.0f, 123));
    }
}
