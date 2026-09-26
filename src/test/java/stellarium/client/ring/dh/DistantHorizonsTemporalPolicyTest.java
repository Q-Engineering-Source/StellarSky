package stellarium.client.ring.dh;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.junit.Test;
import static org.junit.Assert.*;

public class DistantHorizonsTemporalPolicyTest {
    @Test public void onlyActiveCurvedFramesSuppressTaaWithoutChangingTheSavedSetting() {
        var taa = new ConfigEntry.Builder<Boolean>().set(true).build();
        var unrelated = new ConfigEntry.Builder<Boolean>().set(true).build();
        DistantHorizonsTemporalPolicy.register(taa);
        assertFalse(DistantHorizonsTemporalPolicy.suppress(taa, false));
        assertTrue(DistantHorizonsTemporalPolicy.suppress(taa, true));
        assertFalse(DistantHorizonsTemporalPolicy.suppress(unrelated, true));
        assertFalse(DistantHorizonsTemporalPolicy.suppress(null, true));
        assertEquals(Boolean.TRUE, taa.getTrueValue());
        assertFalse(DistantHorizonsTemporalPolicy.suppress(taa, false));
    }
}
