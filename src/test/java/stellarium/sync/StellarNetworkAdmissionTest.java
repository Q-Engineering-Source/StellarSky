package stellarium.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.minecraftforge.fml.relauncher.Side;
import org.junit.Test;
import stellarium.StellarSky;
import stellarium.StellarSkyReferences;

public class StellarNetworkAdmissionTest {
    @Test
    public void differentInstalledSceneSchemaVersionsCannotConnectOnEitherSide() {
        StellarSky mod = new StellarSky();
        Map<String, String> peer = Map.of(StellarSkyReferences.MODID, "incompatible-scene-schema");

        assertFalse(mod.checkNetwork(peer, Side.CLIENT));
        assertFalse(mod.checkNetwork(peer, Side.SERVER));

        // The prior ring build has geometry schema 2 but no runtime clock wire.
        Map<String, String> geometryOnlyPeer = Map.of(StellarSkyReferences.MODID, "1.12.2-0.5.4.3");
        assertFalse(mod.checkNetwork(geometryOnlyPeer, Side.CLIENT));
        assertFalse(mod.checkNetwork(geometryOnlyPeer, Side.SERVER));

        Map<String, String> exactClockOnlyPeer = Map.of(StellarSkyReferences.MODID, "1.12.2-0.5.4.4");
        assertFalse(mod.checkNetwork(exactClockOnlyPeer, Side.CLIENT));
        assertFalse(mod.checkNetwork(exactClockOnlyPeer, Side.SERVER));
        Map<String, String> snapshotOnlyPeer = Map.of(StellarSkyReferences.MODID, "1.12.2-0.5.4.5");
        assertFalse(mod.checkNetwork(snapshotOnlyPeer, Side.CLIENT));
        assertFalse(mod.checkNetwork(snapshotOnlyPeer, Side.SERVER));
        // Same clock/NBT layout, but the old build has an infinite transverse board.
        Map<String, String> infiniteWidthPeer = Map.of(StellarSkyReferences.MODID, "1.12.2-0.5.4.6");
        assertFalse(mod.checkNetwork(infiniteWidthPeer, Side.CLIENT));
        assertFalse(mod.checkNetwork(infiniteWidthPeer, Side.SERVER));
        Map<String, String> withoutThinAtmosphere = Map.of(StellarSkyReferences.MODID, "1.12.2-0.5.4.7");
        assertFalse(mod.checkNetwork(withoutThinAtmosphere, Side.CLIENT));
        assertFalse(mod.checkNetwork(withoutThinAtmosphere, Side.SERVER));
        Map<String, String> withoutRenderTimeLocalLight = Map.of(
                StellarSkyReferences.MODID, "1.12.2-0.5.4.8");
        assertFalse(mod.checkNetwork(withoutRenderTimeLocalLight, Side.CLIENT));
        assertFalse(mod.checkNetwork(withoutRenderTimeLocalLight, Side.SERVER));
    }

    @Test
    public void matchingInstalledVersionsCanConnectOnEitherSide() {
        StellarSky mod = new StellarSky();
        Map<String, String> peer = Map.of(StellarSkyReferences.MODID, StellarSkyReferences.VERSION);

        assertTrue(mod.checkNetwork(peer, Side.CLIENT));
        assertTrue(mod.checkNetwork(peer, Side.SERVER));
        assertTrue(mod.existOnServer());
    }

    @Test
    public void catalogueAndStandardBuildFlavorsCannotConnectOnEitherSide() {
        StellarSky mod = new StellarSky();
        String current = StellarSkyReferences.VERSION;
        String counterpart = current.endsWith("-catalogue")
                ? current.substring(0, current.length() - "-catalogue".length())
                : current + "-catalogue";
        Map<String, String> peer = Map.of(StellarSkyReferences.MODID, counterpart);

        assertFalse(mod.checkNetwork(peer, Side.CLIENT));
        assertFalse(mod.checkNetwork(peer, Side.SERVER));
    }

    @Test
    public void optionalPeerFallbackAndNextServerPresenceRemainIntact() {
        StellarSky mod = new StellarSky();

        assertTrue(mod.checkNetwork(Map.of(), Side.CLIENT));
        assertTrue(mod.checkNetwork(Map.of(), Side.SERVER));
        assertFalse(mod.existOnServer());
        assertTrue(mod.checkNetwork(
                Map.of(StellarSkyReferences.MODID, StellarSkyReferences.VERSION), Side.SERVER));
        assertTrue(mod.existOnServer());
    }
}
