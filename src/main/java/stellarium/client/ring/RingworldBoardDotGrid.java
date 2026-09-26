package stellarium.client.ring;

import stellarium.world.ring.RingworldSunshade;

/**
 * Pure, double-precision conversion from frozen world coordinates to bounded
 * dot-grid uniforms. The fragment shader never subtracts a world-scale panel
 * location to decide whether a one-block dot is visible.
 */
final class RingworldBoardDotGrid {
    private RingworldBoardDotGrid() {
    }

    static GridFrame fromBands(RingworldSunshade.CameraRelativeBands bands,
                               double observerX, double observerZ, double pitchBlocks) {
        return fromBands(bands, null, observerX, observerZ, pitchBlocks);
    }

    /**
     * Builds both the existing visual lattice residues and the wider bounded
     * material-cell cycle used by deterministic dot outages. Supplying the
     * frame phase makes the panel ordinal move with the board material rather
     * than with whichever periodic edge happened to be nearest the camera.
     */
    static GridFrame fromBands(RingworldSunshade.CameraRelativeBands bands, RingworldSunshade.Phase phase,
                               double observerX, double observerZ, double pitchBlocks) {
        return fromBands(bands, null, phase, observerX, observerZ, pitchBlocks);
    }

    /** Renderer-only overload: sunshade supplies the unwrapped material panel ordinal across phase wraps. */
    static GridFrame fromBands(RingworldSunshade.CameraRelativeBands bands, RingworldSunshade sunshade,
                               RingworldSunshade.Phase phase, double observerX, double observerZ,
                               double pitchBlocks) {
        if (bands == null) {
            throw new NullPointerException("bands");
        }
        requireFinite("observerX", observerX);
        requireFinite("observerZ", observerZ);
        if (!Double.isFinite(pitchBlocks) || pitchBlocks <= 0.0
                || pitchBlocks * RingworldBoardDotOutage.CELL_PERIOD >= RingworldBoardDotOutage.MAX_CYCLE_BLOCKS) {
            throw new IllegalArgumentException("pitchBlocks must keep the GLSL cell cycle finite and safely exact");
        }
        double observerProjection = observerX * bands.directionX() + observerZ * bands.directionZ();
        // board.frag receives uBandHeading as float uniforms. Build this
        // scalar basis from those exact quantized values (without normalizing)
        // so a large observer coordinate cannot drift by double-vs-float
        // heading error before the phase is reduced for GLSL.
        double tangentX = -(double) (float) bands.directionZ();
        double tangentZ = (double) (float) bands.directionX();
        double observerTangent = observerX * tangentX + observerZ * tangentZ;
        requireFinite("observerProjection", observerProjection);
        requireFinite("observerTangent", observerTangent);
        if (bands.coverage() != RingworldSunshade.BandCoverage.PARTIAL) {
            return new GridFrame(positiveModulo(observerX, pitchBlocks), positiveModulo(observerZ, pitchBlocks),
                    0.0, 0.0, 0.0, 0.0, 0.0,
                    positiveModulo(observerX, RingworldBoardDotOutage.cycleBlocks(pitchBlocks)),
                    positiveModulo(observerZ, RingworldBoardDotOutage.cycleBlocks(pitchBlocks)),
                    0.0, 0.0, 0,
                    positiveModulo(observerTangent, pitchBlocks),
                    positiveModulo(observerTangent, RingworldBoardDotOutage.cycleBlocks(pitchBlocks)));
        }

        // CameraRelativeBands may select either nearest edge. Restore the one
        // canonical leading edge before deriving any visible point pattern.
        double leadingRelative = bands.edgeOrientation() == 1
                ? bands.edgeRelativeToRenderOriginBlocks()
                : bands.edgeRelativeToRenderOriginBlocks() - bands.panelWidthBlocks();
        requireFinite("canonicalLeadingRelative", leadingRelative);
        double spacing = bands.spacingBlocks();
        // A Phase provides the material's own canonical panel reference. The
        // fallback preserves the old public helper contract used by existing
        // grid tests, but renderer code always supplies the actual phase.
        double canonicalLeadingWorld = phase == null
                ? positiveModulo(observerProjection + leadingRelative, spacing)
                : phase.panelCenterBlocks() - bands.panelWidthBlocks() / 2.0;
        requireFinite("canonicalLeadingWorld", canonicalLeadingWorld);

        // Anchor one physical panel near the observer. Its absolute index is
        // intentionally kept on the CPU; only its X/Z pitch residues go to GLSL.
        double nearbyPanelIndex = StrictMath.floor((observerProjection - canonicalLeadingWorld) / spacing);
        double nearbyLeadingWorld = canonicalLeadingWorld + nearbyPanelIndex * spacing;
        double nearbyLeadingRelative = nearbyLeadingWorld - observerProjection;
        requireFinite("nearbyLeadingRelative", nearbyLeadingRelative);

        double cellCycleBlocks = RingworldBoardDotOutage.cycleBlocks(pitchBlocks);
        int nearbyPanelResidue = (int) Math.floorMod((long) nearbyPanelIndex
                - (sunshade == null || phase == null ? 0
                : sunshade.canonicalPanelOrdinalResidue(phase, RingworldBoardDotOutage.CELL_PERIOD)),
                (long) RingworldBoardDotOutage.CELL_PERIOD);
        return new GridFrame(
                positiveModulo(observerX, pitchBlocks),
                positiveModulo(observerZ, pitchBlocks),
                positiveModulo(bands.directionX() * nearbyLeadingWorld, pitchBlocks),
                positiveModulo(bands.directionZ() * nearbyLeadingWorld, pitchBlocks),
                positiveModulo(bands.directionX() * spacing, pitchBlocks),
                positiveModulo(bands.directionZ() * spacing, pitchBlocks),
                nearbyLeadingRelative,
                positiveModulo(observerX - bands.directionX() * nearbyLeadingWorld, cellCycleBlocks),
                positiveModulo(observerZ - bands.directionZ() * nearbyLeadingWorld, cellCycleBlocks),
                positiveModulo(bands.directionX() * spacing, cellCycleBlocks),
                positiveModulo(bands.directionZ() * spacing, cellCycleBlocks),
                nearbyPanelResidue,
                positiveModulo(observerTangent, pitchBlocks),
                positiveModulo(observerTangent, cellCycleBlocks));
    }

