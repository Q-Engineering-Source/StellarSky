package stellarium.client;

import net.minecraftforge.common.config.Configuration;
import stellarium.client.ring.cloud.CloudGeometrySettings;
import stellarium.client.ring.cloud.CloudFieldSettings;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarapi.api.lib.config.SimpleHierarchicalConfig;
import stellarapi.api.lib.config.property.ConfigPropertyBoolean;
import stellarapi.api.lib.config.property.ConfigPropertyDouble;
import stellarapi.api.lib.config.property.ConfigPropertyInteger;

public class ClientSettings extends SimpleHierarchicalConfig {

	public float mag_Limit;
	public SkyRendererMode rendererMode;
	public boolean lowPowerRenderer;
	public boolean renderAtmosphere;
	public boolean renderPostProcessing;
	public boolean renderBrightStars;
	public boolean renderSolarSystem;
	public boolean renderMoon = true;
	public boolean renderMilkyWay;
	public boolean renderDeepSky;
	public boolean renderDeepSkyCatalog;
	public boolean renderDeepSkyImages;
	public boolean renderDisplayOverlays;
	public boolean renderLandscape;
	public boolean showTimeMultiplierHud;
	/** Independent of the vanilla Clouds option, which SS holds off. */
	public boolean renderOwnClouds = true;
	public double ownCloudBaseY = 128.0;
	public double ownCloudCellSize = 12.0;
	public double ownCloudCellHeight = 4.0;
	public int ownCloudLayers = 8;
	public int ownCloudRadiusCells = 48;
	public int ownCloudSeed = 0;
	public double ownCloudCoverage = 0.45;
	public boolean ownCloudWorleyEnabled = false;
	public double ownCloudErosion = 0.2;
	/** Local underside transmission multiplier, loaded from SS_Cloud_Bottom_Brightness_Percent. */
	public double ownCloudBottomBrightness = 0.58;
	/** Maximum permitted planarization height error for near-cloud segments; zero preserves the exact path. */
	public double ownCloudCurvatureErrorMeters = 0.05;
	public double ownCloudLodFineTransitionBlocks = 128.0;
	public double ownCloudLodMidTransitionBlocks = 384.0;
	public double ownCloudLodLowTransitionBlocks = 1024.0;
	public boolean renderHorizonClouds = true;
	public int ownCloudHorizonBlocks = 16384;
	public boolean ownCloudCullFine = false;
	public boolean ownCloudCullMid = true;
	public boolean ownCloudCullLow = true;
	public boolean ownCloudCullVeryLow = true;
	/** Purely local visual preferences for the opaque ringworld sunshade board. */
	public boolean renderRingworldBoardDots = true;
	public float ringworldBoardDotPitch = 256.0f;
	public float ringworldBoardDotRadius = 0.5f;
	public float ringworldBoardDotBrightness = 1.0f;
	public float ringworldBoardDotPerimeterInset = 16.0f;
	public float ringworldBoardDotOffProbability = 0.25f;
	public int ringworldBoardDotSeed = 0;
	public boolean ringworldBoardDotPulseEnabled = false;
	public float ringworldBoardDotPulsePeriodSeconds = 10.0f;
	public boolean ringworldBoardDotEdgeProfileEnabled = false;
	public float ringworldBoardDotEdgeBandPercent = 15.0f;
	public float ringworldBoardDotEdgeBrightnessPercent = 100.0f;
	public float ringworldBoardDotCenterBrightnessPercent = 20.0f;
	public float ringworldBoardDotEdgeTransitionPercent = 5.0f;
	public float extendedStarMagnitudeLimit;
	public float extendedStarBrightness;
	public float extendedDeepSkyMagnitudeLimit;
	public float extendedMilkyWayBrightness;

	private ConfigPropertyDouble propMagLimit;
	private ConfigPropertyBoolean propExtendedRenderer, propLowPowerRenderer;
	private ConfigPropertyBoolean propRenderAtmosphere, propRenderPostProcessing, propRenderBrightStars,
			propRenderSolarSystem, propRenderMoon, propRenderMilkyWay, propRenderDeepSky, propRenderDeepSkyCatalog,
			propRenderDeepSkyImages, propRenderDisplayOverlays, propRenderLandscape,
			propShowTimeMultiplierHud, propRenderRingworldBoardDots;
	private ConfigPropertyDouble propExtendedStarMagnitudeLimit, propExtendedStarBrightness,
			propExtendedDeepSkyMagnitudeLimit, propExtendedMilkyWayBrightness, propRingworldBoardDotPitch,
			propRingworldBoardDotRadius, propRingworldBoardDotBrightness, propRingworldBoardDotPerimeterInset;

