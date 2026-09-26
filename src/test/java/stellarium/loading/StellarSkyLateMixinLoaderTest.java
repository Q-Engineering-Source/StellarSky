package stellarium.loading;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class StellarSkyLateMixinLoaderTest {
    @Test public void missingDhDoesNotQueueItsClassReferences() {
        assertEquals(List.of("mixins.stellarsky.actinium.json"), StellarSkyLateMixinLoader.configsFor(null));
    }
    @Test public void supportedDhQueuesItsExactAdapter() {
        assertEquals(List.of("mixins.stellarsky.actinium.json", "mixins.stellarsky.dh.json"),
                StellarSkyLateMixinLoader.configsFor("3.2.0-b"));
    }
    @Test public void dh330QueuesTheVerifiedAdapterAndItsTemporalConfigBridge() {
        assertEquals(List.of("mixins.stellarsky.actinium.json", "mixins.stellarsky.dh.json", "mixins.stellarsky.dh330.json"),
                StellarSkyLateMixinLoader.configsFor("3.3.0"));
    }
    @Test public void unsupportedDhCannotReceiveUnverifiedMixinTargets() {
        assertThrows(IllegalStateException.class, () -> StellarSkyLateMixinLoader.configsFor("3.2.1-b-dev"));
    }
    @Test public void dedicatedServerDoesNotEnforceAClientRendererVersion() {
        assertEquals(List.of("mixins.stellarsky.actinium.json"),
                StellarSkyLateMixinLoader.configsFor("3.2.1-b-dev", false));
    }
}
