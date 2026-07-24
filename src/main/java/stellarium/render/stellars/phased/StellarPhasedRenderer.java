package stellarium.render.stellars.phased;

import stellarium.render.stellars.access.EnumStellarPass;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.render.stellars.layer.StellarLayerModel;
import stellarium.render.stellars.layer.StellarLayerRenderer;
import stellarium.client.ClientSettings;
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
		if(settings.lowPowerRenderer)
			return type instanceof LayerSolarSystem || type instanceof LayerBrStar;
		if(type instanceof LayerSolarSystem)
			return settings.renderSolarSystem;
		if(type instanceof LayerBrStar)
			return settings.renderBrightStars;
		if(type instanceof LayerMilkyway)
			return settings.renderMilkyWay;
		if(type instanceof LayerDeepSky)
			return settings.renderDeepSky;
		return true;
	}
}
