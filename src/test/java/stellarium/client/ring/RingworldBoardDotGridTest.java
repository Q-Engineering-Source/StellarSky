package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import stellarium.world.ring.RingworldSunshade;

/** Contracts for the bounded CPU uniforms consumed by the procedural board dots. */
public class RingworldBoardDotGridTest {
    private static final double PITCH = 256.0;

    @Test
    public void leadingAndTrailingNearestEdgeChoicesProduceTheSameGridFrame() {
        RingworldSunshade.CameraRelativeBands leading = bands(75.0, 1);
        RingworldSunshade.CameraRelativeBands trailing = bands(175.0, -1);

        RingworldBoardDotGrid.GridFrame fromLeading = RingworldBoardDotGrid.fromBands(leading, 25.0, 99.0, PITCH);
        RingworldBoardDotGrid.GridFrame fromTrailing = RingworldBoardDotGrid.fromBands(trailing, 25.0, 99.0, PITCH);

        assertEquals(fromLeading, fromTrailing);
    }

    @Test
    public void rigidCameraTranslationPreservesBothWorldAxisPhaseResiduesForTheSamePoint() {
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(bands(-25.0, 1), 125.0, 44.0, PITCH);
        RingworldBoardDotGrid.GridFrame second = RingworldBoardDotGrid.fromBands(bands(-75.0, 1), 175.0, 79.0, PITCH);

        // The fragment has camera-relative hit coordinates, but the two sums
        // below represent the same physical point (212.75, 301.5) in world X/Z.
        assertEquals(positiveModulo(first.observerXPhaseBlocks() + 87.75, PITCH),
                positiveModulo(second.observerXPhaseBlocks() + 37.75, PITCH), 0.0);
        assertEquals(positiveModulo(first.observerZPhaseBlocks() + 257.5, PITCH),
                positiveModulo(second.observerZPhaseBlocks() + 222.5, PITCH), 0.0);
    }

    @Test
    public void fullCoverageStillUsesFrozenObserverWorldResidues() {
        RingworldSunshade.CameraRelativeBands full = new RingworldSunshade.CameraRelativeBands(
                1.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0, RingworldSunshade.BandCoverage.FULL);
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(full, 125.0, 44.0, PITCH);
        RingworldBoardDotGrid.GridFrame second = RingworldBoardDotGrid.fromBands(full, 175.0, 79.0, PITCH);

        assertEquals(positiveModulo(first.observerXPhaseBlocks() + 87.75, PITCH),
                positiveModulo(second.observerXPhaseBlocks() + 37.75, PITCH), 0.0);
        assertEquals(positiveModulo(first.observerZPhaseBlocks() + 257.5, PITCH),
                positiveModulo(second.observerZPhaseBlocks() + 222.5, PITCH), 0.0);
    }

    @Test
    public void physicalPanelStepRetainsSpacingModuloPitchInsteadOfRestartingTheLattice() {
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(bands(-25.0, 1), 125.0, 50.0, PITCH);
        RingworldBoardDotGrid.GridFrame next = RingworldBoardDotGrid.fromBands(bands(-25.0, 1), 425.0, 50.0, PITCH);

        // Board spacing is 300 m, so the next physical panel advances the 256 m
        // lattice by 44 m rather than incorrectly reusing its first-panel phase.
        assertEquals(44.0, first.panelStepXPhaseBlocks(), 0.0);
        assertEquals(positiveModulo(first.nearbyPanelAnchorXPhaseBlocks() + first.panelStepXPhaseBlocks(), PITCH),
                next.nearbyPanelAnchorXPhaseBlocks(), 0.0);
    }

