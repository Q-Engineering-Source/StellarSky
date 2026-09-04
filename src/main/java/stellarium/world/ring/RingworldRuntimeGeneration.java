package stellarium.world.ring;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** Typed generation fields carried by the opaque server scene payload. */
public final class RingworldRuntimeGeneration {
    private static final String MOST_KEY = "RingworldRuntimeGenerationMost";
    private static final String LEAST_KEY = "RingworldRuntimeGenerationLeast";

    private RingworldRuntimeGeneration() {
    }

    public static void write(NBTTagCompound payload, UUID generation) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(generation, "generation");
        payload.setLong(MOST_KEY, generation.getMostSignificantBits());
        payload.setLong(LEAST_KEY, generation.getLeastSignificantBits());
    }

    /** Missing or malformed client payload has no ring authority. */
    public static UUID readClient(NBTTagCompound payload) {
        if (payload == null || !payload.hasKey(MOST_KEY, 4) || !payload.hasKey(LEAST_KEY, 4)) {
            return null;
        }
        return new UUID(payload.getLong(MOST_KEY), payload.getLong(LEAST_KEY));
    }
}
