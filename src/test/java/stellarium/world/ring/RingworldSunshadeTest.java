package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldSunshadeTest {

	@Test(expected = IllegalArgumentException.class)
	public void phaseRejectsNaNFraction() { new RingworldSunshade.Phase(0L, 0L, Double.NaN, 0.0); }

	@Test(expected = IllegalArgumentException.class)
	public void phaseRejectsOutOfRangeFraction() { new RingworldSunshade.Phase(0L, 0L, 1.1, 0.0); }

	@Test(expected = IllegalArgumentException.class)
	public void phaseRejectsInfinitePanelCenter() { new RingworldSunshade.Phase(0L, 0L, 0.5, Double.POSITIVE_INFINITY); }
    @Test
    public void constantCoverageDoesNotRequireRepresentableUnusedPeriodDimensions() {
        RingworldSunshade empty = new RingworldSunshade(20_088_001.0, 0.0, 100L, 0.0, 0.0, 0.0);
        RingworldSunshade full = new RingworldSunshade(20_088_001.0, 20_088_001.0, 100L, 0.0, 0.0, 0.0);
        assertEquals(RingworldSunshade.BandCoverage.EMPTY,
                empty.cameraRelativeBands(empty.phase(0L, 0L, 0.0), 0.0, 0.0).coverage());
        assertEquals(RingworldSunshade.BandCoverage.FULL,
                full.cameraRelativeBands(full.phase(0L, 0L, 0.0), 0.0, 0.0).coverage());
    }

    @Test
    public void rendererInputRejectsStaticBandDimensionsThatFloatWouldMoveByABlock() {
        RingworldSunshade oddWidth = new RingworldSunshade(40_176_000.0, 20_088_001.0,
                1_728_000L, 0.0, 0.0, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> oddWidth.cameraRelativeBands(oddWidth.phase(0L, 0L, 0.0), 0.0, 0.0));
        // The authoritative double-precision field remains valid; only this GPU input is unsupported.
        assertTrue(oddWidth.sample(0L, 10_044_000.25, 0.0).materialOccupied());
    }

    @Test
    public void displayPhaseUsesCommittedLongIntervalInsteadOfWrappedCenterInterpolation() {
        RingworldSunshade sunshade = new RingworldSunshade(2325.0, 1.0, 100L, 0.0, 0.0, 0.0);

        assertEquals(151.125, sunshade.phase(Long.MAX_VALUE - 1L, Long.MAX_VALUE, 0.5).panelCenterBlocks(), 0.0);
        assertEquals(-581.25, sunshade.phase(0L, 150L, 0.5).panelCenterBlocks(), 0.0);
        assertEquals(sunshade.phase(75L, 75L, 0.0).panelCenterBlocks(),
                sunshade.phase(75L, 150L, 0.0).panelCenterBlocks(), 0.0);
        assertEquals(sunshade.phase(150L, 150L, 0.0).panelCenterBlocks(),
                sunshade.phase(75L, 150L, 1.0).panelCenterBlocks(), 0.0);
    }

    @Test
    public void canonicalPanelOrdinalKeepsStrictHalfBoundaryAndSupportsNegativeAndHugeInterpolatedTimes() {
        RingworldSunshade positiveHalf = new RingworldSunshade(300.0, 100.0, 100L, 150.0, 0.0, 0.0);
        RingworldSunshade negativeHalf = new RingworldSunshade(300.0, 100.0, 100L, -150.0, 0.0, 0.0);
        int modulus = 4093;
        assertEquals(0, positiveHalf.canonicalPanelOrdinalResidue(positiveHalf.phase(0L, 0L, 1.0), modulus));
        assertEquals(1, positiveHalf.canonicalPanelOrdinalResidue(positiveHalf.phase(1L, 1L, 1.0), modulus));
        assertEquals(0, negativeHalf.canonicalPanelOrdinalResidue(negativeHalf.phase(0L, 0L, 1.0), modulus));
        assertEquals(modulus - 1,
                negativeHalf.canonicalPanelOrdinalResidue(negativeHalf.phase(-1L, -1L, 1.0), modulus));

        RingworldSunshade.Phase hugeFraction = positiveHalf.phase(Long.MAX_VALUE - 1L, Long.MAX_VALUE, 0.5);
        int hugeResidue = positiveHalf.canonicalPanelOrdinalResidue(hugeFraction, modulus);
        assertTrue(hugeResidue >= 0 && hugeResidue < modulus);
    }

    @Test
    public void edgeSampleSharesTheSoftFieldButKeepsMaterialOccupancyBinary() {
        RingworldSunshade sunshade = new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 2.0);

        RingworldSunshade.EdgeSample softInterior = sunshade.sample(0L, 4.0, 0.0);
        assertTrue(softInterior.hasMaterialEdge());
        assertEquals(-1.0, softInterior.signedEdgeDistanceBlocks(), 0.0);
        assertTrue(softInterior.materialOccupied());
        assertEquals(0.5, softInterior.transmittance(), 0.0);

        RingworldSunshade.EdgeSample boundary = sunshade.sample(0L, 5.0, 0.0);
        assertEquals(0.0, boundary.signedEdgeDistanceBlocks(), 0.0);
        assertTrue(boundary.materialOccupied());
        assertEquals(1.0, boundary.transmittance(), 0.0);

        RingworldSunshade.EdgeSample gap = sunshade.sample(0L, 6.0, 0.0);
        assertEquals(1.0, gap.signedEdgeDistanceBlocks(), 0.0);
        assertFalse(gap.materialOccupied());
        assertEquals(1.0, gap.transmittance(), 0.0);
    }

    @Test
    public void edgeSampleDefinesNoEdgeForEmptyAndFullCoverageAndTracksFastNegativeMotion() {
        RingworldSunshade noPanel = new RingworldSunshade(10.0, 0.0, 100L, 2.0, 0.0, 0.0);
        RingworldSunshade fullCoverage = new RingworldSunshade(10.0, 10.0, 100L, 2.0, 0.0, 0.0);
        RingworldSunshade fastFixture = new RingworldSunshade(2325.0, 1.0, 100L, 0.0, 0.0, 0.0);

        RingworldSunshade.EdgeSample clear = noPanel.sample(0L, -98.0, 0.0);
        assertFalse(clear.hasMaterialEdge());
        assertEquals(0.0, clear.signedEdgeDistanceBlocks(), 0.0);
        assertFalse(clear.materialOccupied());
        assertEquals(1.0, clear.transmittance(), 0.0);

        RingworldSunshade.EdgeSample covered = fullCoverage.sample(0L, -98.0, 0.0);
        assertFalse(covered.hasMaterialEdge());
        assertEquals(0.0, covered.signedEdgeDistanceBlocks(), 0.0);
        assertTrue(covered.materialOccupied());
        assertEquals(0.0, covered.transmittance(), 0.0);

        // 465 m/s at 20 TPS is 23.25 blocks per tick: an engineering fixture only.
        assertTrue(fastFixture.sample(1L, 23.25, 0.0).materialOccupied());
        assertTrue(fastFixture.sample(-1L, -23.25, 0.0).materialOccupied());
    }

    @Test
    public void opaquePanelCenterBlocksAllSkylight() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);

        assertEquals(0.0, sunshade.transmittance(0L, 0.0, 0.0), 0.0);
    }

    @Test
    public void clearGapPassesAllSkylight() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);

        assertEquals(1.0, sunshade.transmittance(0L, 3.0, 0.0), 0.0);
    }

    @Test
    public void panelsAdvanceOneHalfSpacingAtHalfCycle() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);

        assertEquals(0.0, sunshade.transmittance(50L, 5.0, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(50L, 0.0, 0.0), 0.0);
    }

    @Test
    public void headingProjectsBandsAlongItsUnitDirection() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 90.0, 0.0);

        assertEquals(0.0, sunshade.transmittance(0L, 0.0, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(0L, 0.0, 3.0), 0.0);
        assertEquals(0.0, sunshade.transmittance(0L, 3.0, 0.0), 0.0);
    }

    @Test
    public void negativeCoordinatesAndExtremeTimesWrapToTheRepeatingBands() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);

        assertEquals(0.0, sunshade.transmittance(0L, -10.0, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(0L, -7.0, 0.0), 0.0);
        assertEquals(0.0, sunshade.transmittance(-50L, 5.0, 0.0), 0.0);
        assertEquals(0.0, sunshade.transmittance(Long.MIN_VALUE, -0.8, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(Long.MIN_VALUE, 2.2, 0.0), 0.0);
    }

    @Test
    public void featherUsesSmoothstepAcrossThePanelOuterEdge() {
        RingworldSunshade sunshade = new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 2.0);

        assertEquals(0.0, sunshade.transmittance(0L, 3.0, 0.0), 0.0);
        assertEquals(0.5, sunshade.transmittance(0L, 4.0, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(0L, 5.0, 0.0), 0.0);
        assertEquals(1.0, sunshade.transmittance(0L, 6.0, 0.0), 0.0);
    }

    @Test
    public void sideFeatherFadesOnlyInwardFromBothFiniteStripEdges() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0, 128.0);
        RingworldSunshade.Phase phase = sunshade.phase(0L, 0L, 0.0);

        assertEquals(1.0, sunshade.transmittance(0L, 0.0, -8_192.0), 0.0);
        assertTrue(sunshade.transmittance(0L, 0.0, -8_192.0 + 1.0e-3) < 1.0);
        assertEquals(0.5, sunshade.transmittance(0L, 0.0, -8_128.0), 0.0);
        assertEquals(0.5, sunshade.transmittance(phase, 0.0, -8_128.0), 0.0);
        assertEquals(0.0, sunshade.transmittance(0L, 0.0, -8_064.0), 0.0);
        assertEquals(0.0, sunshade.transmittance(0L, 0.0, 8_064.0), 0.0);
        assertEquals(0.5, sunshade.transmittance(0L, 0.0, 8_128.0), 0.0);
        assertTrue(sunshade.transmittance(0L, 0.0, 8_192.0 - 1.0e-3) < 1.0);
        assertEquals(1.0, sunshade.transmittance(0L, 0.0, 8_192.0), 0.0);
    }

    @Test
    public void sideAndMovementFeathersCombineAsIndependentOpacityFactors() {
        RingworldSunshade sunshade = new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 2.0, 128.0);

        // x=4 gives Tx=.5 and z=-8128 gives Tz=.5, so T=1-(1-Tx)*(1-Tz)=.75.
        assertEquals(0.75, sunshade.transmittance(0L, 4.0, -8_128.0), 0.0);
        assertEquals(0.5, sunshade.transmittance(0L, 4.0, 0.0), 0.0);
        assertEquals(0.5, sunshade.transmittance(0L, 0.0, -8_128.0), 0.0);
    }

    @Test
    public void sideFeatherNeverSoftensMaterialOccupancyOrExtendsThePhysicalBoard() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0, 128.0);
        RingworldSunshade.Phase phase = sunshade.phase(0L, 0L, 0.0);

        assertTrue(sunshade.sample(0L, 0.0, -8_192.0).materialOccupied());
        assertTrue(sunshade.materialOccupied(phase, 0.0, -8_128.0));
        assertTrue(sunshade.sample(0L, 0.0, -8_128.0).materialOccupied());
        assertFalse(sunshade.sample(0L, 0.0, -8_192.000_001).materialOccupied());
        assertFalse(sunshade.materialOccupied(phase, 0.0, 8_192.0));
    }

    @Test
    public void longAndCapturedPhaseSideQueriesShareTheSameScalarField() {
        RingworldSunshade sunshade = new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 2.0, 128.0);
        RingworldSunshade.Phase phase = sunshade.phase(0L, 0L, 0.0);

        RingworldSunshade.EdgeSample sampled = sunshade.sample(0L, 4.0, -8_128.0);
        RingworldSunshade.EdgeSample captured = sunshade.sample(phase, 4.0, -8_128.0);
        assertEquals(sampled.transmittance(), captured.transmittance(), 0.0);
        assertEquals(sampled.materialOccupied(), captured.materialOccupied());
        assertEquals(sampled.transmittance(), sunshade.transmittance(phase, 4.0, -8_128.0), 0.0);
    }

    @Test
    public void noPanelIsTransparentAndFullCoverageIsOpaque() {
        RingworldSunshade noPanel = new RingworldSunshade(10.0, 0.0, 100L, 2.0, 0.0, 0.0);
        RingworldSunshade fullCoverage = new RingworldSunshade(10.0, 10.0, 100L, 2.0, 0.0, 0.0);

        assertEquals(1.0, noPanel.transmittance(0L, 2.0, 0.0), 0.0);
        assertEquals(1.0, noPanel.transmittance(0L, -98.0, 0.0), 0.0);
        assertEquals(0.0, fullCoverage.transmittance(0L, 2.0, 0.0), 0.0);
        assertEquals(0.0, fullCoverage.transmittance(0L, -98.0, 0.0), 0.0);
    }

    @Test
    public void constructorRejectsInvalidSunshadeConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(0.0, 0.0, 100L, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(Double.POSITIVE_INFINITY, 0.0, 100L, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, -1.0, 100L, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 11.0, 100L, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 4.0, 0L, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 4.0, 100L, Double.NaN, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 4.0, 100L, 0.0, Double.NEGATIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 5.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0, 8_192.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0, Double.NaN));
        assertEquals(8_192.0,
                new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0, 8_192.0).sideFeatherBlocks(), 0.0);
    }

    @Test
    public void transmittanceRejectsNonfiniteCoordinatesAndProjectionOverflow() {
        RingworldSunshade alongDiagonal = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 45.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> alongDiagonal.transmittance(0L, Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class, () -> alongDiagonal.transmittance(0L, 0.0, Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> alongDiagonal.transmittance(0L, Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    public void finiteLargeOffsetsRemainPeriodicWithoutOverflowingDuringWorldTicks() {
        RingworldSunshade fullCoverage = new RingworldSunshade(
                Double.MAX_VALUE, Double.MAX_VALUE, 2L, Double.MAX_VALUE, 0.0, 0.0);
        RingworldSunshade narrowPanel = new RingworldSunshade(
                Double.MAX_VALUE, 4.0, 2L, Double.MAX_VALUE, 0.0, 0.0);
        RingworldSunshade shiftedPanel = new RingworldSunshade(
                Double.MAX_VALUE, 4.0, 2L, Double.MAX_VALUE * 0.75, 0.0, 0.0);

        assertEquals(0.0, fullCoverage.transmittance(1L, 0.0, 0.0), 0.0);
        assertEquals(0.0, narrowPanel.transmittance(0L, 0.0, 0.0), 0.0);
        assertEquals(1.0, narrowPanel.transmittance(0L, -7.0, 0.0), 0.0);
        assertEquals(1.0, narrowPanel.transmittance(1L, 0.0, 0.0), 0.0);
        assertEquals(1.0, shiftedPanel.transmittance(1L, 0.0, 0.0), 0.0);
    }

    @Test
    public void cameraRelativeBandsChooseTheNearestPhysicalLeadingOrTrailingEdge() {
        RingworldSunshade sunshade = new RingworldSunshade(
                40_176_000.0, 20_088_000.0, 1_728_000L, 0.0, 0.0, 627_750.0);
        RingworldSunshade.Phase phase = sunshade.phase(0L, 0L, 0.0);

        RingworldSunshade.CameraRelativeBands nearLeading =
                sunshade.cameraRelativeBands(phase, -10_044_000.125, 0.0);
        RingworldSunshade.CameraRelativeBands nearTrailing =
                sunshade.cameraRelativeBands(phase, 10_043_999.875, 0.0);

        assertEquals(RingworldSunshade.BandCoverage.PARTIAL, nearLeading.coverage());
        assertEquals(1, nearLeading.edgeOrientation());
        assertEquals(0.125, nearLeading.edgeRelativeToRenderOriginBlocks(), 0.0);
        assertEquals(-1, nearTrailing.edgeOrientation());
        assertEquals(0.125, nearTrailing.edgeRelativeToRenderOriginBlocks(), 0.0);
        assertEquals(40_176_000.0, nearTrailing.spacingBlocks(), 0.0);
        assertEquals(20_088_000.0, nearTrailing.panelWidthBlocks(), 0.0);
        assertEquals(20_088_000.0, nearTrailing.gapWidthBlocks(), 0.0);
        RingworldSunshade.CameraRelativeBands insideLeading = sunshade.cameraRelativeBands(phase, -10_043_999.875, 0.0);
        RingworldSunshade.CameraRelativeBands outsideTrailing = sunshade.cameraRelativeBands(phase, 10_044_000.125, 0.0);
        assertEquals(-0.125, insideLeading.edgeRelativeToRenderOriginBlocks(), 0.0);
        assertEquals(1, insideLeading.edgeOrientation());
        assertEquals(-0.125, outsideTrailing.edgeRelativeToRenderOriginBlocks(), 0.0);
        assertEquals(-1, outsideTrailing.edgeOrientation());
        assertTrue(sunshade.sample(phase, -10_043_999.875, 0.0).materialOccupied());
        assertFalse(sunshade.sample(phase, 10_044_000.125, 0.0).materialOccupied());
    }

    @Test
    public void cameraRelativeBandsKeepEmptyAndFullCoverageEdgeFree() {
        RingworldSunshade empty = new RingworldSunshade(40_176_000.0, 0.0, 1_728_000L, 0.0, 0.0, 0.0);
        RingworldSunshade full = new RingworldSunshade(
                40_176_000.0, 40_176_000.0, 1_728_000L, 0.0, 0.0, 0.0);

        assertEquals(RingworldSunshade.BandCoverage.EMPTY,
                empty.cameraRelativeBands(empty.phase(0L, 0L, 0.0), 29_999_999.75, -29_999_999.75).coverage());
        assertEquals(0, empty.cameraRelativeBands(empty.phase(0L, 0L, 0.0), 0.0, 0.0).edgeOrientation());
        assertEquals(RingworldSunshade.BandCoverage.FULL,
                full.cameraRelativeBands(full.phase(0L, 0L, 0.0), -29_999_999.75, 29_999_999.75).coverage());
        assertEquals(0, full.cameraRelativeBands(full.phase(0L, 0L, 0.0), 0.0, 0.0).edgeOrientation());
    }

    @Test
    public void finiteStripLeavesAnOtherwiseOpaqueBandUnshadedBeyondItsPositiveZEdge() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);

        assertTrue(sunshade.sample(0L, 0.0, 8_191.999).materialOccupied());
        RingworldSunshade.EdgeSample outside = sunshade.sample(0L, 0.0, 8_192.0);
        assertFalse(outside.hasMaterialEdge());
        assertFalse(outside.materialOccupied());
        assertEquals(1.0, outside.transmittance(), 0.0);
        assertEquals(1.0, sunshade.transmittance(0L, 0.0, 8_192.0), 0.0);
    }

    @Test
    public void finiteStripIncludesItsNegativeEdgeAndExcludesBothExteriorSides() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);

        assertTrue(sunshade.sample(Long.MAX_VALUE, 0.0, -8_192.0).materialOccupied());
        assertEquals(0.0, sunshade.transmittance(Long.MAX_VALUE, 0.0, -8_192.0), 0.0);
        assertFalse(sunshade.sample(Long.MAX_VALUE, 0.0, -8_192.000_001).hasMaterialEdge());
        assertEquals(1.0, sunshade.transmittance(Long.MAX_VALUE, 0.0, -8_192.000_001), 0.0);
        assertFalse(sunshade.sample(Long.MAX_VALUE, 0.0, 8_192.0).materialOccupied());
    }

    @Test
    public void finiteStripOverridesFullAndEmptyCoverageOnlyOutsideTheBoard() {
        RingworldSunshade full = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);
        RingworldSunshade empty = new RingworldSunshade(10.0, 0.0, 100L, 0.0, 0.0, 0.0);

        assertTrue(full.sample(0L, 0.0, 0.0).materialOccupied());
        assertFalse(full.sample(0L, 0.0, 8_192.0).hasMaterialEdge());
        assertFalse(full.sample(0L, 0.0, 8_192.0).materialOccupied());
        assertEquals(1.0, full.transmittance(0L, 0.0, 8_192.0), 0.0);
        assertFalse(empty.sample(0L, 0.0, 8_192.0).hasMaterialEdge());
        assertEquals(1.0, empty.transmittance(0L, 0.0, 8_192.0), 0.0);
    }

    @Test
    public void finiteStripAppliesToTheCommittedCurrentPhaseEndpoint() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);
        RingworldSunshade.Phase endpoint = sunshade.phase(Long.MAX_VALUE - 1L, Long.MAX_VALUE, 1.0);

        assertTrue(sunshade.sample(endpoint, 0.0, 0.0).materialOccupied());
        RingworldSunshade.EdgeSample outside = sunshade.sample(endpoint, 0.0, 8_192.0);
        assertFalse(outside.hasMaterialEdge());
        assertFalse(outside.materialOccupied());
        assertEquals(1.0, outside.transmittance(), 0.0);
    }
}
