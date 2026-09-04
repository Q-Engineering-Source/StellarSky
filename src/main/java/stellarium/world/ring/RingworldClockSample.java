package stellarium.world.ring;

import java.util.Objects;
import java.util.UUID;

/**
 * One server-committed ringworld lighting endpoint. It is neither a
 * prediction nor an interpolated time.
 */
public record RingworldClockSample(int dimension, UUID generation, long sequence, long worldTime,
                                  boolean discontinuousBefore) {
    /** No provenance was supplied: never invent a smooth segment. */
    public RingworldClockSample(int dimension, UUID generation, long sequence, long worldTime) {
        this(dimension, generation, sequence, worldTime, true);
    }

    public RingworldClockSample {
        Objects.requireNonNull(generation, "generation");
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
