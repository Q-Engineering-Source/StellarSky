package stellarium.client;

import net.minecraftforge.common.config.Configuration;
import stellarapi.api.lib.config.SimpleHierarchicalConfig;
import stellarapi.api.lib.config.property.ConfigPropertyBoolean;
import stellarapi.api.lib.config.property.ConfigPropertyDouble;

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
	public float extendedStarMagnitudeLimit;
	public float extendedStarBrightness;
	public float extendedDeepSkyMagnitudeLimit;
	public float extendedMilkyWayBrightness;

	private ConfigPropertyDouble propMagLimit;
	private ConfigPropertyBoolean propExtendedRenderer, propLowPowerRenderer;
	private ConfigPropertyBoolean propRenderAtmosphere, propRenderPostProcessing, propRenderBrightStars,
			propRenderSolarSystem, propRenderMoon, propRenderMilkyWay, propRenderDeepSky, propRenderDeepSkyCatalog,
			propRenderDeepSkyImages, propRenderDisplayOverlays, propRenderLandscape,
			propShowTimeMultiplierHud;
	private ConfigPropertyDouble propExtendedStarMagnitudeLimit, propExtendedStarBrightness,
			propExtendedDeepSkyMagnitudeLimit, propExtendedMilkyWayBrightness;

	private boolean isDirty = false;

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
		this.addConfigProperty(this.propExtendedStarMagnitudeLimit);
		this.addConfigProperty(this.propExtendedStarBrightness);
		this.addConfigProperty(this.propExtendedDeepSkyMagnitudeLimit);
		this.addConfigProperty(this.propExtendedMilkyWayBrightness);
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
		propRenderMilkyWay.setComment("Render the active mode's Milky Way layer.");
		propRenderDeepSky.setComment("Master switch for deep-sky rendering.");
		propRenderDeepSkyCatalog.setComment("Render the extended catalogue of galaxies, nebulae, and clusters.");
		propRenderDeepSkyImages.setComment("Render the original textured Messier objects.");
		propRenderDisplayOverlays.setComment("Render celestial grids and display overlays.");
		propRenderLandscape.setComment("Render Stellar Sky landscape silhouettes.");
		propShowTimeMultiplierHud.setComment("Show the movable time status overlay.");

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
}
