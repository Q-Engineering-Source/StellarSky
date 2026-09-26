package stellarium.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraftforge.common.config.Configuration;
import org.junit.Test;

/** Uses the real config loader without creating a Minecraft or OpenGL client. */
public class BoardDotSettingsTest {
    @Test
    public void defaultsKeepPermanentFaultsSeparateFromOptionalAnimationAndWidthProfile() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        settings.loadFromConfig(config, "client");
        assertEquals(0.25f, settings.ringworldBoardDotOffProbability, 0.0f);
        assertEquals(25.0, config.getCategory("client").get("Ringworld_Board_Dot_Off_Percent").getDouble(), 0.0);
        assertEquals(0, settings.ringworldBoardDotSeed);
        assertFalse(settings.ringworldBoardDotPulseEnabled);
        assertFalse(settings.ringworldBoardDotEdgeProfileEnabled);
        assertEquals(10.0f, settings.ringworldBoardDotPulsePeriodSeconds, 0.0f);
        assertEquals(15.0f, settings.ringworldBoardDotEdgeBandPercent, 0.0f);
        assertEquals(100.0f, settings.ringworldBoardDotEdgeBrightnessPercent, 0.0f);
        assertEquals(20.0f, settings.ringworldBoardDotCenterBrightnessPercent, 0.0f);
        assertEquals(5.0f, settings.ringworldBoardDotEdgeTransitionPercent, 0.0f);
    }

    @Test
    public void percentagesRoundTripAndDoNotEnableOtherVisualOptions() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        var category = config.getCategory("client");
        category.get("Ringworld_Board_Dot_Off_Percent").set(37.5);
        category.get("Ringworld_Board_Dot_Seed").set(-81);
        category.get("Ringworld_Board_Dot_Pulse_Enabled").set(true);
        category.get("Ringworld_Board_Dot_Pulse_Period_Seconds").set(60.0);
        category.get("Ringworld_Board_Dot_Edge_Profile_Enabled").set(true);
        category.get("Ringworld_Board_Dot_Edge_Band_Percent").set(12.5);
        category.get("Ringworld_Board_Dot_Edge_Brightness_Percent").set(80.0);
        category.get("Ringworld_Board_Dot_Center_Brightness_Percent").set(0.0);
        category.get("Ringworld_Board_Dot_Edge_Transition_Percent").set(2.5);
        settings.loadFromConfig(config, "client");
        assertEquals(0.375f, settings.ringworldBoardDotOffProbability, 0.0f);
        assertEquals(-81, settings.ringworldBoardDotSeed);
        assertTrue(settings.ringworldBoardDotPulseEnabled);
        assertTrue(settings.ringworldBoardDotEdgeProfileEnabled);
        assertEquals(60.0f, settings.ringworldBoardDotPulsePeriodSeconds, 0.0f);
        assertEquals(12.5f, settings.ringworldBoardDotEdgeBandPercent, 0.0f);
        assertEquals(80.0f, settings.ringworldBoardDotEdgeBrightnessPercent, 0.0f);
        assertEquals(0.0f, settings.ringworldBoardDotCenterBrightnessPercent, 0.0f);
        assertEquals(2.5f, settings.ringworldBoardDotEdgeTransitionPercent, 0.0f);
        settings.saveToConfig(config, "client");
        assertEquals(37.5, category.get("Ringworld_Board_Dot_Off_Percent").getDouble(), 0.0);
        category.get("Ringworld_Board_Dot_Off_Percent").set(100.0);
        category.get("Ringworld_Board_Dot_Pulse_Enabled").set(false);
        settings.loadFromConfig(config, "client");
        assertEquals(1.0f, settings.ringworldBoardDotOffProbability, 0.0f);
        assertFalse(settings.ringworldBoardDotPulseEnabled);
        assertTrue(settings.ringworldBoardDotEdgeProfileEnabled);
        category.get("Ringworld_Board_Dot_Off_Percent").set(0.0);
        settings.loadFromConfig(config, "client");
        assertEquals(0.0f, settings.ringworldBoardDotOffProbability, 0.0f);
    }
}