	private boolean isDirty = false;
	private final ConfigPropertyBoolean propRenderOwnClouds = new ConfigPropertyBoolean("Render_SS_Clouds", "", true);
	private final ConfigPropertyDouble propOwnCloudBaseY = new ConfigPropertyDouble("SS_Cloud_Base_Y", "", 128.0);
	private final ConfigPropertyDouble propOwnCloudCellSize = new ConfigPropertyDouble("SS_Cloud_Cell_Size", "", 12.0);
	private final ConfigPropertyDouble propOwnCloudCellHeight = new ConfigPropertyDouble("SS_Cloud_Cell_Height", "", 4.0);
	private final ConfigPropertyInteger propOwnCloudLayers = new ConfigPropertyInteger("SS_Cloud_Layers", "", 8);
	private final ConfigPropertyInteger propOwnCloudRadius = new ConfigPropertyInteger("SS_Cloud_Radius_Cells", "", 48);
	private final ConfigPropertyInteger propOwnCloudSeed = new ConfigPropertyInteger("SS_Cloud_Seed", "", 0);
	private final ConfigPropertyDouble propOwnCloudCoverage = new ConfigPropertyDouble("SS_Cloud_Coverage", "", 0.45);
	private final ConfigPropertyBoolean propOwnCloudWorley = new ConfigPropertyBoolean("SS_Cloud_Worley_Enabled", "", false);
	private final ConfigPropertyDouble propOwnCloudErosion = new ConfigPropertyDouble("SS_Cloud_Worley_Erosion", "", 0.2);
	private final ConfigPropertyDouble propOwnCloudBottomBrightness = new ConfigPropertyDouble("SS_Cloud_Bottom_Brightness_Percent", "", 58.0);
	private final ConfigPropertyDouble propOwnCloudCurvatureError = new ConfigPropertyDouble("SS_Cloud_Curvature_Error_Meters", "", 0.05);
	private final ConfigPropertyDouble propOwnCloudLodFineTransition = new ConfigPropertyDouble("SS_Cloud_LOD_Fine_Transition_Blocks", "", 128.0);
	private final ConfigPropertyDouble propOwnCloudLodMidTransition = new ConfigPropertyDouble("SS_Cloud_LOD_Mid_Transition_Blocks", "", 384.0);
	private final ConfigPropertyDouble propOwnCloudLodLowTransition = new ConfigPropertyDouble("SS_Cloud_LOD_Low_Transition_Blocks", "", 1024.0);
	private final ConfigPropertyBoolean propHorizonClouds = new ConfigPropertyBoolean("Render_SS_Horizon_Clouds", "", true);
	private final ConfigPropertyInteger propCloudHorizon = new ConfigPropertyInteger("SS_Cloud_Detail_Distance_Blocks", "", 16384);
	private final ConfigPropertyBoolean propCloudCullFine = new ConfigPropertyBoolean("SS_Cloud_Cull_Fine", "", false);
	private final ConfigPropertyBoolean propCloudCullMid = new ConfigPropertyBoolean("SS_Cloud_Cull_Mid", "", true);
	private final ConfigPropertyBoolean propCloudCullLow = new ConfigPropertyBoolean("SS_Cloud_Cull_Low", "", true);
	private final ConfigPropertyBoolean propCloudCullVeryLow = new ConfigPropertyBoolean("SS_Cloud_Cull_Very_Low", "", true);
	private final ConfigPropertyDouble propDotOffPercent = new ConfigPropertyDouble("Ringworld_Board_Dot_Off_Percent", "", 25.0);
	private final ConfigPropertyInteger propDotSeed = new ConfigPropertyInteger("Ringworld_Board_Dot_Seed", "", 0);
	private final ConfigPropertyBoolean propDotPulse = new ConfigPropertyBoolean("Ringworld_Board_Dot_Pulse_Enabled", "", false);
	private final ConfigPropertyDouble propDotPulsePeriod = new ConfigPropertyDouble("Ringworld_Board_Dot_Pulse_Period_Seconds", "", 10.0);
	private final ConfigPropertyBoolean propDotEdgeProfile = new ConfigPropertyBoolean("Ringworld_Board_Dot_Edge_Profile_Enabled", "", false);
	private final ConfigPropertyDouble propDotEdgeBand = new ConfigPropertyDouble("Ringworld_Board_Dot_Edge_Band_Percent", "", 15.0);
	private final ConfigPropertyDouble propDotEdgeBrightness = new ConfigPropertyDouble("Ringworld_Board_Dot_Edge_Brightness_Percent", "", 100.0);
	private final ConfigPropertyDouble propDotCenterBrightness = new ConfigPropertyDouble("Ringworld_Board_Dot_Center_Brightness_Percent", "", 20.0);
	private final ConfigPropertyDouble propDotEdgeTransition = new ConfigPropertyDouble("Ringworld_Board_Dot_Edge_Transition_Percent", "", 5.0);

