package stellarium.world.ring;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class RingworldClockSampleTest {
    @Test
    public void continuityMustBeExplicitAndUnspecifiedSamplesFailClosed() {
        UUID generation = UUID.fromString("2f4ce7e4-6192-4c7c-b3cb-b6da6399b0a9");

        RingworldClockSample unspecified = new RingworldClockSample(0, generation, 1L, 40L);
        assertTrue(unspecified.discontinuousBefore());

        RingworldClockSample continuous = new RingworldClockSample(0, generation, 2L, 41L, false);
        assertFalse(continuous.discontinuousBefore());
    }
}
