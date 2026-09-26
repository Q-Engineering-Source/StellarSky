package stellarium.client.ring.dh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.junit.Test;

/** Exercises the exact DH config type through its public builder, without an active client or Mixin runtime. */
public class DistantHorizonsCloudPolicyTest {
    @Test public void forcesOnlyTheTwoRegisteredDhCloudEntriesOff() {
        ConfigEntry<Boolean> cloudRendering = booleanEntry(true);
        ConfigEntry<Boolean> multiLayerClouds = booleanEntry(true);
        ConfigEntry<Boolean> unrelatedDhEntry = booleanEntry(true);

        DistantHorizonsCloudPolicy.registerCloudEntries(cloudRendering, multiLayerClouds);

        assertEquals(Boolean.FALSE, DistantHorizonsCloudPolicy.forceOffIfDhCloudEntry(cloudRendering, Boolean.TRUE));
        assertEquals(Boolean.FALSE, DistantHorizonsCloudPolicy.forceOffIfDhCloudEntry(multiLayerClouds, Boolean.TRUE));
        assertEquals(Boolean.TRUE, DistantHorizonsCloudPolicy.forceOffIfDhCloudEntry(unrelatedDhEntry, Boolean.TRUE));
        assertTrue(DistantHorizonsCloudPolicy.isDhCloudEntry(cloudRendering));
        assertTrue(DistantHorizonsCloudPolicy.isDhCloudEntry(multiLayerClouds));
        assertFalse(DistantHorizonsCloudPolicy.isDhCloudEntry(unrelatedDhEntry));
    }

    private static ConfigEntry<Boolean> booleanEntry(boolean initialValue) {
        return new ConfigEntry.Builder<Boolean>().set(initialValue).build();
    }
}
