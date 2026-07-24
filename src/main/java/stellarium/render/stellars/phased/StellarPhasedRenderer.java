package stellarium.render.stellars.phased;

import stellarium.render.stellars.access.EnumStellarPass;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.render.stellars.layer.StellarLayerModel;
import stellarium.render.stellars.layer.StellarLayerRenderer;
import stellarium.client.ClientSettings;
import stellarium.render.extended.ExtendedSkyRenderer;
import stellarium.stellars.star.brstar.LayerBrStar;
import stellarium.stellars.system.LayerSolarSystem;
import stellarium.stellars.milkyway.LayerMilkyway;
import stellarium.stellars.deepsky.LayerDeepSky;

public enum StellarPhasedRenderer {
	INSTANCE;

	public void render(StellarRenderModel model, EnumStellarPass pass, LayerRHelper info) {
		render(model, pass, info, info.minecraft == null ? null
				: stellarium.StellarSky.PROXY.getClientSettings());
	}

	public void render(StellarRenderModel model, EnumStellarPass pass, LayerRHelper info, boolean lowPower) {
		render(model, pass, info, stellarium.StellarSky.PROXY.getClientSettings());
	}

	public void render(StellarRenderModel model, EnumStellarPass pass, LayerRHelper info, ClientSettings settings) {
		// Render all layers
		for(StellarLayerModel layerModel : model.layerModels) {
			if(!shouldRender(layerModel, settings))
				continue;
			StellarLayerRenderer.INSTANCE.render(layerModel, pass, info);
		}
	}

	private boolean shouldRender(StellarLayerModel layerModel, ClientSettings settings) {
		if(settings == null)
			return true;
		Object type = layerModel.getLayerType();
		boolean extended = ExtendedSkyRenderer.INSTANCE.isActive(settings);
		if(settings.lowPowerRenderer) {
			if(type instanceof LayerSolarSystem)
				return settings.renderSolarSystem;
			return type instanceof LayerBrStar && !extended && settings.renderBrightStars;
		}
		if(type instanceof LayerSolarSystem)
			return settings.renderSolarSystem;
		if(type instanceof LayerBrStar)
			return !extended && settings.renderBrightStars;
		if(type instanceof LayerMilkyway)
			return !extended && settings.renderMilkyWay;
		if(type instanceof LayerDeepSky) {
			if(extended)
				return settings.renderDeepSky && settings.renderDeepSkyImages;
			return settings.renderDeepSky;
		}
		return true;
	}
}
