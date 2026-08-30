package stellarium.stellars.system;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import stellarium.StellarSkyResources;
import stellarium.StellarSky;
import stellarium.render.stellars.access.EnumStellarPass;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.render.util.FloatVertexFormats;
import stellarium.stellars.render.ICelestialObjectRenderer;

public enum SunRenderer implements ICelestialObjectRenderer<SunRenderCache> {

	INSTANCE;
	private static final double VANILLA_TEXTURE_CONTENT_SCALE = 4.0;

	@Override
	public void render(SunRenderCache cache, EnumStellarPass pass, LayerRHelper info) {
		if(pass == EnumStellarPass.DominateScatter) {
			float scatter = getTwilightScatter(cache.appPos.getZ());
			if(scatter > 0.0f)
				info.renderDominate(cache.appPos, scatter, scatter, scatter);
		} else if(pass == EnumStellarPass.Opaque) {
			if(StellarSky.PROXY.getClientSettings().lowPowerRenderer) {
				info.bindTexture(StellarSkyResources.resourceVanillaSunSurface);
				float weather = 1.0f
						- info.world.getRainStrength(info.partialTicks) * 0.8f;
				GlStateManager.enableBlend();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
				info.bindTexShader();
				info.builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F);
				info.renderTexturedBillboard(cache.appPos, LayerRHelper.DEEP_DEPTH * 0.8,
						Math.asin(cache.size) * VANILLA_TEXTURE_CONTENT_SCALE,
						0.0f, 0.0f, 1.0f, 1.0f,
						cache.spriteRed * weather,
						cache.spriteGreen * weather,
						cache.spriteBlue * weather, 1.0f);
				info.builder.finishDrawing();
				info.renderer.draw(info.builder);
				info.unbindTexShader();
				GlStateManager.disableBlend();
				return;
			} else {
				info.bindTexture(StellarSkyResources.resourceSunSurface.getLocation());
			}

			info.bindTexShader();
			info.builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F_NORMAL);

			float brightness = 4830000.0f;

			int longc, latc;

			for(longc=0; longc<cache.longn; longc++){
				for(latc=0; latc<cache.latn; latc++){
					int longcd=(longc+1)%cache.longn;
					float longd=(float)longc/(float)cache.longn;
					float latd=1.0f-(float)latc/(float)cache.latn;
					float longdd=(float)(longc+1)/(float)cache.longn;
					float latdd=1.0f-(float)(latc+1)/(float)cache.latn;

					info.builder.pos(cache.sunPos[longc][latc], LayerRHelper.DEEP_DEPTH * 0.8f);
					info.builder.tex(longd, latd);
					info.builder.color(brightness, brightness, brightness,
							1.0f);
					info.builder.normal(cache.sunNormal[longc][latc]);
					info.builder.endVertex();

					info.builder.pos(cache.sunPos[longcd][latc], LayerRHelper.DEEP_DEPTH * 0.8f);
					info.builder.tex(longdd, latd);
					info.builder.color(brightness, brightness, brightness,
							1.0f);
					info.builder.normal(cache.sunNormal[longcd][latc]);
					info.builder.endVertex();

					info.builder.pos(cache.sunPos[longcd][latc+1], LayerRHelper.DEEP_DEPTH * 0.8f);
					info.builder.tex(longdd, latdd);
					info.builder.color(brightness, brightness, brightness,
							1.0f);
					info.builder.normal(cache.sunNormal[longcd][latc+1]);
					info.builder.endVertex();

					info.builder.pos(cache.sunPos[longc][latc+1], LayerRHelper.DEEP_DEPTH * 0.8f);
					info.builder.tex(longd, latdd);
					info.builder.color(brightness, brightness, brightness,
							1.0f);
					info.builder.normal(cache.sunNormal[longc][latc+1]);
					info.builder.endVertex();
				}
			}

			info.builder.finishDrawing();
			info.renderer.draw(info.builder);
			info.unbindTexShader();
		}
	}

	private static float getTwilightScatter(double sinAltitude) {
		double astronomicalTwilight = Math.sin(Math.toRadians(-18.0));
		double value = (sinAltitude - astronomicalTwilight) / -astronomicalTwilight;
		value = Math.max(0.0, Math.min(1.0, value));
		return (float) (value * value * (3.0 - 2.0 * value));
	}
}