    private static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    record GridFrame(double observerXPhaseBlocks,
                     double observerZPhaseBlocks,
                     double nearbyPanelAnchorXPhaseBlocks,
                     double nearbyPanelAnchorZPhaseBlocks,
                     double panelStepXPhaseBlocks,
                     double panelStepZPhaseBlocks,
                     double nearbyLeadingRelativeBlocks,
                     double nearbyMaterialXCyclePhaseBlocks,
                     double nearbyMaterialZCyclePhaseBlocks,
                     double panelStepXCyclePhaseBlocks,
                     double panelStepZCyclePhaseBlocks,
                     int nearbyPanelResidue,
                     double tangentPhaseBlocks,
                     double tangentCyclePhaseBlocks) {
        GridFrame {
            if (!Double.isFinite(observerXPhaseBlocks) || !Double.isFinite(observerZPhaseBlocks)
                    || !Double.isFinite(nearbyPanelAnchorXPhaseBlocks)
                    || !Double.isFinite(nearbyPanelAnchorZPhaseBlocks)
                    || !Double.isFinite(panelStepXPhaseBlocks) || !Double.isFinite(panelStepZPhaseBlocks)
                    || !Double.isFinite(nearbyLeadingRelativeBlocks)
                    || !Double.isFinite(nearbyMaterialXCyclePhaseBlocks)
                    || !Double.isFinite(nearbyMaterialZCyclePhaseBlocks)
                    || !Double.isFinite(panelStepXCyclePhaseBlocks)
                    || !Double.isFinite(panelStepZCyclePhaseBlocks)
                    || !Double.isFinite(tangentPhaseBlocks)
                    || !Double.isFinite(tangentCyclePhaseBlocks)) {
                throw new IllegalArgumentException("grid frame must be finite");
            }
            if (nearbyPanelResidue < 0 || nearbyPanelResidue >= RingworldBoardDotOutage.CELL_PERIOD) {
                throw new IllegalArgumentException("grid frame panel residue must be bounded");
            }
        }
    }
}
