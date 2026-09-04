package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;

public class RingworldRuntimeGenerationTest {
    @Test
    public void clientReadsOnlyACompleteTypedServerPayloadGeneration() {
        UUID generation = UUID.fromString("2f4ce7e4-6192-4c7c-b3cb-b6da6399b0a9");
        NBTTagCompound payload = new NBTTagCompound();

        assertNull(RingworldRuntimeGeneration.readClient(payload));
        RingworldRuntimeGeneration.write(payload, generation);
        assertEquals(generation, RingworldRuntimeGeneration.readClient(payload));

        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setString("RingworldRuntimeGenerationMost", "not-a-long");
        malformed.setLong("RingworldRuntimeGenerationLeast", generation.getLeastSignificantBits());
        assertNull(RingworldRuntimeGeneration.readClient(malformed));
    }
}
