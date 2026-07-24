package stellarium.client;

import net.minecraftforge.common.config.Configuration;
import stellarapi.api.lib.config.SimpleHierarchicalConfig;
import stellarapi.api.lib.config.property.ConfigPropertyBoolean;
import stellarapi.api.lib.config.property.ConfigPropertyDouble;

public class ClientSettings extends SimpleHierarchicalConfig {

	public float mag_Limit;
	public boolean lowPowerRenderer;
	public boolean renderAtmosphere;
	public boolean renderPostProcessing;
	public boolean renderBrightStars;
	public boolean renderSolarSystem;
	public boolean renderMilkyWay;
	public boolean renderDeepSky;
	public boolean renderDisplayOverlays;
	public boolean renderLandscape;
	public boolean showTimeMultiplierHud;

	private ConfigPropertyDouble propMagLimit;
	private ConfigPropertyBoolean propLowPowerRenderer;
	private ConfigPropertyBoolean propRenderAtmosphere, propRenderPostProcessing, propRenderBrightStars,
			propRenderSolarSystem, propRenderMilkyWay, propRenderDeepSky, propRenderDisplayOverlays,
			propRenderLandscape, propShowTimeMultiplierHud;

	private boolean isDirty = false;

	public ClientSettings() {
		this.propMagLimit = new ConfigPropertyDouble("Mag_Limit", "", 4.5);
		this.propLowPowerRenderer = new ConfigPropertyBoolean("Low_Power_Renderer", "", false);
		this.propRenderAtmosphere = new ConfigPropertyBoolean("Render_Atmosphere", "", true);
		this.propRenderPostProcessing = new ConfigPropertyBoolean("Render_Post_Processing", "", true);
		this.propRenderBrightStars = new ConfigPropertyBoolean("Render_Bright_Stars", "", true);
		this.propRenderSolarSystem = new ConfigPropertyBoolean("Render_Solar_System", "", true);
		this.propRenderMilkyWay = new ConfigPropertyBoolean("Render_Milky_Way", "", true);
		this.propRenderDeepSky = new ConfigPropertyBoolean("Render_Deep_Sky", "", true);
		this.propRenderDisplayOverlays = new ConfigPropertyBoolean("Render_Display_Overlays", "", true);
		this.propRenderLandscape = new ConfigPropertyBoolean("Render_Landscape", "", true);
		this.propShowTimeMultiplierHud = new ConfigPropertyBoolean("Show_Time_Multiplier_Hud", "", true);

		this.addConfigProperty(this.propMagLimit);
		this.addConfigProperty(this.propLowPowerRenderer);
		this.addConfigProperty(this.propRenderAtmosphere);
		this.addConfigProperty(this.propRenderPostProcessing);
		this.addConfigProperty(this.propRenderBrightStars);
		this.addConfigProperty(this.propRenderSolarSystem);
		this.addConfigProperty(this.propRenderMilkyWay);
		this.addConfigProperty(this.propRenderDeepSky);
		this.addConfigProperty(this.propRenderDisplayOverlays);
		this.addConfigProperty(this.propRenderLandscape);
		this.addConfigProperty(this.propShowTimeMultiplierHud);
	}

	@Override
	public void setupConfig(Configuration config, String category) {
		config.setCategoryComment(category, "Configurations for client modifications.\n"
				+ "Most of them are for rendering/view.");
		config.setCategoryLanguageKey(category, "config.category.client");
		config.setCategoryRequiresMcRestart(category, false);

		super.setupConfig(config, category);

		propMagLimit.setComment("Limit of magnitude can be seen on naked eye.\n" +
				"If you want to increase FPS, lower the Mag_Limit.\n" +
				"(Realistic = 6.5, Default = 4.5)\n" +
				"The lower you set it, the fewer stars you will see\n" +
				"but the better FPS you will get");
		propMagLimit.setRequiresMcRestart(true);
		propMagLimit.setLanguageKey("config.property.client.maglimit");
		propMagLimit.setMinValue(3.0);
		propMagLimit.setMaxValue(7.0);

		propLowPowerRenderer.setComment("Render only the solar system and bright stars. "
				+ "Skips atmospheric scattering, post-processing, the Milky Way, and deep-sky objects.");
		propLowPowerRenderer.setRequiresMcRestart(true);
		propRenderAtmosphere.setComment("Render atmospheric scattering and refraction.");
		propRenderPostProcessing.setComment("Render stellar post-processing effects.");
		propRenderBrightStars.setComment("Render the bright-star layer.");
		propRenderSolarSystem.setComment("Render the sun, moon, and solar-system objects.");
		propRenderMilkyWay.setComment("Render the Milky Way layer.");
		propRenderDeepSky.setComment("Render deep-sky objects.");
		propRenderDisplayOverlays.setComment("Render celestial grids and display overlays.");
		propRenderLandscape.setComment("Render StellarSky landscape silhouettes.");
		propShowTimeMultiplierHud.setComment("Show the time multiplier text in the upper-left corner.");
	}

	@Override
	public void loadFromConfig(Configuration config, String category) {
		super.loadFromConfig(config, category);

		this.mag_Limit=(float)propMagLimit.getDouble();
		this.lowPowerRenderer = propLowPowerRenderer.getBoolean();
		this.renderAtmosphere = propRenderAtmosphere.getBoolean();
		this.renderPostProcessing = propRenderPostProcessing.getBoolean();
		this.renderBrightStars = propRenderBrightStars.getBoolean();
		this.renderSolarSystem = propRenderSolarSystem.getBoolean();
		this.renderMilkyWay = propRenderMilkyWay.getBoolean();
		this.renderDeepSky = propRenderDeepSky.getBoolean();
		this.renderDisplayOverlays = propRenderDisplayOverlays.getBoolean();
		this.renderLandscape = propRenderLandscape.getBoolean();
		this.showTimeMultiplierHud = propShowTimeMultiplierHud.getBoolean();
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

	/** Marks runtime client settings for renderer refresh. */
	public void markDirty() {
		this.isDirty = true;
	}
}