	public ClientSettings() {
		this.propMagLimit = new ConfigPropertyDouble("Mag_Limit", "", 4.5);
		this.propExtendedRenderer = new ConfigPropertyBoolean("Extended_Renderer", "", false);
		this.propLowPowerRenderer = new ConfigPropertyBoolean("Low_Power_Renderer", "", false);
		this.propRenderAtmosphere = new ConfigPropertyBoolean("Render_Atmosphere", "", true);
		this.propRenderPostProcessing = new ConfigPropertyBoolean("Render_Post_Processing", "", true);
		this.propRenderBrightStars = new ConfigPropertyBoolean("Render_Bright_Stars", "", true);
		this.propRenderSolarSystem = new ConfigPropertyBoolean("Render_Solar_System", "", true);
		this.propRenderMoon = new ConfigPropertyBoolean("Render_Moon", "", true);
		this.propRenderMilkyWay = new ConfigPropertyBoolean("Render_Milky_Way", "", true);
		this.propRenderDeepSky = new ConfigPropertyBoolean("Render_Deep_Sky", "", true);
		this.propRenderDeepSkyCatalog = new ConfigPropertyBoolean("Render_Deep_Sky_Catalog", "", true);
		this.propRenderDeepSkyImages = new ConfigPropertyBoolean("Render_Deep_Sky_Images", "", true);
		this.propRenderDisplayOverlays = new ConfigPropertyBoolean("Render_Display_Overlays", "", true);
		this.propRenderLandscape = new ConfigPropertyBoolean("Render_Landscape", "", true);
		this.propShowTimeMultiplierHud = new ConfigPropertyBoolean("Show_Time_Multiplier_Hud", "", true);
		this.propRenderRingworldBoardDots = new ConfigPropertyBoolean("Render_Ringworld_Board_Dots", "", true);
		this.propRingworldBoardDotPitch = new ConfigPropertyDouble("Ringworld_Board_Dot_Pitch", "", 256.0);
		this.propRingworldBoardDotRadius = new ConfigPropertyDouble("Ringworld_Board_Dot_Radius", "", 0.5);
		this.propRingworldBoardDotBrightness = new ConfigPropertyDouble("Ringworld_Board_Dot_Brightness", "", 1.0);
		this.propRingworldBoardDotPerimeterInset =
				new ConfigPropertyDouble("Ringworld_Board_Dot_Perimeter_Inset", "", 16.0);
		this.propExtendedStarMagnitudeLimit =
				new ConfigPropertyDouble("Extended_Star_Magnitude_Limit", "", 10.5);
		this.propExtendedStarBrightness =
				new ConfigPropertyDouble("Extended_Star_Brightness", "", 1.0);
		this.propExtendedDeepSkyMagnitudeLimit =
				new ConfigPropertyDouble("Extended_Deep_Sky_Magnitude_Limit", "", 12.0);
		this.propExtendedMilkyWayBrightness =
				new ConfigPropertyDouble("Extended_Milky_Way_Brightness", "", 1.0);

		this.addConfigProperty(this.propMagLimit);
		this.addConfigProperty(this.propExtendedRenderer);
		this.addConfigProperty(this.propLowPowerRenderer);
		this.addConfigProperty(this.propRenderAtmosphere);
		this.addConfigProperty(this.propRenderPostProcessing);
		this.addConfigProperty(this.propRenderBrightStars);
		this.addConfigProperty(this.propRenderSolarSystem);
		this.addConfigProperty(this.propRenderMoon);
		this.addConfigProperty(this.propRenderMilkyWay);
		this.addConfigProperty(this.propRenderDeepSky);
		this.addConfigProperty(this.propRenderDeepSkyCatalog);
		this.addConfigProperty(this.propRenderDeepSkyImages);
		this.addConfigProperty(this.propRenderDisplayOverlays);
		this.addConfigProperty(this.propRenderLandscape);
		this.addConfigProperty(this.propShowTimeMultiplierHud);
		this.addConfigProperty(this.propRenderRingworldBoardDots);
		this.addConfigProperty(this.propRingworldBoardDotPitch);
		this.addConfigProperty(this.propRingworldBoardDotRadius);
		this.addConfigProperty(this.propRingworldBoardDotBrightness);
		this.addConfigProperty(this.propRingworldBoardDotPerimeterInset);
		this.addConfigProperty(this.propExtendedStarMagnitudeLimit);
		this.addConfigProperty(this.propExtendedStarBrightness);
		this.addConfigProperty(this.propExtendedDeepSkyMagnitudeLimit);
		this.addConfigProperty(this.propExtendedMilkyWayBrightness);
		this.addConfigProperty(this.propRenderOwnClouds);
		this.addConfigProperty(this.propOwnCloudBaseY);
		this.addConfigProperty(this.propOwnCloudCellSize);
		this.addConfigProperty(this.propOwnCloudCellHeight);
		this.addConfigProperty(this.propOwnCloudLayers);
		this.addConfigProperty(this.propOwnCloudRadius);
		this.addConfigProperty(this.propOwnCloudSeed);
		this.addConfigProperty(this.propOwnCloudCoverage);
		this.addConfigProperty(this.propOwnCloudWorley);
		this.addConfigProperty(this.propOwnCloudErosion);
		this.addConfigProperty(this.propOwnCloudBottomBrightness);
		this.addConfigProperty(this.propOwnCloudCurvatureError);
		this.addConfigProperty(this.propOwnCloudLodFineTransition);
		this.addConfigProperty(this.propOwnCloudLodMidTransition);
		this.addConfigProperty(this.propOwnCloudLodLowTransition);
		this.addConfigProperty(this.propHorizonClouds);
		this.addConfigProperty(this.propCloudHorizon);
		this.addConfigProperty(this.propCloudCullFine);
		this.addConfigProperty(this.propCloudCullMid);
		this.addConfigProperty(this.propCloudCullLow);
		this.addConfigProperty(this.propCloudCullVeryLow);
		this.addConfigProperty(this.propDotOffPercent);
		this.addConfigProperty(this.propDotSeed);
		this.addConfigProperty(this.propDotPulse);
		this.addConfigProperty(this.propDotPulsePeriod);
		this.addConfigProperty(this.propDotEdgeProfile);
		this.addConfigProperty(this.propDotEdgeBand);
		this.addConfigProperty(this.propDotEdgeBrightness);
		this.addConfigProperty(this.propDotCenterBrightness);
		this.addConfigProperty(this.propDotEdgeTransition);
	}

