package stellarium.world.ring;

import java.math.BigDecimal;

/**
 * Computes the soft skylight field and opaque footprint of the moving ringworld sunshade.
 */
public final class RingworldSunshade {

    private static final EdgeSample OUTSIDE_STRIP_SAMPLE = new EdgeSample(false, 0.0, false, 1.0);

    private final double spacingBlocks;
    private final double shadowWidthBlocks;
    private final double phaseOffsetBlocks;
    private final long cycleTicks;
    private final double directionX;
    private final double directionZ;
    private final double featherBlocks;
    private final double sideFeatherBlocks;

    public RingworldSunshade(double spacingBlocks,
                             double shadowWidthBlocks,
                             long cycleTicks,
                             double phaseOffsetBlocks,
                             double headingDegrees,
                             double featherBlocks) {
        this(spacingBlocks, shadowWidthBlocks, cycleTicks, phaseOffsetBlocks, headingDegrees, featherBlocks, 0.0);
    }

    /**
     * Creates a moving sunshade with a finite-strip soft field on both Z sides.
     * The side feather only affects transmitted skylight below the board; it
     * does not extend or soften the physical board material.
     */
    public RingworldSunshade(double spacingBlocks,
                             double shadowWidthBlocks,
                             long cycleTicks,
                             double phaseOffsetBlocks,
                             double headingDegrees,
                             double featherBlocks,
                             double sideFeatherBlocks) {
        requireFinite("spacingBlocks", spacingBlocks);
        requireFinite("shadowWidthBlocks", shadowWidthBlocks);
        requireFinite("phaseOffsetBlocks", phaseOffsetBlocks);
        requireFinite("headingDegrees", headingDegrees);
        requireFinite("featherBlocks", featherBlocks);
        requireFinite("sideFeatherBlocks", sideFeatherBlocks);
        if (spacingBlocks <= 0.0) {
            throw new IllegalArgumentException("spacingBlocks must be greater than zero");
        }
        if (shadowWidthBlocks < 0.0 || shadowWidthBlocks > spacingBlocks) {
            throw new IllegalArgumentException("shadowWidthBlocks must be within [0, spacingBlocks]");
        }
        if (cycleTicks <= 0L) {
            throw new IllegalArgumentException("cycleTicks must be greater than zero");
        }
        double maximumFeatherBlocks = Math.min(shadowWidthBlocks, spacingBlocks - shadowWidthBlocks) / 2.0;
        if (featherBlocks < 0.0 || featherBlocks > maximumFeatherBlocks) {
            throw new IllegalArgumentException("featherBlocks exceeds the available panel or gap width");
        }
        double maximumSideFeatherBlocks = Math.min(-RingworldStripBounds.BOARD_MIN_Z,
                RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        if (sideFeatherBlocks < 0.0 || sideFeatherBlocks > maximumSideFeatherBlocks) {
            throw new IllegalArgumentException("sideFeatherBlocks must fit within the finite board half-width");
        }
        this.spacingBlocks = spacingBlocks;
        this.shadowWidthBlocks = shadowWidthBlocks;
        this.cycleTicks = cycleTicks;
        this.phaseOffsetBlocks = centeredModulo(phaseOffsetBlocks, spacingBlocks);
        double headingRadians = StrictMath.toRadians(headingDegrees % 360.0);
        this.directionX = StrictMath.cos(headingRadians);
        this.directionZ = StrictMath.sin(headingRadians);
        requireFinite("directionX", directionX);
        requireFinite("directionZ", directionZ);
        this.featherBlocks = featherBlocks;
        this.sideFeatherBlocks = sideFeatherBlocks;
    }

    /**
     * Evaluates the moving band once for consumers that need both the soft
     * skylight field and the opaque board footprint.
     *
     * <p>Within the finite board, a partial panel's motion-axis signed edge
     * distance is negative inside, positive outside, and zero on its boundary.
     * Both zero-width panels and full coverage have no material edge. Samples
     * outside the board are likewise neutral empty space: they have no material
     * edge, no occupancy, and full transmittance, rather than a fabricated
     * sunrise edge at the transverse cut.</p>
     */
    public EdgeSample sample(long worldTime, double x, double z) {
        double projection = projectReceiver(x, z);
        if (!RingworldStripBounds.insideBoard(z)) {
            return OUTSIDE_STRIP_SAMPLE;
        }
        return sampleForDistance(evaluateDistanceFromPanelCenter(worldTime, projection), z);
    }

    /** Samples a phase captured once by the render entry without re-evaluating its time. */
    public EdgeSample sample(Phase phase, double x, double z) {
        if (phase == null) {
            throw new NullPointerException("phase");
        }
        double projection = projectReceiver(x, z);
        if (!RingworldStripBounds.insideBoard(z)) {
            return OUTSIDE_STRIP_SAMPLE;
        }
        if (shadowWidthBlocks == 0.0 || shadowWidthBlocks == spacingBlocks) {
            return sampleForDistance(0.0, z);
        }
        return sampleForDistance(evaluateDistanceFromPanelCenter(phase, projection), z);
    }

    /** Allocation-free display-phase material query for packed-light hot paths. */
    public boolean materialOccupied(Phase phase, double x, double z) {
        if (phase == null) {
            throw new NullPointerException("phase");
        }
        double projection = projectReceiver(x, z);
        if (!RingworldStripBounds.insideBoard(z) || shadowWidthBlocks == 0.0) {
            return false;
        }
        return shadowWidthBlocks == spacingBlocks
                || evaluateDistanceFromPanelCenter(phase, projection) <= shadowWidthBlocks / 2.0;
    }

    /** Allocation-free display-phase transmission query for packed-light hot paths. */
    public double transmittance(Phase phase, double x, double z) {
        if (phase == null) {
            throw new NullPointerException("phase");
        }
        double projection = projectReceiver(x, z);
        if (!RingworldStripBounds.insideBoard(z) || shadowWidthBlocks == 0.0) {
            return 1.0;
        }
        if (shadowWidthBlocks == spacingBlocks) {
            return combinedTransmittance(0.0, z);
        }
        return combinedTransmittance(transmittanceForDistance(evaluateDistanceFromPanelCenter(phase, projection),
                shadowWidthBlocks / 2.0), z);
    }

    private EdgeSample sampleForDistance(double distanceFromPanelCenter, double z) {
        if (shadowWidthBlocks == 0.0) {
            return new EdgeSample(false, 0.0, false, 1.0);
        }
        if (shadowWidthBlocks == spacingBlocks) {
            return new EdgeSample(false, 0.0, true, combinedTransmittance(0.0, z));
        }
        double panelEdge = shadowWidthBlocks / 2.0;
        double signedEdgeDistance = distanceFromPanelCenter - panelEdge;
        boolean materialOccupied = signedEdgeDistance <= 0.0;
        return new EdgeSample(true, signedEdgeDistance, materialOccupied,
                combinedTransmittance(transmittanceForDistance(distanceFromPanelCenter, panelEdge), z));
    }

    /** Builds the one-per-render phase from committed endpoints without widening absolute longs to double. */
    public Phase phase(long previousTime, long currentTime, double fraction) {
        if (fraction <= 0.0) {
            return new Phase(previousTime, currentTime, 0.0, panelCenter(previousTime));
        }
        if (fraction >= 1.0) {
            return new Phase(previousTime, currentTime, 1.0, panelCenter(currentTime));
        }
        BigDecimal previous = BigDecimal.valueOf(previousTime);
        BigDecimal span = BigDecimal.valueOf(currentTime).subtract(previous);
        BigDecimal time = previous.add(span.multiply(BigDecimal.valueOf(fraction)));
        BigDecimal period = BigDecimal.valueOf(cycleTicks);
        BigDecimal remainder = time.remainder(period);
        if (remainder.signum() < 0) {
            remainder = remainder.add(period);
        }
        BigDecimal half = period.divide(BigDecimal.valueOf(2L));
        if (remainder.compareTo(half) > 0) {
            remainder = remainder.subtract(period);
        }
        double centeredTicks = remainder.doubleValue();
        return new Phase(previousTime, currentTime, fraction,
                centeredModulo(phaseOffsetBlocks + spacingBlocks * (centeredTicks / cycleTicks), spacingBlocks));
    }

    /**
     * Returns the unwrapped material-panel ordinal for an already-built render
     * phase, reduced only at the caller's requested bounded modulus. Rendering
     * uses a wrapped center for GPU precision, but a dot fault belongs to the
     * same physical panel across that wrap. This helper is pure: it neither
     * changes phase construction nor any time/light/sync state.
     */
    public int canonicalPanelOrdinalResidue(Phase phase, int modulus) {
        if (phase == null) {
            throw new NullPointerException("phase");
        }
        if (modulus <= 0) {
            throw new IllegalArgumentException("modulus must be positive");
        }
        BigDecimal previous = BigDecimal.valueOf(phase.previousTime());
        BigDecimal span = BigDecimal.valueOf(phase.currentTime()).subtract(previous);
        BigDecimal time = previous.add(span.multiply(BigDecimal.valueOf(phase.fraction())));
        BigDecimal period = BigDecimal.valueOf(cycleTicks);
        BigDecimal remainder = time.remainder(period);
        if (remainder.signum() < 0) {
            remainder = remainder.add(period);
        }
        BigDecimal half = period.divide(BigDecimal.valueOf(2L));
        // Keep the exact strict `>` boundary used by phase(...): +half remains
        // positive, while a value just above it enters the negative half.
        BigDecimal centeredTicks = remainder.compareTo(half) > 0 ? remainder.subtract(period) : remainder;
        BigDecimal wholeCycles = time.subtract(centeredTicks).divide(period);

        double cycleLocalCenter = phaseOffsetBlocks
                + spacingBlocks * (centeredTicks.doubleValue() / cycleTicks);
        double wrappedDifference = cycleLocalCenter - phase.panelCenterBlocks();
        long localWraps = Math.round(wrappedDifference / spacingBlocks);
        if (Math.abs(wrappedDifference - localWraps * spacingBlocks) > Math.ulp(spacingBlocks) * 4.0) {
            throw new IllegalArgumentException("Phase center is not compatible with this sunshade");
        }
        BigDecimal ordinal = wholeCycles.add(BigDecimal.valueOf(localWraps));
        int residue = ordinal.remainder(BigDecimal.valueOf(modulus)).intValueExact();
        return residue < 0 ? residue + modulus : residue;
    }

    /**
     * Produces the periodic board input relative to one render origin. The
     * nearest physical edge may be either the leading or trailing edge: the
     * orientation tells the GPU which direction enters board material without
     * subtracting large world-space phase values in float precision.
     */
    public CameraRelativeBands cameraRelativeBands(Phase phase, double renderOriginX, double renderOriginZ) {
        if (phase == null) {
            throw new NullPointerException("phase");
        }
        requireFinite("renderOriginX", renderOriginX);
        requireFinite("renderOriginZ", renderOriginZ);
        // Constant coverage has no periodic edge. Canonical unused GPU dimensions
        // avoid imposing float restrictions on authoritative full/empty fields.
        if (shadowWidthBlocks == 0.0) {
            return new CameraRelativeBands(directionX, directionZ, 1.0, 0.0, 1.0, 0.0, 0,
                    BandCoverage.EMPTY);
        }
        if (shadowWidthBlocks == spacingBlocks) {
            return new CameraRelativeBands(directionX, directionZ, 1.0, 1.0, 0.0, 0.0, 0,
                    BandCoverage.FULL);
        }
        double originProjection = renderOriginX * directionX + renderOriginZ * directionZ;
        requireFinite("renderOriginProjection", originProjection);
        double halfWidth = shadowWidthBlocks / 2.0;
        double leadingRelative = centeredModulo(phase.panelCenterBlocks() - halfWidth - originProjection,
                spacingBlocks);
        double trailingRelative = centeredModulo(phase.panelCenterBlocks() + halfWidth - originProjection,
                spacingBlocks);
        if (Math.abs(leadingRelative) <= Math.abs(trailingRelative)) {
            return new CameraRelativeBands(directionX, directionZ, spacingBlocks, shadowWidthBlocks,
                    spacingBlocks - shadowWidthBlocks,
                    leadingRelative, 1, BandCoverage.PARTIAL);
        }
        return new CameraRelativeBands(directionX, directionZ, spacingBlocks, shadowWidthBlocks,
                spacingBlocks - shadowWidthBlocks,
                trailingRelative, -1, BandCoverage.PARTIAL);
    }

    /** Empty material needs neither a board program nor a representable display height. */
    public boolean isEmpty() {
        return shadowWidthBlocks == 0.0;
    }

    private double panelCenter(long worldTime) {
        long remainder = Math.floorMod(worldTime, cycleTicks);
        long centeredTicks = remainder > cycleTicks / 2L ? remainder - cycleTicks : remainder;
        return centeredModulo(phaseOffsetBlocks + spacingBlocks * (centeredTicks / (double) cycleTicks), spacingBlocks);
    }

    /** Existing ground-lighting seam; runs the shared scalar profile without allocating a sample. */
    public double transmittance(long worldTime, double x, double z) {
        double projection = projectReceiver(x, z);
        if (!RingworldStripBounds.insideBoard(z)) {
            return 1.0;
        }
        double distanceFromPanelCenter = evaluateDistanceFromPanelCenter(worldTime, projection);
        if (shadowWidthBlocks == 0.0) {
            return 1.0;
        }
        if (shadowWidthBlocks == spacingBlocks) {
            return combinedTransmittance(0.0, z);
        }
        return combinedTransmittance(transmittanceForDistance(distanceFromPanelCenter, shadowWidthBlocks / 2.0), z);
    }

    /** Validate before either transverse or full/empty bypass, with one projection per query. */
    private double projectReceiver(double x, double z) {
        requireFinite("x", x);
        requireFinite("z", z);
        double projection = x * directionX + z * directionZ;
        requireFinite("projection", projection);
        return projection;
    }

    /** Shared scalar profile for an already validated receiver projection. */
    private double evaluateDistanceFromPanelCenter(long worldTime, double projection) {
        if (shadowWidthBlocks == 0.0) {
            return 0.0;
        }
        if (shadowWidthBlocks == spacingBlocks) {
            return 0.0;
        }
        // Center both phases before adding: two finite offsets must not produce
        // an infinite panel position inside a running world's light query.
        long remainder = Math.floorMod(worldTime, cycleTicks);
        long centeredTicks = remainder > cycleTicks / 2L ? remainder - cycleTicks : remainder;
        double cyclePosition = centeredTicks / (double) cycleTicks;
        double panelCenter = centeredModulo(phaseOffsetBlocks + spacingBlocks * cyclePosition, spacingBlocks);
        double relativeProjection = projection - panelCenter;
        requireFinite("relativeProjection", relativeProjection);
        double offsetWithinPeriod = Math.abs(relativeProjection % spacingBlocks);
        return Math.min(offsetWithinPeriod, spacingBlocks - offsetWithinPeriod);
    }

    private double evaluateDistanceFromPanelCenter(Phase phase, double projection) {
        if (shadowWidthBlocks == 0.0 || shadowWidthBlocks == spacingBlocks) {
            return 0.0;
        }
        double relativeProjection = projection - phase.panelCenterBlocks();
        requireFinite("relativeProjection", relativeProjection);
        double offsetWithinPeriod = Math.abs(relativeProjection % spacingBlocks);
        return Math.min(offsetWithinPeriod, spacingBlocks - offsetWithinPeriod);
    }

    /** Applies the existing smoothstep curve to the shared phase/profile distance. */
    private double transmittanceForDistance(double distanceFromPanelCenter, double panelEdge) {
        if (featherBlocks == 0.0) {
            return distanceFromPanelCenter <= panelEdge ? 0.0 : 1.0;
        }
        double featherStart = panelEdge - featherBlocks;
        if (distanceFromPanelCenter <= featherStart) {
            return 0.0;
        }
        if (distanceFromPanelCenter >= panelEdge) {
            return 1.0;
        }
        double progress = (distanceFromPanelCenter - featherStart) / featherBlocks;
        return progress * progress * (3.0 - 2.0 * progress);
    }

    /**
     * Combines the moving panel and finite-strip soft fields as independent
     * opacity factors. Both callers already established that {@code z} is
     * inside the half-open physical board, so this never expands it.
     */
    private double combinedTransmittance(double movementAxisTransmittance, double z) {
        double sideTransmittance = sideTransmittance(z);
        return 1.0 - (1.0 - movementAxisTransmittance) * (1.0 - sideTransmittance);
    }

    /**
     * Fades only inward from each finite strip side. A zero width preserves
     * historical hard board boundaries; the midpoint remains fully shaded.
     */
    private double sideTransmittance(double z) {
        if (sideFeatherBlocks == 0.0) {
            return 0.0;
        }
        double inwardDistance = Math.min(z - RingworldStripBounds.BOARD_MIN_Z,
                RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE - z);
        if (inwardDistance >= sideFeatherBlocks) {
            return 0.0;
        }
        double progress = inwardDistance / sideFeatherBlocks;
        double smoothstep = progress * progress * (3.0 - 2.0 * progress);
        return 1.0 - smoothstep;
    }

    /** Exposes the persisted finite-strip soft-edge distance in blocks. */
    public double sideFeatherBlocks() {
        return sideFeatherBlocks;
    }

    /** Exposes the persisted movement-axis soft-edge distance in blocks. */
    public double featherBlocks() {
        return featherBlocks;
    }

    public record EdgeSample(boolean hasMaterialEdge,
                             double signedEdgeDistanceBlocks,
                             boolean materialOccupied,
                             double transmittance) {
    }

    public record Phase(long previousTime, long currentTime, double fraction, double panelCenterBlocks) {
        public Phase {
            if (!Double.isFinite(fraction) || fraction < 0.0 || fraction > 1.0
                    || !Double.isFinite(panelCenterBlocks)) {
                throw new IllegalArgumentException("Invalid sunshade phase");
            }
        }
    }

    public enum BandCoverage {
        EMPTY,
        PARTIAL,
        FULL
    }

    /** Immutable GPU input whose coordinates are relative to the renderer's double-precision origin. */
    public record CameraRelativeBands(double directionX,
                                      double directionZ,
                                      double spacingBlocks,
                                      double panelWidthBlocks,
                                      double gapWidthBlocks,
                                      double edgeRelativeToRenderOriginBlocks,
                                      int edgeOrientation,
                                      BandCoverage coverage) {
        public CameraRelativeBands {
            requireFinite("directionX", directionX);
            requireFinite("directionZ", directionZ);
            requireFinite("spacingBlocks", spacingBlocks);
            requireFinite("panelWidthBlocks", panelWidthBlocks);
            requireFinite("gapWidthBlocks", gapWidthBlocks);
            requireFinite("edgeRelativeToRenderOriginBlocks", edgeRelativeToRenderOriginBlocks);
            requireExactGpuDimension("spacingBlocks", spacingBlocks);
            requireExactGpuDimension("panelWidthBlocks", panelWidthBlocks);
            requireExactGpuDimension("gapWidthBlocks", gapWidthBlocks);
            if (spacingBlocks <= 0.0 || panelWidthBlocks < 0.0 || panelWidthBlocks > spacingBlocks
                    || gapWidthBlocks < 0.0 || gapWidthBlocks > spacingBlocks
                    || Math.abs((spacingBlocks - panelWidthBlocks) - gapWidthBlocks) > Math.ulp(spacingBlocks)) {
                throw new IllegalArgumentException("Invalid camera-relative board dimensions");
            }
            if (coverage == null) {
                throw new NullPointerException("coverage");
            }
            if ((coverage == BandCoverage.PARTIAL && (edgeOrientation != 1 && edgeOrientation != -1
                    || panelWidthBlocks == 0.0 || gapWidthBlocks == 0.0))
                    || (coverage != BandCoverage.PARTIAL && edgeOrientation != 0)) {
                throw new IllegalArgumentException("Invalid camera-relative board edge orientation");
            }
        }
    }

    private static void requireExactGpuDimension(String name, double value) {
        if ((double) (float) value != value) {
            throw new IllegalArgumentException("The GLSL board path cannot exactly represent " + name + "=" + value);
        }
    }

    private static double centeredModulo(double value, double period) {
        double remainder = value % period;
        double halfPeriod = period / 2.0;
        if (remainder > halfPeriod) {
            return remainder - period;
        }
        return remainder < -halfPeriod ? remainder + period : remainder;
    }

    private static void requireFinite(String parameterName, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(parameterName + " must be finite");
        }
    }
}
