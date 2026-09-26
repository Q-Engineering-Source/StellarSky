package stellarium.client.ring;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Contracts for the optional symmetric bright-edge/dim-center board-dot profile. */
public class RingworldBoardDotWidthProfileTest {
    @Test
    public void disabledProfileLeavesEveryDotAtFullBrightness() {
        RingworldBoardDotWidthProfile.Profile profile = RingworldBoardDotWidthProfile.of(false, 15.0f, 0.0f,
                0.0f, 5.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, -50.0, -50.0, 50.0), 0.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, 0.0, -50.0, 50.0), 0.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, 50.0, -50.0, 50.0), 0.0f);
    }

    @Test
    public void edgeProfileIsLeftRightSymmetricAndUsesTheCenterBrightness() {
        RingworldBoardDotWidthProfile.Profile profile = RingworldBoardDotWidthProfile.of(true, 15.0f, 100.0f,
                20.0f, 5.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, -50.0, -50.0, 50.0), 0.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, 50.0, -50.0, 50.0), 0.0f);
        assertEquals(0.2f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, 0.0, -50.0, 50.0), 0.0f);
        assertEquals(RingworldBoardDotWidthProfile.brightnessMultiplier(profile, -32.5, -50.0, 50.0),
                RingworldBoardDotWidthProfile.brightnessMultiplier(profile, 32.5, -50.0, 50.0), 0.0f);
        assertEquals(0.6f, RingworldBoardDotWidthProfile.brightnessMultiplier(profile, -32.5, -50.0, 50.0),
                1.0e-6f);
    }

    @Test
    public void profileCanBeUniformlyBrightOrUniformlyDark() {
        RingworldBoardDotWidthProfile.Profile bright = RingworldBoardDotWidthProfile.of(true, 15.0f, 100.0f,
                100.0f, 5.0f);
        RingworldBoardDotWidthProfile.Profile dark = RingworldBoardDotWidthProfile.of(true, 15.0f, 0.0f,
                0.0f, 5.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(bright, 0.0, -50.0, 50.0), 0.0f);
        assertEquals(0.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(dark, -50.0, -50.0, 50.0), 0.0f);
        assertEquals(0.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(dark, 0.0, -50.0, 50.0), 0.0f);
    }

    @Test
    public void zeroEdgeBandUsesCenterBrightnessEvenAtTheExactWallAndHalfWidthIsAllEdge() {
        RingworldBoardDotWidthProfile.Profile noEdgeBand = RingworldBoardDotWidthProfile.of(true, 0.0f, 100.0f,
                20.0f, 5.0f);
        RingworldBoardDotWidthProfile.Profile fullWidthEdgeBand = RingworldBoardDotWidthProfile.of(true, 50.0f,
                100.0f, 20.0f, 5.0f);
        assertEquals(0.2f, RingworldBoardDotWidthProfile.brightnessMultiplier(noEdgeBand, -50.0, -50.0, 50.0),
                0.0f);
        assertEquals(0.2f, RingworldBoardDotWidthProfile.brightnessMultiplier(noEdgeBand, 0.0, -50.0, 50.0),
                0.0f);
        assertEquals(1.0f, RingworldBoardDotWidthProfile.brightnessMultiplier(fullWidthEdgeBand, 0.0, -50.0, 50.0),
                0.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void edgeBandCannotExceedOneHalfOfTheBoardWidth() {
        RingworldBoardDotWidthProfile.of(true, 50.1f, 100.0f, 20.0f, 5.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void transitionCannotExceedOneHalfOfTheBoardWidth() {
        RingworldBoardDotWidthProfile.of(true, 15.0f, 100.0f, 20.0f, 50.1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void brightnessPercentCannotExceedOneHundred() {
        RingworldBoardDotWidthProfile.of(true, 15.0f, 100.1f, 20.0f, 5.0f);
    }
}
