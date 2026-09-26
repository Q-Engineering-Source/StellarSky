package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import net.minecraftforge.common.config.Configuration;
import org.junit.Test;

public class RingworldGenerationConfigTest {
    @Test public void missingConfigDoesNotOptIntoGeneration() throws Exception {
        assertFalse(RingworldGenerationConfig.read(new Configuration()).requests(0));
    }

    @Test public void explicitOptInTargetsOnlyOverworld() throws Exception {
        var input = new Configuration();
        input.get("generation", "EnableNewOverworld", false).set(true);
        var config = RingworldGenerationConfig.read(input);
        assertTrue(config.requests(0));
        assertFalse(config.requests(-1));
        assertFalse(config.requests(1));
    }

    @Test public void malformedBooleanCannotSilentlyDisableOrEnableGeneration() throws Exception {
        var input = new Configuration();
        input.get("generation", "EnableNewOverworld", false).set("maybe");
        assertThrows(IllegalArgumentException.class, () -> RingworldGenerationConfig.read(input));
    }
}