    @Test
    public void largeWorldCoordinatesAreReducedBeforeFloatUniformConversion() {
        RingworldSunshade.CameraRelativeBands large = new RingworldSunshade.CameraRelativeBands(
                1.0, 0.0, 40_176_000.0, 20_000_000.0, 20_176_000.0,
                -19_999_999.75, 1, RingworldSunshade.BandCoverage.PARTIAL);

        RingworldBoardDotGrid.GridFrame frame = RingworldBoardDotGrid.fromBands(
                large, 29_999_999.75, -29_999_999.25, PITCH);

        assertBoundedPhase(frame.observerXPhaseBlocks());
        assertBoundedPhase(frame.observerZPhaseBlocks());
        assertBoundedPhase(frame.nearbyPanelAnchorXPhaseBlocks());
        assertBoundedPhase(frame.nearbyPanelAnchorZPhaseBlocks());
        assertBoundedPhase(frame.panelStepXPhaseBlocks());
        assertBoundedPhase(frame.panelStepZPhaseBlocks());
    }

    @Test
    public void materialCycleAndPanelResidueStayWithTheSameMovingPanelDot() {
        RingworldSunshade.Phase firstPhase = new RingworldSunshade.Phase(0L, 0L, 0.0, 0.0);
        RingworldSunshade.Phase movedPhase = new RingworldSunshade.Phase(0L, 0L, 0.0, 50.0);
        RingworldSunshade.CameraRelativeBands bands = bands(0.0, 1);

        // The physical dot is 33 blocks into panel ordinal 5. Advancing the
        // board by 50 blocks also advances its world and camera coordinates.
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(bands, firstPhase,
                1000.0, 99.0, PITCH);
        RingworldBoardDotGrid.GridFrame moved = RingworldBoardDotGrid.fromBands(bands, movedPhase,
                1040.0, 99.0, PITCH);
        double firstMaterialX = cycleCoordinate(first.nearbyMaterialXCyclePhaseBlocks(), 483.0,
                2.0, first.panelStepXCyclePhaseBlocks());
        double movedMaterialX = cycleCoordinate(moved.nearbyMaterialXCyclePhaseBlocks(), 493.0,
                2.0, moved.panelStepXCyclePhaseBlocks());
        assertEquals(firstMaterialX, movedMaterialX, 0.0);
        assertEquals(first.nearbyPanelResidue() + 2, moved.nearbyPanelResidue() + 2);

        RingworldBoardDotOutage.DotId before = RingworldBoardDotOutage.dotId(firstMaterialX, 99.0, PITCH,
                first.nearbyPanelResidue() + 2L, RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId after = RingworldBoardDotOutage.dotId(movedMaterialX, 99.0, PITCH,
                moved.nearbyPanelResidue() + 2L, RingworldBoardDotOutage.FACE_UNDERSIDE);
        assertEquals(before, after);
        assertEquals(RingworldBoardDotOutage.sample(before, 0), RingworldBoardDotOutage.sample(after, 0), 0.0f);
    }

    @Test
    public void nearbyPeriodicPanelAnchorSwitchDoesNotChangeThePhysicalDotId() {
        RingworldSunshade.Phase phase = new RingworldSunshade.Phase(0L, 0L, 0.0, 0.0);
        RingworldSunshade.CameraRelativeBands bands = bands(0.0, 1);
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(bands, phase,
                1000.0, 99.0, PITCH);
        RingworldBoardDotGrid.GridFrame afterAnchorSwitch = RingworldBoardDotGrid.fromBands(bands, phase,
                1300.0, 99.0, PITCH);

        double firstMaterialX = cycleCoordinate(first.nearbyMaterialXCyclePhaseBlocks(), 483.0,
                2.0, first.panelStepXCyclePhaseBlocks());
        double switchedMaterialX = cycleCoordinate(afterAnchorSwitch.nearbyMaterialXCyclePhaseBlocks(), 183.0,
                1.0, afterAnchorSwitch.panelStepXCyclePhaseBlocks());
        RingworldBoardDotOutage.DotId before = RingworldBoardDotOutage.dotId(firstMaterialX, 99.0, PITCH,
                first.nearbyPanelResidue() + 2L, RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId after = RingworldBoardDotOutage.dotId(switchedMaterialX, 99.0, PITCH,
                afterAnchorSwitch.nearbyPanelResidue() + 1L, RingworldBoardDotOutage.FACE_UNDERSIDE);
        assertEquals(before, after);
    }

    @Test
    public void phaseWrapRetainsTheSameMovingPanelResidueAndOutageIdentity() {
        RingworldSunshade sunshade = new RingworldSunshade(300.0, 100.0, 100L, 0.0, 0.0, 0.0);
        RingworldSunshade.Phase beforeWrap = sunshade.phase(50L, 50L, 1.0);
        RingworldSunshade.Phase afterWrap = sunshade.phase(51L, 51L, 1.0);
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(
                sunshade.cameraRelativeBands(beforeWrap, 1000.0, 0.0), sunshade, beforeWrap,
                1000.0, 0.0, PITCH);
        RingworldBoardDotGrid.GridFrame second = RingworldBoardDotGrid.fromBands(
                sunshade.cameraRelativeBands(afterWrap, 1003.0, 0.0), sunshade, afterWrap,
                1003.0, 0.0, PITCH);
        RingworldBoardDotOutage.DotId before = RingworldBoardDotOutage.dotId(
                cycleCoordinate(first.nearbyMaterialXCyclePhaseBlocks(), 33.0, 0.0,
                        first.panelStepXCyclePhaseBlocks()), 0.0, PITCH,
                first.nearbyPanelResidue(), RingworldBoardDotOutage.FACE_UNDERSIDE);
        RingworldBoardDotOutage.DotId after = RingworldBoardDotOutage.dotId(
                cycleCoordinate(second.nearbyMaterialXCyclePhaseBlocks(), 33.0, 0.0,
                        second.panelStepXCyclePhaseBlocks()), 0.0, PITCH,
                second.nearbyPanelResidue(), RingworldBoardDotOutage.FACE_UNDERSIDE);
        assertEquals(before, after);
        assertEquals(RingworldBoardDotOutage.sample(before, 0), RingworldBoardDotOutage.sample(after, 0), 0.0f);
    }

    @Test
    public void fortyFiveDegreeTangentPhaseIsStableAcrossReanchorWithNegativeCoordinates() {
        RingworldSunshade sunshade = new RingworldSunshade(300.0, 100.0, 100L, 0.0, 45.0, 0.0);
        RingworldSunshade.Phase phase = sunshade.phase(10L, 10L, 1.0);
        double firstObserverX = -900.0;
        double firstObserverZ = -600.0;
        float headingX = (float) StrictMath.cos(StrictMath.toRadians(45.0));
        float headingZ = (float) StrictMath.sin(StrictMath.toRadians(45.0));
        double step = headingX * 300.0;
        double secondObserverX = firstObserverX + step;
        double secondObserverZ = firstObserverZ + step;
        RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(
                sunshade.cameraRelativeBands(phase, firstObserverX, firstObserverZ), sunshade, phase,
                firstObserverX, firstObserverZ, PITCH);
        RingworldBoardDotGrid.GridFrame second = RingworldBoardDotGrid.fromBands(
                sunshade.cameraRelativeBands(phase, secondObserverX, secondObserverZ), sunshade, phase,
                secondObserverX, secondObserverZ, PITCH);
        double tangentX = -headingZ;
        double tangentZ = headingX;
        // Same material point: only the heading-parallel observer origin moved.
        double firstScalar = positiveModulo(first.tangentPhaseBlocks() + tangentX * 19.0 + tangentZ * -27.0, PITCH);
        double secondScalar = positiveModulo(second.tangentPhaseBlocks() + tangentX * (19.0 - step)
                + tangentZ * (-27.0 - step), PITCH);
        assertEquals(firstScalar, secondScalar, 1.0e-6);
    }

    @Test
    public void floatQuantizedHeadingKeepsTangentLightIdentityAtBothThirtyMillionCoordinateSigns() {
        RingworldSunshade sunshade = new RingworldSunshade(300.0, 100.0, 100L, 0.0, 45.0, 0.0);
        RingworldSunshade.Phase phase = sunshade.phase(10L, 10L, 1.0);
        float headingX = (float) StrictMath.cos(StrictMath.toRadians(45.0));
        float headingZ = (float) StrictMath.sin(StrictMath.toRadians(45.0));
        float tangentX = -headingZ;
        float tangentZ = headingX;
        float translationX = headingX * 300.0f;
        float translationZ = headingZ * 300.0f;
        for (float coordinate : new float[] {30_000_000.0f, -30_000_000.0f}) {
            double firstObserverX = coordinate;
            double firstObserverZ = -coordinate;
            double secondObserverX = firstObserverX + translationX;
            double secondObserverZ = firstObserverZ + translationZ;
            RingworldBoardDotGrid.GridFrame first = RingworldBoardDotGrid.fromBands(
                    sunshade.cameraRelativeBands(phase, firstObserverX, firstObserverZ), sunshade, phase,
                    firstObserverX, firstObserverZ, PITCH);
            RingworldBoardDotGrid.GridFrame second = RingworldBoardDotGrid.fromBands(
                    sunshade.cameraRelativeBands(phase, secondObserverX, secondObserverZ), sunshade, phase,
                    secondObserverX, secondObserverZ, PITCH);
            float firstHitX = 19.0f;
            float firstHitZ = -27.0f;
            float secondHitX = firstHitX - translationX;
            float secondHitZ = firstHitZ - translationZ;
            float firstScalar = positiveModuloFloat((float) first.tangentPhaseBlocks()
                    + firstHitX * tangentX + firstHitZ * tangentZ, (float) PITCH);
            float secondScalar = positiveModuloFloat((float) second.tangentPhaseBlocks()
                    + secondHitX * tangentX + secondHitZ * tangentZ, (float) PITCH);
            assertEquals(firstScalar, secondScalar, 1.0e-4f);

            float firstCycleScalar = positiveModuloFloat((float) first.tangentCyclePhaseBlocks()
                    + firstHitX * tangentX + firstHitZ * tangentZ,
                    (float) RingworldBoardDotOutage.cycleBlocks(PITCH));
            float secondCycleScalar = positiveModuloFloat((float) second.tangentCyclePhaseBlocks()
                    + secondHitX * tangentX + secondHitZ * tangentZ,
                    (float) RingworldBoardDotOutage.cycleBlocks(PITCH));
            long firstPanelIndex = (long) StrictMath.floor((firstHitX * headingX + firstHitZ * headingZ
                    - (float) first.nearbyLeadingRelativeBlocks()) / 300.0f);
            long secondPanelIndex = (long) StrictMath.floor((secondHitX * headingX + secondHitZ * headingZ
                    - (float) second.nearbyLeadingRelativeBlocks()) / 300.0f);
            RingworldBoardDotOutage.DotId firstId = RingworldBoardDotOutage.panelSideDotId(firstCycleScalar,
                    PITCH, first.nearbyPanelResidue() + firstPanelIndex);
            RingworldBoardDotOutage.DotId secondId = RingworldBoardDotOutage.panelSideDotId(secondCycleScalar,
                    PITCH, second.nearbyPanelResidue() + secondPanelIndex);
            assertEquals(firstId, secondId);
        }
    }

    private static double cycleCoordinate(double nearbyMaterialPhase, double relativeHit, double panelIndex,
                                          double panelStepPhase) {
        return positiveModulo(nearbyMaterialPhase + relativeHit - panelIndex * panelStepPhase,
                PITCH * RingworldBoardDotOutage.CELL_PERIOD);
    }

    private static RingworldSunshade.CameraRelativeBands bands(double edgeRelative, int orientation) {
        return new RingworldSunshade.CameraRelativeBands(
                1.0, 0.0, 300.0, 100.0, 200.0, edgeRelative, orientation,
                RingworldSunshade.BandCoverage.PARTIAL);
    }

    private static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }

    private static float positiveModuloFloat(float value, float period) {
        float result = value % period;
        return result < 0.0f ? result + period : result;
    }

    private static void assertBoundedPhase(double value) {
        assertTrue(value >= 0.0);
        assertTrue(value < PITCH);
    }
}
