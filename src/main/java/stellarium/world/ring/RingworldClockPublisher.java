package stellarium.world.ring;

import java.util.Objects;
import java.util.UUID;

/** Server scene-owned monotonic publisher for committed frame endpoints. */
public final class RingworldClockPublisher {
    private final UUID generation;
    private long sequence;
    private RingworldClockSample latestSample;

    public RingworldClockPublisher(UUID generation) {
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    public UUID generation() {
        return generation;
    }

    public RingworldClockSample publish(int dimension, long worldTime) {
        return publish(dimension, worldTime, true);
    }

    public RingworldClockSample publish(int dimension, long worldTime, boolean discontinuousBefore) {
        sequence = Math.incrementExact(sequence);
        latestSample = new RingworldClockSample(dimension, generation, sequence, worldTime, discontinuousBefore);
        return latestSample;
    }

    public RingworldClockSample latestSample() {
        return latestSample;
    }
}