	@Override
	public void setupConfig(Configuration config, String category) {
		config.setCategoryComment(category, "Configurations for client rendering and overlays.");
		config.setCategoryLanguageKey(category, "config.category.client");
		config.setCategoryRequiresMcRestart(category, false);

		super.setupConfig(config, category);
		propMagLimit.setComment("Legacy renderer naked-eye magnitude limit.");
		propMagLimit.setRequiresMcRestart(true);
		propMagLimit.setLanguageKey("config.property.client.maglimit");
		propMagLimit.setMinValue(3.0);
		propMagLimit.setMaxValue(7.0);

		propExtendedRenderer.setComment("Use the batched Stellarium catalogue renderer. "
				+ "Disable to retain the original Stellar Sky renderer.");
		propLowPowerRenderer.setComment("Render only the solar system and stars. "
				+ "Skips atmospheric scattering, post-processing, the Milky Way, and deep-sky objects.");
		propRenderAtmosphere.setComment("Render atmospheric scattering and refraction.");
		propRenderPostProcessing.setComment("Render stellar post-processing effects.");
		propRenderBrightStars.setComment("Render the active mode's star layer.");
		propRenderSolarSystem.setComment("Render the sun, moon, and solar-system objects.");
		propRenderMoon.setComment("Render the moon and make it available for celestial object information.");
		propRenderOwnClouds.setComment("Render SS-owned closed procedural clouds in supported ringworld scenes. "
				+ "Vanilla clouds stay OFF independently; DH cloud compatibility is not included.");
		propOwnCloudBaseY.setComment("World Y of the bottom of the SS cloud volume. Clouds are clipped and capped to the air profile.");
		propOwnCloudBaseY.setMinValue(-4096.0);
		propOwnCloudBaseY.setMaxValue(4096.0);
		propOwnCloudCellSize.setComment("Horizontal size of a cloud voxel in blocks. The current finite world-field cache requires "
				+ "at least 12 blocks to cover every fixed LOD band and its streaming margin. Smaller values are rejected "
				+ "during configuration loading, not silently rescaled. Cosmetic movement is +X at 0.03 blocks per tick.");
		propOwnCloudCellSize.setMinValue(12.0);
		propOwnCloudCellSize.setMaxValue(64.0);
		propOwnCloudCellHeight.setComment("Physical height of one effective cloud layer in blocks. Total cloud thickness is "
				+ "SS_Cloud_Layers multiplied by this value, then clipped to the air profile. For a 32-block cloud volume, "
				+ "use 8 layers at height 4, 4 layers at height 8, or 2 layers at height 16.");
		propOwnCloudCellHeight.setMinValue(1.0);
		propOwnCloudCellHeight.setMaxValue(16.0);
		propOwnCloudLayers.setComment("Effective vertical cloud layers: 2, 4, or 8. Fewer layers coarsen the same cloud field "
				+ "by majority within each group, with a strongest-group fallback preserving horizontal column coverage. "
				+ "Sparse vertical details may disappear. Existing configurations default to 8; 3 and other values are rejected.");
		propOwnCloudLayers.setMinValue(2);
		propOwnCloudLayers.setMaxValue(8);
		propOwnCloudRadius.setComment("Guaranteed cloud coverage radius in cells. The cache adds 16 cells of overscan "
				+ "for coarse recentering; the total mesh radius never exceeds 64. Vanilla scene far clipping still applies.");
		propOwnCloudRadius.setMinValue(16);
		propOwnCloudRadius.setMaxValue(48);
		propOwnCloudSeed.setComment("Deterministic seed shared by near and horizon cloud shapes.");
		propOwnCloudCoverage.setComment("Fraction of horizontal cloud columns eligible for clouds (0 = clear sky, 1 = overcast). "
				+ "The same footprint applies through every vertical layer; detail cannot fill excluded sky openings.");
		propOwnCloudCoverage.setMinValue(0.0);
		propOwnCloudCoverage.setMaxValue(1.0);
		propOwnCloudWorley.setComment("Optionally erode the 3D Perlin cloud edges with cellular Worley noise.");
		propOwnCloudErosion.setComment("Strength of optional Worley shaping; independent of cloud coverage and height.");
		propOwnCloudErosion.setMinValue(0.0);
		propOwnCloudErosion.setMaxValue(1.0);
		propOwnCloudBottomBrightness.setComment("Brightness percentage for the lower cloud surface. 0 is black and 100 is full brightness.");
		propOwnCloudBottomBrightness.setMinValue(0.0);
		propOwnCloudBottomBrightness.setMaxValue(100.0);
		propOwnCloudCurvatureError.setComment("Maximum planarization height error in meters for near-cloud segments; follows the actual ring radius automatically. 0 disables planarization and retains curved geometry. Default 0.05m.");
		propOwnCloudCurvatureError.setMinValue(0.0);
		propOwnCloudCurvatureError.setMaxValue(16.0);
		propOwnCloudLodFineTransition.setComment("Total blend width in blocks centered on the 512-block fine-to-mid cloud LOD boundary. 0 makes a hard transition.");
		propOwnCloudLodFineTransition.setMinValue(0.0);
		propOwnCloudLodFineTransition.setMaxValue(256.0);
		propOwnCloudLodMidTransition.setComment("Total blend width in blocks centered on the 2048-block mid-to-low cloud LOD boundary. 0 makes a hard transition.");
		propOwnCloudLodMidTransition.setMinValue(0.0);
		propOwnCloudLodMidTransition.setMaxValue(768.0);
		propOwnCloudLodLowTransition.setComment("Total blend width in blocks centered on the 8192-block low-to-very-low cloud LOD boundary. 0 makes a hard transition.");
		propOwnCloudLodLowTransition.setMinValue(0.0);
		propOwnCloudLodLowTransition.setMaxValue(2048.0);
		propHorizonClouds.setComment("Extend the same closed cloud field beyond the Minecraft scene far plane. "
				+ "Shares physical depth with the sunshade and spatial atmosphere; not DH clouds.");
		propCloudHorizon.setComment("Fixed 3D-to-2D transition at 16384 blocks, preserving the configured release LOD contract. "
				+ "This legacy key is retained for explicit diagnostics; values other than 16384 are rejected, not silently remapped. "
				+ "Farther clouds continue through the 2D horizon tiers.");
		propCloudHorizon.setMinValue(16384);
		propCloudHorizon.setMaxValue(16384);
		propCloudCullFine.setComment("Cull back faces in fine 3D clouds (0-512 blocks). Default false preserves inside-cloud views.");
		propCloudCullMid.setComment("Cull back faces in mid 3D clouds (512-2048 blocks).");
		propCloudCullLow.setComment("Cull back faces in low 3D clouds (2048-8192 blocks).");
		propCloudCullVeryLow.setComment("Cull back faces in very-low 3D clouds (8192-16384 blocks by default). 2D LODs remain two-sided.");
		propRenderMilkyWay.setComment("Render the active mode's Milky Way layer.");
		propRenderDeepSky.setComment("Master switch for deep-sky rendering.");
		propRenderDeepSkyCatalog.setComment("Render the extended catalogue of galaxies, nebulae, and clusters.");
		propRenderDeepSkyImages.setComment("Render the original textured Messier objects.");
		propRenderDisplayOverlays.setComment("Render celestial grids and display overlays.");
		propRenderLandscape.setComment("Render Stellar Sky landscape silhouettes.");
		propShowTimeMultiplierHud.setComment("Show the movable time status overlay.");
		propRenderRingworldBoardDots.setComment("Render the faint blue maintenance dots on ringworld board undersides and sides.");
		propRingworldBoardDotPitch.setComment("Distance in blocks between ringworld board dot centers."
				+ " This is a client-only visual preference.");
		propRingworldBoardDotPitch.setMinValue(4.0);
		propRingworldBoardDotPitch.setMaxValue(1024.0);
		propRingworldBoardDotRadius.setComment("Radius in blocks of one ringworld board dot.");
		propRingworldBoardDotRadius.setMinValue(0.1);
		propRingworldBoardDotRadius.setMaxValue(2.0);
		propRingworldBoardDotBrightness.setComment("Emission multiplier for full-bright (light level 15) pale-blue board dots. "
				+ "Independent of local skylight; does not emit block light. Atmosphere and opaque occlusion still apply.");
		propRingworldBoardDotBrightness.setMinValue(0.0);
		propRingworldBoardDotBrightness.setMaxValue(4.0);
		propRingworldBoardDotPerimeterInset.setComment("Distance in blocks kept clear along horizontal ringworld board perimeters."
				+ " Vertical side rows remain at the board mid-height.");
		propRingworldBoardDotPerimeterInset.setMinValue(0.0);
		propRingworldBoardDotPerimeterInset.setMaxValue(4096.0);
		propDotOffPercent.setComment("Percentage of physical board lights permanently off for this seed. "
				+ "0 keeps all lights; 100 disables all. This never changes per frame or revives through pulse animation.");
		propDotOffPercent.setMinValue(0.0);
		propDotOffPercent.setMaxValue(100.0);
		propDotSeed.setComment("Deterministic board-light fault pattern seed. Same seed and physical light identity keep the same state.");
		propDotPulse.setComment("Optional slow brightness pulse for functioning lights only. Permanent faults remain off.");
		propDotPulsePeriod.setComment("Seconds per optional slow pulse at 20 world-simulation ticks per second.");
		propDotPulsePeriod.setMinValue(1.0);
		propDotPulsePeriod.setMaxValue(3600.0);
		propDotEdgeProfile.setComment("Optionally make the two Z-width edges brighter and the center darker. "
				+ "This is a board-wide light distribution, not a ring inside each dot. Permanent faults still dominate.");
		propDotEdgeBand.setComment("Width of EACH bright edge band as a percentage of the full board Z width. "
				+ "15 means 15% left and 15% right, leaving 70% between them. 0 disables bright bands; 50 joins them.");
		propDotEdgeBand.setMinValue(0.0);
		propDotEdgeBand.setMaxValue(50.0);
		propDotEdgeBrightness.setComment("Emission percentage in both bright edge bands, relative to Board_Dot_Brightness.");
		propDotEdgeBrightness.setMinValue(0.0);
		propDotEdgeBrightness.setMaxValue(100.0);
		propDotCenterBrightness.setComment("Emission percentage in the central band; 0 makes its functioning dots dark.");
		propDotCenterBrightness.setMinValue(0.0);
		propDotCenterBrightness.setMaxValue(100.0);
		propDotEdgeTransition.setComment("Width of the smooth transition inward from EACH bright edge band, "
				+ "as a percentage of full board width. Limited to the available half-center; 0 makes a hard transition.");
		propDotEdgeTransition.setMinValue(0.0);
		propDotEdgeTransition.setMaxValue(50.0);

		propExtendedStarMagnitudeLimit.setComment("Faintest star loaded by the extended renderer.");
		propExtendedStarMagnitudeLimit.setMinValue(6.0);
		propExtendedStarMagnitudeLimit.setMaxValue(10.5);
		propExtendedStarBrightness.setComment("Brightness multiplier for extended stars.");
		propExtendedStarBrightness.setMinValue(0.1);
		propExtendedStarBrightness.setMaxValue(4.0);
		propExtendedDeepSkyMagnitudeLimit.setComment("Faintest catalogue deep-sky object to render.");
		propExtendedDeepSkyMagnitudeLimit.setMinValue(6.0);
		propExtendedDeepSkyMagnitudeLimit.setMaxValue(20.0);
		propExtendedMilkyWayBrightness.setComment("Brightness multiplier for the extended Milky Way.");
		propExtendedMilkyWayBrightness.setMinValue(0.0);
		propExtendedMilkyWayBrightness.setMaxValue(4.0);
	}

