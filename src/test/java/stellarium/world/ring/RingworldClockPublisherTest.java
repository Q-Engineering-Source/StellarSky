package stellarium.world.ring;

import static org.junit.Assert.assertEquals;

import java.util.UUID;

import org.junit.Test;

public class RingworldClockPublisherTest {
    @Test
    public void committedPublisherAdvancesSequenceEvenWhenWorldTimeRepeats() {
        UUID generation = UUID.fromString("2f4ce7e4-6192-4c7c-b3cb-b6da6399b0a9");
        RingworldClockPublisher publisher = new RingworldClockPublisher(generation);

        RingworldClockSample first = publisher.publish(7, 12_345L);
        RingworldClockSample second = publisher.publish(7, 12_345L);

        assertEquals(generation, first.generation());
        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals(12_345L, second.worldTime());
        assertEquals(second, publisher.latestSample());
    }
}
