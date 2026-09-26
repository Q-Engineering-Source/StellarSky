package stellarium.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraftforge.common.config.Configuration;
import org.junit.Test;

/** Exercises the actual local config loader without initializing a Minecraft client. */
public class OwnCloudSettingsTest {
    @Test
    public void effectiveLayerCountAndPhysicalLayerHeightLoadIndependently() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        config.getCategory("client").get("SS_Cloud_Layers").set(2);
        config.getCategory("client").get("SS_Cloud_Cell_Height").set(16.0);
        settings.loadFromConfig(config, "client");
        assertEquals(2, settings.ownCloudLayers);
        assertEquals(16.0, settings.ownCloudCellHeight, 0.0);
        assertEquals(32.0, settings.ownCloudLayers * settings.ownCloudCellHeight, 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedThreeLayersAreRejectedInsteadOfSilentlyChoosingAnotherQuality() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        config.getCategory("client").get("SS_Cloud_Layers").set(3);
        settings.loadFromConfig(config, "client");
    }

    @Test(expected = IllegalArgumentException.class)
    public void aLegacyDetailOverrideCannotSilentlyMoveTheFixedThreeDToTwoDTransition() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        config.getCategory("client").get("SS_Cloud_Detail_Distance_Blocks").set(65536);
        settings.loadFromConfig(config, "client");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedSmallWorldFieldCellsAreRejectedAtConfigLoadNotInTheRenderLoop() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        config.getCategory("client").get("SS_Cloud_Cell_Size").set(4.0);
        settings.loadFromConfig(config, "client");
    }

    @Test
    public void ownCloudDefaultsAreIndependentOfTheLegacyRendererMode() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        settings.loadFromConfig(config, "client");
        assertTrue(settings.renderOwnClouds);
        assertEquals(128.0, settings.ownCloudBaseY, 0.0);
        assertEquals(12.0, settings.ownCloudCellSize, 0.0);
        assertEquals(4.0, settings.ownCloudCellHeight, 0.0);
        assertEquals(8, settings.ownCloudLayers);
        assertEquals(48, settings.ownCloudRadiusCells);
        assertEquals(0, settings.ownCloudSeed);
        assertEquals(0.45, settings.ownCloudCoverage, 0.0);
        assertFalse(settings.ownCloudWorleyEnabled);
		assertEquals(0.2, settings.ownCloudErosion, 0.0);
		assertEquals(0.58, settings.ownCloudBottomBrightness, 0.0);
		assertEquals(0.05, settings.ownCloudCurvatureErrorMeters, 0.0);
		assertEquals(128.0, settings.ownCloudLodFineTransitionBlocks, 0.0);
		assertEquals(384.0, settings.ownCloudLodMidTransitionBlocks, 0.0);
		assertEquals(1024.0, settings.ownCloudLodLowTransitionBlocks, 0.0);
        assertTrue(settings.renderHorizonClouds);
        assertEquals(16384, settings.ownCloudHorizonBlocks);
        assertFalse(settings.ownCloudCullFine);
        assertTrue(settings.ownCloudCullMid);
        assertTrue(settings.ownCloudCullLow);
        assertTrue(settings.ownCloudCullVeryLow);
        assertEquals(0.5, settings.ringworldBoardDotRadius, 0.0);
        assertEquals(1.0, settings.ringworldBoardDotBrightness, 0.0);
        config.getCategory("client").get("Low_Power_Renderer").set(true);
        settings.loadFromConfig(config, "client");
        assertTrue(settings.lowPowerRenderer);
        assertTrue(settings.renderOwnClouds);
    }

    @Test
    public void loadsOwnCloudSwitchAndGeometryThroughExistingConfiguration() {
        ClientSettings settings = new ClientSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "client");
        config.getCategory("client").get("Render_SS_Clouds").set(false);
        config.getCategory("client").get("SS_Cloud_Base_Y").set(240.0);
        config.getCategory("client").get("SS_Cloud_Cell_Size").set(16.0);
        config.getCategory("client").get("SS_Cloud_Cell_Height").set(8.0);
        config.getCategory("client").get("SS_Cloud_Radius_Cells").set(24);
        config.getCategory("client").get("SS_Cloud_Seed").set(71);
        config.getCategory("client").get("SS_Cloud_Coverage").set(0.25);
        config.getCategory("client").get("SS_Cloud_Worley_Enabled").set(true);
		config.getCategory("client").get("SS_Cloud_Worley_Erosion").set(0.7);
		config.getCategory("client").get("SS_Cloud_Bottom_Brightness_Percent").set(42.0);
		config.getCategory("client").get("SS_Cloud_Curvature_Error_Meters").set(1.25);
		config.getCategory("client").get("SS_Cloud_LOD_Fine_Transition_Blocks").set(64.0);
		config.getCategory("client").get("SS_Cloud_LOD_Mid_Transition_Blocks").set(192.0);
		config.getCategory("client").get("SS_Cloud_LOD_Low_Transition_Blocks").set(512.0);
        config.getCategory("client").get("Render_SS_Horizon_Clouds").set(false);
		config.getCategory("client").get("SS_Cloud_Detail_Distance_Blocks").set(16384);
        config.getCategory("client").get("SS_Cloud_Cull_Fine").set(true);
        config.getCategory("client").get("SS_Cloud_Cull_Mid").set(false);
        config.getCategory("client").get("SS_Cloud_Cull_Low").set(false);
        config.getCategory("client").get("SS_Cloud_Cull_Very_Low").set(false);
        settings.loadFromConfig(config, "client");
        assertFalse(settings.renderOwnClouds);
        assertEquals(240.0, settings.ownCloudBaseY, 0.0);
        assertEquals(16.0, settings.ownCloudCellSize, 0.0);
        assertEquals(8.0, settings.ownCloudCellHeight, 0.0);
        assertEquals(24, settings.ownCloudRadiusCells);
        assertEquals(71, settings.ownCloudSeed);
        assertEquals(0.25, settings.ownCloudCoverage, 0.0);
        assertTrue(settings.ownCloudWorleyEnabled);
		assertEquals(0.7, settings.ownCloudErosion, 0.0);
		assertEquals(0.42, settings.ownCloudBottomBrightness, 0.0);
		assertEquals(1.25, settings.ownCloudCurvatureErrorMeters, 0.0);
		assertEquals(64.0, settings.ownCloudLodFineTransitionBlocks, 0.0);
		assertEquals(192.0, settings.ownCloudLodMidTransitionBlocks, 0.0);
		assertEquals(512.0, settings.ownCloudLodLowTransitionBlocks, 0.0);
        assertFalse(settings.renderHorizonClouds);
        assertEquals(16384, settings.ownCloudHorizonBlocks);
        assertTrue(settings.ownCloudCullFine);
        assertFalse(settings.ownCloudCullMid);
        assertFalse(settings.ownCloudCullLow);
        assertFalse(settings.ownCloudCullVeryLow);
        assertTrue(settings.renderAtmosphere);
    }

	@Test
	public void zeroBrightnessAndZeroTransitionWidthsRemainExplicitHardCuts() {
		ClientSettings settings = new ClientSettings();
		Configuration config = new Configuration();
		settings.setupConfig(config, "client");
		config.getCategory("client").get("SS_Cloud_Bottom_Brightness_Percent").set(0.0);
		config.getCategory("client").get("SS_Cloud_Curvature_Error_Meters").set(0.0);
		config.getCategory("client").get("SS_Cloud_LOD_Fine_Transition_Blocks").set(0.0);
		config.getCategory("client").get("SS_Cloud_LOD_Mid_Transition_Blocks").set(0.0);
		config.getCategory("client").get("SS_Cloud_LOD_Low_Transition_Blocks").set(0.0);
		settings.loadFromConfig(config, "client");
		assertEquals(0.0, settings.ownCloudBottomBrightness, 0.0);
		assertEquals(0.0, settings.ownCloudCurvatureErrorMeters, 0.0);
		assertEquals(0.0, settings.ownCloudLodFineTransitionBlocks, 0.0);
		assertEquals(0.0, settings.ownCloudLodMidTransitionBlocks, 0.0);
		assertEquals(0.0, settings.ownCloudLodLowTransitionBlocks, 0.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void outOfRangeFineTransitionIsRejectedAtConfigLoad() {
		ClientSettings settings = new ClientSettings();
		Configuration config = new Configuration();
		settings.setupConfig(config, "client");
		config.getCategory("client").get("SS_Cloud_LOD_Fine_Transition_Blocks").set(257.0);
		settings.loadFromConfig(config, "client");
	}

	@Test(expected = IllegalArgumentException.class)
	public void curvatureErrorAboveThePlanarizationLimitIsRejectedAtConfigLoad() {
		ClientSettings settings = new ClientSettings();
		Configuration config = new Configuration();
		settings.setupConfig(config, "client");
		config.getCategory("client").get("SS_Cloud_Curvature_Error_Meters").set(16.01);
		settings.loadFromConfig(config, "client");
	}

	@Test(expected = IllegalArgumentException.class)
	public void nonFiniteCurvatureErrorIsRejectedAtConfigLoad() {
		ClientSettings settings = new ClientSettings();
		Configuration config = new Configuration();
		settings.setupConfig(config, "client");
		config.getCategory("client").get("SS_Cloud_Curvature_Error_Meters").set(Double.NaN);
		settings.loadFromConfig(config, "client");
	}
}
