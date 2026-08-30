package stellarium.stellars;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OpticsHelperTest {
    @Test
    public void twinkleIsStrongestAtHorizon() {
        assertEquals(1.0f, OpticsHelper.twinkleAltitudeFactor(0.0), 0.0f);
    }

    @Test
    public void twinkleRetainsTenPercentAtZenith() {
        assertEquals(0.1f, OpticsHelper.twinkleAltitudeFactor(1.0), 1.0e-6f);
    }

    @Test
    public void belowHorizonDoesNotExceedHorizonStrength() {
        assertEquals(1.0f, OpticsHelper.twinkleAltitudeFactor(-1.0), 0.0f);
    }
}
