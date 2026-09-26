package stellarium.client.ring.cloud;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the least expensive cloud curvature representation that stays
 * within the configured vertical error budget.
 *
 * <p>The decision is global for the ring radius and error budget. Callers may
 * request a shorter {@code maxDistance}, but that only clips the already
 * selected profile; it must never move a fold boundary between LODs.</p>
 */
public final class CloudCurvaturePolicy {
    /** Default maximum vertical deviation, in blocks/metres, before curvature is retained. */
    public static final double DEFAULT_MAX_PLANAR_ERROR_METERS = 0.05D;

    private static final double[] PLANAR_ENDS = {512.0D, 2_048.0D, 8_192.0D, 16_384.0D};
    private static final int FOLD_COUNT = 3;
    /* A smaller radius can traverse too much of the ring within one cloud page. */
    private static final double MINIMUM_SAFE_RADIUS_METERS = 16_384.0D;

    private CloudCurvaturePolicy() {
    }

    /**
     * Returns adjacent X ranges covering {@code [-maxDistance, maxDistance]}.
     * A non-curved segment applies its affine X/Y coefficients to a physical
     * local X coordinate. A curved segment deliberately has zero coefficients:
     * its consumer must take the established exact-curvature shader path.
     *
     * @param radius ring radius in metres; zero requests the safe exact path
     * @param tolerance maximum allowed planar sag in metres; zero disables approximation
     * @param maxDistance requested symmetrical coverage distance in metres
     */
    public static List<Segment> segments(double radius, double tolerance, double maxDistance) {
        requireFiniteNonNegative("Cloud curvature tolerance", tolerance);
        requireFinitePositive("Cloud curvature maximum distance", maxDistance);
        if (radius == 0.0D) {
            return List.of(exact(-maxDistance, maxDistance));
        }
        requireFinitePositive("Cloud curvature radius", radius);
        if (tolerance == 0.0D || radius <= MINIMUM_SAFE_RADIUS_METERS) {
            return List.of(exact(-maxDistance, maxDistance));
        }

        double flatEnd = largestPlanarEnd(radius, tolerance);
        if (flatEnd == 0.0D) {
            return List.of(exact(-maxDistance, maxDistance));
        }

        List<Segment> result = new ArrayList<>(2 * FOLD_COUNT + 3);
        int flatIndex = indexOf(flatEnd);
        double outerEnd = flatIndex + 1 < PLANAR_ENDS.length ? PLANAR_ENDS[flatIndex + 1] : flatEnd;
        if (outerEnd == flatEnd) {
            addExact(result, -maxDistance, -flatEnd);
            addFlat(result, -flatEnd, flatEnd);
            addExact(result, flatEnd, maxDistance);
        } else {
            addExact(result, -maxDistance, -outerEnd);
            addFold(result, radius, flatEnd, outerEnd, true);
            addFlat(result, -flatEnd, flatEnd);
            addFold(result, radius, flatEnd, outerEnd, false);
            addExact(result, outerEnd, maxDistance);
        }
        return clipToRequestedRange(result, maxDistance);
    }

    private static double largestPlanarEnd(double radius, double tolerance) {
        double selected = 0.0D;
        for (double end : PLANAR_ENDS) {
            if (sag(radius, end) <= tolerance) {
                selected = end;
            }
        }
        return selected;
    }

    private static int indexOf(double distance) {
        for (int index = 0; index < PLANAR_ENDS.length; index++) {
            if (PLANAR_ENDS[index] == distance) {
                return index;
            }
        }
        throw new IllegalStateException("Unknown cloud curvature planar end " + distance);
    }

    private static void addFold(List<Segment> result, double radius, double innerEnd,
            double outerEnd, boolean negative) {
        double step = (outerEnd - innerEnd) / FOLD_COUNT;
        int start = negative ? FOLD_COUNT - 1 : 0;
        int end = negative ? -1 : FOLD_COUNT;
        int increment = negative ? -1 : 1;
        for (int index = start; index != end; index += increment) {
            double from = innerEnd + step * index;
            double to = from + step;
            if (negative) {
                addFoldSegment(result, radius, innerEnd, outerEnd, -to, -from);
            } else {
                addFoldSegment(result, radius, innerEnd, outerEnd, from, to);
            }
        }
    }

