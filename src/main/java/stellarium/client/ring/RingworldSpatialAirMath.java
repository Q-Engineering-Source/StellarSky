package stellarium.client.ring;

/** CPU mirrors of stable scalar parts of the shader, kept small enough to test without a GL context. */
public final class RingworldSpatialAirMath {
    public static final int MAX_PARTITIONS = 8;
    public static final int SOURCE_EVALUATIONS_PER_PARTITION = 2;
    public static final int MAX_SOURCE_EVALUATIONS = MAX_PARTITIONS * SOURCE_EVALUATIONS_PER_PARTITION;

    /**
     * Display-linear compressed-thin-air calibration. At full density,
     * unoccluded zenith phase (g=.35) and 100 blocks, this yields pre-view-T
     * in-scatter near (0.0010, 0.0046, 0.0178): deliberately faint but visible
     * blue distant air without restoring the old observer-global sky dome.
     * It is a first-release appearance target, not physical radiometry; real
     * GPU visual calibration remains required before acceptance.
     */
    public record OpticalCoefficients(double sigmaTRed, double sigmaTGreen, double sigmaTBlue,
                                      double sigmaSRed, double sigmaSGreen, double sigmaSBlue,
                                      double sunRed, double sunGreen, double sunBlue) {
        public OpticalCoefficients {
            double[] values = {sigmaTRed, sigmaTGreen, sigmaTBlue, sigmaSRed, sigmaSGreen, sigmaSBlue,
                    sunRed, sunGreen, sunBlue};
            for (double value : values) if (!Double.isFinite(value) || value < 0.0)
                throw new IllegalArgumentException("optical coefficient must be finite and non-negative");
            if (sigmaSRed > sigmaTRed || sigmaSGreen > sigmaTGreen || sigmaSBlue > sigmaTBlue)
                throw new IllegalArgumentException("sigmaS must not exceed sigmaT");
        }
    }

    private static final OpticalCoefficients DISPLAY_LINEAR_OPTICS = new OpticalCoefficients(
            0.000020, 0.000035, 0.000060,
            0.000008, 0.000018, 0.000035,
            5.0, 10.0, 20.0);
    private RingworldSpatialAirMath() {
    }

    public static OpticalCoefficients displayLinearOptics() { return DISPLAY_LINEAR_OPTICS; }

    public static double verticalSunMass(double y, double fullDensityTopY, double upperY) {
        requireFinite(y);
        requireFinite(fullDensityTopY);
        requireFinite(upperY);
        if (!(fullDensityTopY < upperY)) throw new IllegalArgumentException("invalid air fade interval");
        if (y >= upperY) return 0.0;
        double fade = upperY - fullDensityTopY;
        if (y <= fullDensityTopY) return fade * 0.5 + fullDensityTopY - y;
        double u = (y - fullDensityTopY) / fade;
        return fade * (0.5 - u + u * u * u - 0.5 * u * u * u * u);
    }

    /** Stable (1 - exp(-x)) / x used for a constant-density front-to-back segment. */
    public static double attenuationAverage(double x) {
        requireFinite(x);
        if (x < 0.0) throw new IllegalArgumentException("optical depth must be non-negative");
        if (x < 1.0e-4) return 1.0 - x * 0.5 + x * x / 6.0;
        return -Math.expm1(-x) / x;
    }

    public static double henyeyGreenstein(double cosine, double anisotropy) {
        if (!Double.isFinite(cosine) || !Double.isFinite(anisotropy) || cosine < -1.0 || cosine > 1.0
                || anisotropy <= -0.95 || anisotropy >= 0.95) {
            throw new IllegalArgumentException("invalid phase input");
        }
        double denominator = Math.pow(1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine, 1.5);
        return (1.0 - anisotropy * anisotropy) / (4.0 * Math.PI * denominator);
    }

    public static double clampDistanceBudget(double requested, double spacing, double gapWidth) {
        requireFinite(requested);
        requireFinite(spacing);
        requireFinite(gapWidth);
        if (requested < 0.0 || spacing <= 0.0 || gapWidth < 0.0 || gapWidth > spacing) {
            throw new IllegalArgumentException("invalid distance budget");
        }
        double panelWidth = spacing - gapWidth;
        if (panelWidth <= 0.0 || gapWidth <= 0.0) return requested;
        return Math.min(requested, Math.min(panelWidth, gapWidth) * 0.25);
    }

    /** Mean of the two smoothstep material-edge ramps plus the fully lit gap. */
    public static double periodMeanTransmission(double spacing, double gapWidth, double feather) {
        requireFinite(spacing);
        requireFinite(gapWidth);
        requireFinite(feather);
        if (spacing <= 0.0 || gapWidth < 0.0 || gapWidth > spacing || feather < 0.0
                || feather > Math.min(spacing - gapWidth, gapWidth) * 0.5)
            throw new IllegalArgumentException("invalid periodic illumination dimensions");
        return (gapWidth + feather) / spacing;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
    }
}
