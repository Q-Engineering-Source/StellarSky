package stellarium.stellars.star;

import stellarium.render.stellars.access.EnumStellarPass;
import stellarium.render.stellars.AtmosphericAppearance;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.stellars.OpticsHelper;
import stellarium.stellars.render.ICelestialObjectRenderer;

public enum StarRenderer implements ICelestialObjectRenderer<StarRenderCache> {
	INSTANCE;

	@Override
	public void render(StarRenderCache cache, EnumStellarPass pass, LayerRHelper info) {
		float multiplier = OpticsHelper.getMultFromArea(info.pointArea());
		info.renderPoint(cache.pos, LayerRHelper.DEEP_DEPTH,
				AtmosphericAppearance.blend(cache.intrinsicRed, cache.red, info.atmosphereFade)
						* multiplier,
				AtmosphericAppearance.blend(cache.intrinsicGreen, cache.green, info.atmosphereFade)
						* multiplier,
				AtmosphericAppearance.blend(cache.intrinsicBlue, cache.blue, info.atmosphereFade)
						* multiplier);
	}

}