    private static void addFoldSegment(List<Segment> result, double radius, double innerEnd,
            double outerEnd, double minimum, double maximum) {
        double mapMinimumX = foldedX(radius, innerEnd, outerEnd, minimum);
        double mapMaximumX = foldedX(radius, innerEnd, outerEnd, maximum);
        double mapMinimumY = foldedY(radius, innerEnd, outerEnd, minimum);
        double mapMaximumY = foldedY(radius, innerEnd, outerEnd, maximum);
        double slopeX = (mapMaximumX - mapMinimumX) / (maximum - minimum);
        double slopeY = (mapMaximumY - mapMinimumY) / (maximum - minimum);
        add(result, new Segment(minimum, maximum, false, slopeX, mapMinimumX - slopeX * minimum,
                slopeY, mapMinimumY - slopeY * minimum));
    }

    private static double foldedX(double radius, double innerEnd, double outerEnd, double x) {
        double sign = Math.copySign(1.0D, x);
        double absolute = Math.abs(x);
        double circular = radius * Math.sin(absolute / radius);
        double correction = (absolute - circular) * (outerEnd - absolute) / (outerEnd - innerEnd);
        return sign * (circular + correction);
    }

    private static double foldedY(double radius, double innerEnd, double outerEnd, double x) {
        double absolute = Math.abs(x);
        return sag(radius, absolute) - sag(radius, innerEnd) * (outerEnd - absolute) / (outerEnd - innerEnd);
    }

    /** Uses {@code 2R sin^2(x/(2R))} to avoid cancellation near the observer. */
    private static double sag(double radius, double x) {
        double sine = Math.sin(x / (2.0D * radius));
        return 2.0D * radius * sine * sine;
    }

    private static void addFlat(List<Segment> result, double minimum, double maximum) {
        add(result, new Segment(minimum, maximum, false, 1.0D, 0.0D, 0.0D, 0.0D));
    }

    private static void addExact(List<Segment> result, double minimum, double maximum) {
        if (maximum > minimum) {
            add(result, exact(minimum, maximum));
        }
    }

    private static Segment exact(double minimum, double maximum) {
        return new Segment(minimum, maximum, true, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private static void add(List<Segment> result, Segment segment) {
        if (segment.maximum() <= segment.minimum()) {
            return;
        }
        result.add(segment);
    }

    private static List<Segment> clipToRequestedRange(List<Segment> profile, double maxDistance) {
        List<Segment> clipped = new ArrayList<>(profile.size());
        for (Segment segment : profile) {
            double minimum = Math.max(-maxDistance, segment.minX());
            double maximum = Math.min(maxDistance, segment.maxX());
            if (maximum > minimum) {
                clipped.add(new Segment(minimum, maximum, segment.curved(), segment.slopeX(),
                        segment.interceptX(), segment.slopeY(), segment.interceptY()));
            }
        }
        if (clipped.isEmpty()) {
            throw new IllegalStateException("Cloud curvature profile did not cover its requested range");
        }
        return List.copyOf(clipped);
    }

    private static void requireFinitePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    /** One contiguous policy range. {@code curved=true} delegates to the exact shader path. */
    public record Segment(double minX, double maxX, boolean curved,
            double slopeX, double interceptX, double slopeY, double interceptY) {
        public Segment {
            if (!Double.isFinite(minX) || !Double.isFinite(maxX) || maxX <= minX
                    || !Double.isFinite(slopeX) || !Double.isFinite(interceptX)
                    || !Double.isFinite(slopeY) || !Double.isFinite(interceptY)) {
                throw new IllegalArgumentException("Invalid cloud curvature segment");
            }
        }

        private double minimum() {
            return minX;
        }

        private double maximum() {
            return maxX;
        }
    }
}
