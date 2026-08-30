package stellarium.stellars.system;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import stellarium.StellarSkyResources;
import stellarium.StellarSky;
import stellarium.render.stellars.access.EnumStellarPass;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.render.util.FloatVertexFormats;
import stellarium.stellars.render.ICelestialObjectRenderer;

public enum MoonRenderer implements ICelestialObjectRenderer<MoonRenderCache> {
	INSTANCE;
	private static final double VANILLA_TEXTURE_CONTENT_SCALE = 4.0;

	@Override
	public void render(MoonRenderCache cache, EnumStellarPass pass, LayerRHelper info) {
		if(pass == EnumStellarPass.DominateScatter && cache.shouldRenderDominate) {
			info.renderDominate(cache.appPos, cache.domination, cache.domination, cache.domination);
		} else if(pass == EnumStellarPass.Opaque && cache.shouldRender) {
			boolean lowPower = StellarSky.PROXY.getClientSettings().lowPowerRenderer;
			if(lowPower) {
				info.bindTexture(StellarSkyResources.resourceVanillaMoonPhases);
				int phase = cache.phaseIndex;
				float minU = (phase % 4) * 0.25f;
				float minV = (phase / 4) * 0.5f;
				float weather = 1.0f
						- info.world.getRainStrength(info.partialTicks) * 0.8f;

				GlStateManager.enableBlend();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
				info.bindTexShader();
				info.builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F);
				info.renderTexturedBillboard(cache.appPos, LayerRHelper.DEEP_DEPTH * 0.5,
						Math.asin(cache.size) * VANILLA_TEXTURE_CONTENT_SCALE,
						minU, minV, minU + 0.25f, minV + 0.5f,
						cache.spriteRed * weather,
						cache.spriteGreen * weather,
						cache.spriteBlue * weather, 1.0f);
				info.builder.finishDrawing();
				info.renderer.draw(info.builder);
				info.unbindTexShader();
				GlStateManager.disableBlend();
				return;
			} else {
				info.bindTexture(StellarSkyResources.resourceMoonSurface.getLocation());
			}
			info.bindTexShader();
			info.builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F_NORMAL);

			int longc, latc;

			for(longc=0; longc<cache.longn; longc++){
				for(latc=0; latc<cache.latn; latc++){
					int longcd=(longc+1)%cache.longn;
					float longd=(float)longc/(float)cache.longn + 0.5f;
					float latd=1.0f-(float)latc/(float)cache.latn;
					float longdd=(float)(longc+1)/(float)cache.longn + 0.5f;
					float latdd=1.0f-(float)(latc+1)/(float)cache.latn;
					float br = cache.surfBr[longc][latc];
					float brNextLong = cache.surfBr[longcd][latc];
					float brNextLat = cache.surfBr[longcd][latc+1];
					float brLat = cache.surfBr[longc][latc+1];

					info.builder.pos(cache.pos[longc][latc], LayerRHelper.DEEP_DEPTH * 0.5f);
					info.builder.tex(longd, latd);
					info.builder.color(br, br, br, 1.0f);
					info.builder.normal(cache.normal[longc][latc]);
					info.builder.endVertex();

					info.builder.pos(cache.pos[longcd][latc], LayerRHelper.DEEP_DEPTH * 0.5f);
					info.builder.tex(longdd, latd);
					info.builder.color(brNextLong, brNextLong, brNextLong, 1.0f);
					info.builder.normal(cache.normal[longcd][latc]);
					info.builder.endVertex();
					
					info.builder.pos(cache.pos[longcd][latc+1], LayerRHelper.DEEP_DEPTH * 0.5f);
					info.builder.tex(longdd, latdd);
					info.builder.color(brNextLat, brNextLat, brNextLat, 1.0f);
					info.builder.normal(cache.normal[longcd][latc+1]);
					info.builder.endVertex();

					info.builder.pos(cache.pos[longc][latc+1], LayerRHelper.DEEP_DEPTH * 0.5f);
					info.builder.tex(longd, latdd);
					info.builder.color(brLat, brLat, brLat, 1.0f);
					info.builder.normal(cache.normal[longc][latc+1]);
					info.builder.endVertex();
				}
			}

			info.builder.finishDrawing();
			info.renderer.draw(info.builder);
			info.unbindTexShader();
		}
	}

}
