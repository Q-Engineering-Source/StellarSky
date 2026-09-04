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

    public RingworldSunshade(double spacingBlocks,
                             double shadowWidthBlocks,
                             long cycleTicks,
                             double phaseOffsetBlocks,
                             double headingDegrees,
                             double featherBlocks) {
        requireFinite("spacingBlocks", spacingBlocks);
        requireFinite("shadowWidthBlocks", shadowWidthBlocks);
        requireFinite("phaseOffsetBlocks", phaseOffsetBlocks);
        requireFinite("headingDegrees", headingDegrees);
        requireFinite("featherBlocks", featherBlocks);
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
        return sampleForDistance(evaluateDistanceFromPanelCenter(worldTime, projection));
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
            return sampleForDistance(0.0);
        }
        double relativeProjection = projection - phase.panelCenterBlocks();
        requireFinite("relativeProjection", relativeProjection);
        double offsetWithinPeriod = Math.abs(relativeProjection % spacingBlocks);
        return sampleForDistance(Math.min(offsetWithinPeriod, spacingBlocks - offsetWithinPeriod));
    }

    private EdgeSample sampleForDistance(double distanceFromPanelCenter) {
        if (shadowWidthBlocks == 0.0) {
            return new EdgeSample(false, 0.0, false, 1.0);
        }
        if (shadowWidthBlocks == spacingBlocks) {
            return new EdgeSample(false, 0.0, true, 0.0);
        }
        double panelEdge = shadowWidthBlocks / 2.0;
        double signedEdgeDistance = distanceFromPanelCenter - panelEdge;
        boolean materialOccupied = signedEdgeDistance <= 0.0;
        return new EdgeSample(true, signedEdgeDistance, materialOccupied,
                transmittanceForDistance(distanceFromPanelCenter, panelEdge));
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
            return 0.0;
        }
        return transmittanceForDistance(distanceFromPanelCenter, shadowWidthBlocks / 2.0);
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

    public record EdgeSample(boolean hasMaterialEdge,
                             double signedEdgeDistanceBlocks,
                             boolean materialOccupied,
                             double transmittance) {
    }

    public record Phase(long previousTime, long currentTime, double fraction, double panelCenterBlocks) {
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