	@Override
	public void loadFromConfig(Configuration config, String category) {
		super.loadFromConfig(config, category);

		this.mag_Limit = (float) propMagLimit.getDouble();
		this.rendererMode = propExtendedRenderer.getBoolean()
				? SkyRendererMode.EXTENDED : SkyRendererMode.LEGACY;
		this.lowPowerRenderer = propLowPowerRenderer.getBoolean();
		this.renderAtmosphere = propRenderAtmosphere.getBoolean();
		this.renderPostProcessing = propRenderPostProcessing.getBoolean();
		this.renderBrightStars = propRenderBrightStars.getBoolean();
		this.renderSolarSystem = propRenderSolarSystem.getBoolean();
		this.renderMoon = propRenderMoon.getBoolean();
		this.renderMilkyWay = propRenderMilkyWay.getBoolean();
		this.renderDeepSky = propRenderDeepSky.getBoolean();
		this.renderDeepSkyCatalog = propRenderDeepSkyCatalog.getBoolean();
		this.renderDeepSkyImages = propRenderDeepSkyImages.getBoolean();
		this.renderDisplayOverlays = propRenderDisplayOverlays.getBoolean();
		this.renderLandscape = propRenderLandscape.getBoolean();
		this.showTimeMultiplierHud = propShowTimeMultiplierHud.getBoolean();
		this.renderOwnClouds = propRenderOwnClouds.getBoolean();
		this.ownCloudBaseY = propOwnCloudBaseY.getDouble();
		this.ownCloudCellSize = propOwnCloudCellSize.getDouble();
		this.ownCloudCellHeight = propOwnCloudCellHeight.getDouble();
		this.ownCloudLayers = CloudFieldSettings.requireLayers(propOwnCloudLayers.getInt());
		this.ownCloudRadiusCells = propOwnCloudRadius.getInt();
		this.ownCloudSeed = propOwnCloudSeed.getInt();
		this.ownCloudCoverage = propOwnCloudCoverage.getDouble();
		this.ownCloudWorleyEnabled = propOwnCloudWorley.getBoolean();
		this.ownCloudErosion = propOwnCloudErosion.getDouble();
		double bottomBrightnessPercent = propOwnCloudBottomBrightness.getDouble();
		this.ownCloudBottomBrightness = requireCloudRange("SS_Cloud_Bottom_Brightness_Percent", bottomBrightnessPercent, 0.0, 100.0) / 100.0;
		this.ownCloudCurvatureErrorMeters = requireCloudRange("SS_Cloud_Curvature_Error_Meters", propOwnCloudCurvatureError.getDouble(), 0.0, 16.0);
		this.ownCloudLodFineTransitionBlocks = requireCloudRange("SS_Cloud_LOD_Fine_Transition_Blocks", propOwnCloudLodFineTransition.getDouble(), 0.0, 256.0);
		this.ownCloudLodMidTransitionBlocks = requireCloudRange("SS_Cloud_LOD_Mid_Transition_Blocks", propOwnCloudLodMidTransition.getDouble(), 0.0, 768.0);
		this.ownCloudLodLowTransitionBlocks = requireCloudRange("SS_Cloud_LOD_Low_Transition_Blocks", propOwnCloudLodLowTransition.getDouble(), 0.0, 2048.0);
		this.renderHorizonClouds = propHorizonClouds.getBoolean();
		this.ownCloudHorizonBlocks = propCloudHorizon.getInt();
		if (this.ownCloudHorizonBlocks != 16384) {
			throw new IllegalArgumentException("SS_Cloud_Detail_Distance_Blocks must be 16384 to preserve the fixed cloud LOD bands");
		}
		CloudWorldCache.requireBandCoverage(CloudGeometrySettings.forLayers(this.ownCloudCellSize,
				this.ownCloudCellHeight, this.ownCloudLayers, this.ownCloudRadiusCells + 16), this.ownCloudHorizonBlocks);
		this.ownCloudCullFine = propCloudCullFine.getBoolean();
		this.ownCloudCullMid = propCloudCullMid.getBoolean();
		this.ownCloudCullLow = propCloudCullLow.getBoolean();
		this.ownCloudCullVeryLow = propCloudCullVeryLow.getBoolean();
		this.renderRingworldBoardDots = propRenderRingworldBoardDots.getBoolean();
		this.ringworldBoardDotPitch = (float) propRingworldBoardDotPitch.getDouble();
		this.ringworldBoardDotRadius = (float) propRingworldBoardDotRadius.getDouble();
		this.ringworldBoardDotBrightness = (float) propRingworldBoardDotBrightness.getDouble();
		this.ringworldBoardDotPerimeterInset = (float) propRingworldBoardDotPerimeterInset.getDouble();
		this.ringworldBoardDotOffProbability = (float) (propDotOffPercent.getDouble() / 100.0);
		this.ringworldBoardDotSeed = propDotSeed.getInt();
		this.ringworldBoardDotPulseEnabled = propDotPulse.getBoolean();
		this.ringworldBoardDotPulsePeriodSeconds = (float) propDotPulsePeriod.getDouble();
		this.ringworldBoardDotEdgeProfileEnabled = propDotEdgeProfile.getBoolean();
		this.ringworldBoardDotEdgeBandPercent = (float) propDotEdgeBand.getDouble();
		this.ringworldBoardDotEdgeBrightnessPercent = (float) propDotEdgeBrightness.getDouble();
		this.ringworldBoardDotCenterBrightnessPercent = (float) propDotCenterBrightness.getDouble();
		this.ringworldBoardDotEdgeTransitionPercent = (float) propDotEdgeTransition.getDouble();
		this.extendedStarMagnitudeLimit = (float) propExtendedStarMagnitudeLimit.getDouble();
		this.extendedStarBrightness = (float) propExtendedStarBrightness.getDouble();
		this.extendedDeepSkyMagnitudeLimit = (float) propExtendedDeepSkyMagnitudeLimit.getDouble();
		this.extendedMilkyWayBrightness = (float) propExtendedMilkyWayBrightness.getDouble();
		this.isDirty = true;
	}

	@Override
	public void saveToConfig(Configuration config, String category) {
		super.saveToConfig(config, category);
	}

	public boolean checkDirty() {
		boolean flag = this.isDirty;
		this.isDirty = false;
		return flag;
	}

	public void markDirty() {
		this.isDirty = true;
	}

	private static double requireCloudRange(String key, double value, double minimum, double maximum) {
		if (!Double.isFinite(value) || value < minimum || value > maximum) {
			throw new IllegalArgumentException(key + " must be finite and within [" + minimum + ", " + maximum + "]");
		}
		return value;
	}
}
