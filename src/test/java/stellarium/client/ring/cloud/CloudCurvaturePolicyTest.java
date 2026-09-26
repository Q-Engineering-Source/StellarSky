package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class CloudCurvaturePolicyTest {
    private static final double AU_METERS = 149_597_870_700.0D;
    private static final double ACCEPTANCE_RADIUS = 0.000292D * AU_METERS;
    private static final double TOLERANCE = 0.05D;
    private static final double EPSILON = 1.0e-9D;

    @Test
    public void acceptanceRadiusKeepsTheFirstTwoBandsFlatThenUsesThreeChordSegments() {
        List<CloudCurvaturePolicy.Segment> segments =
                CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, TOLERANCE, 16_384.0D);

        assertEquals(9, segments.size());
        assertTrue(segments.get(0).curved());
        assertFold(segments.get(1), -8_192.0D, -6_144.0D);
        assertFold(segments.get(2), -6_144.0D, -4_096.0D);
        assertFold(segments.get(3), -4_096.0D, -2_048.0D);
        assertFlat(segments.get(4), -2_048.0D, 2_048.0D);
        assertFold(segments.get(5), 2_048.0D, 4_096.0D);
        assertFold(segments.get(6), 4_096.0D, 6_144.0D);
        assertFold(segments.get(7), 6_144.0D, 8_192.0D);
        assertTrue(segments.get(8).curved());
    }

    @Test
    public void oneAuKeepsAllThreeDimensionalCloudBandsFlat() {
        List<CloudCurvaturePolicy.Segment> segments =
                CloudCurvaturePolicy.segments(AU_METERS, TOLERANCE, 16_384.0D);

        assertEquals(1, segments.size());
        assertFlat(segments.get(0), -16_384.0D, 16_384.0D);
    }

    @Test
    public void zeroToleranceDisablesEveryPlanarApproximation() {
        List<CloudCurvaturePolicy.Segment> segments =
                CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, 0.0D, 16_384.0D);

        assertEquals(1, segments.size());
        assertTrue(segments.get(0).curved());
        assertEquals(-16_384.0D, segments.get(0).minX(), 0.0D);
        assertEquals(16_384.0D, segments.get(0).maxX(), 0.0D);
    }

    @Test
    public void foldPiecesMeetTheFlatAndExactCircleEndpointsWithoutGaps() {
        List<CloudCurvaturePolicy.Segment> segments =
                CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, TOLERANCE, 16_384.0D);

        CloudCurvaturePolicy.Segment firstPositiveFold = segments.get(5);
        CloudCurvaturePolicy.Segment lastPositiveFold = segments.get(7);
        assertPosition(firstPositiveFold, 2_048.0D, 2_048.0D, 0.0D);
        assertPosition(lastPositiveFold, 8_192.0D,
                circleX(ACCEPTANCE_RADIUS, 8_192.0D), circleSag(ACCEPTANCE_RADIUS, 8_192.0D));

        for (int index = 1; index < segments.size() - 1; index++) {
            CloudCurvaturePolicy.Segment left = segments.get(index);
            CloudCurvaturePolicy.Segment right = segments.get(index + 1);
            if (!left.curved() && !right.curved()) {
                assertEquals(left.maxX(), right.minX(), 0.0D);
                assertPosition(left, left.maxX(), mapX(right, right.minX()), mapY(right, right.minX()));
            }
        }
    }

    @Test
    public void positiveAndNegativeFoldProfilesMirrorAcrossTheObserver() {
        List<CloudCurvaturePolicy.Segment> segments =
                CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, TOLERANCE, 16_384.0D);

        for (int offset = 0; offset < 3; offset++) {
            CloudCurvaturePolicy.Segment negative = segments.get(3 - offset);
            CloudCurvaturePolicy.Segment positive = segments.get(5 + offset);
            assertEquals(-positive.maxX(), negative.minX(), 0.0D);
            assertEquals(-positive.minX(), negative.maxX(), 0.0D);
            assertEquals(positive.slopeX(), negative.slopeX(), EPSILON);
            assertEquals(-positive.interceptX(), negative.interceptX(), EPSILON);
            assertEquals(-positive.slopeY(), negative.slopeY(), EPSILON);
            assertEquals(positive.interceptY(), negative.interceptY(), EPSILON);
        }
    }

    @Test
    public void clippingDoesNotChangeTheChosenProfileOrTheFoldAffineMaps() {
        List<CloudCurvaturePolicy.Segment> full =
                CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, TOLERANCE, 16_384.0D);
        List<CloudCurvaturePolicy.Segment> clipped =
                CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, TOLERANCE, 3_000.0D);

        assertEquals(3, clipped.size());
        for (CloudCurvaturePolicy.Segment clippedSegment : clipped) {
            CloudCurvaturePolicy.Segment source = full.stream()
                    .filter(candidate -> candidate.curved() == clippedSegment.curved()
                            && candidate.minX() <= clippedSegment.minX()
                            && candidate.maxX() >= clippedSegment.maxX())
                    .findFirst()
                    .orElseThrow(AssertionError::new);
            assertEquals(source.slopeX(), clippedSegment.slopeX(), EPSILON);
            assertEquals(source.interceptX(), clippedSegment.interceptX(), EPSILON);
            assertEquals(source.slopeY(), clippedSegment.slopeY(), EPSILON);
            assertEquals(source.interceptY(), clippedSegment.interceptY(), EPSILON);
        }
    }

    @Test
    public void invalidToleranceAndSmallOrMissingRadiiUseTheSafeContract() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, -0.001D, 512.0D));
        assertThrows(IllegalArgumentException.class,
                () -> CloudCurvaturePolicy.segments(ACCEPTANCE_RADIUS, TOLERANCE, 0.0D));
        assertEquals(1, CloudCurvaturePolicy.segments(0.0D, TOLERANCE, 512.0D).size());
        assertTrue(CloudCurvaturePolicy.segments(8_192.0D, TOLERANCE, 512.0D).get(0).curved());
    }

    private static void assertFlat(CloudCurvaturePolicy.Segment segment, double minimum, double maximum) {
        assertFalse(segment.curved());
        assertEquals(minimum, segment.minX(), 0.0D);
        assertEquals(maximum, segment.maxX(), 0.0D);
        assertEquals(1.0D, segment.slopeX(), 0.0D);
        assertEquals(0.0D, segment.interceptX(), 0.0D);
        assertEquals(0.0D, segment.slopeY(), 0.0D);
        assertEquals(0.0D, segment.interceptY(), 0.0D);
    }

    private static void assertFold(CloudCurvaturePolicy.Segment segment, double minimum, double maximum) {
        assertFalse(segment.curved());
        assertEquals(minimum, segment.minX(), 0.0D);
        assertEquals(maximum, segment.maxX(), 0.0D);
    }

    private static void assertPosition(CloudCurvaturePolicy.Segment segment, double inputX,
            double expectedX, double expectedY) {
        assertEquals(expectedX, mapX(segment, inputX), 1.0e-8D);
        assertEquals(expectedY, mapY(segment, inputX), 1.0e-8D);
    }

    private static double mapX(CloudCurvaturePolicy.Segment segment, double x) {
        return segment.slopeX() * x + segment.interceptX();
    }

    private static double mapY(CloudCurvaturePolicy.Segment segment, double x) {
        return segment.slopeY() * x + segment.interceptY();
    }

    private static double circleX(double radius, double x) {
        return radius * Math.sin(x / radius);
    }

    private static double circleSag(double radius, double x) {
        return 2.0D * radius * Math.sin(x / (2.0D * radius)) * Math.sin(x / (2.0D * radius));
    }
}
