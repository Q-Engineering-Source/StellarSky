package stellarium.stellars.system;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import stellarapi.api.lib.math.Vector3;
import stellarium.StellarSkyResources;
import stellarium.StellarSky;
import stellarium.render.stellars.AtmosphericAppearance;
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
			if(info.ringworldSkyIllumination.isRing()) {
				if(!info.ringworldSkyIllumination.canRenderDirectSunScatter()) return;
				SunVisualTransform transform = SunVisualTransform.toGroundZenith(cache.appPos);
				Vector3 direction = transform.transform(cache.appPos, new Vector3());
				float direct = (float) info.ringworldSkyIllumination.directScatterLightColor(0.0);
				info.renderDominate(direction, direct, direct, direct);
				return;
			}
			float scatter = getTwilightScatter(cache.appPos.getZ());
			if(scatter > 0.0f)
				info.renderDominate(cache.appPos, scatter, scatter, scatter);
		} else if(pass == EnumStellarPass.Opaque) {
			if(!info.ringworldSkyIllumination.canRenderOpaqueSun()) return;
			SunVisualTransform visualTransform = info.ringworldSnapshot == null ? null
					: SunVisualTransform.toGroundZenith(cache.appPos);
			Vector3 lightDirection = visualTransform == null ? cache.appPos
					: visualTransform.transform(cache.appPos, new Vector3());
			if(StellarSky.PROXY.getClientSettings().lowPowerRenderer) {
				info.bindTexture(StellarSkyResources.resourceVanillaSunSurface);
				float weather = 1.0f
						- info.world.getRainStrength(info.partialTicks) * 0.8f;
				float weatherTransmission = AtmosphericAppearance.blend(1.0f, weather,
						info.atmosphereFade);
				GlStateManager.enableBlend();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
				info.bindTexShader();
				info.builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F);
				info.renderTexturedBillboard(lightDirection, LayerRHelper.DEEP_DEPTH * 0.8,
						Math.asin(cache.size) * VANILLA_TEXTURE_CONTENT_SCALE,
						0.0f, 0.0f, 1.0f, 1.0f,
						AtmosphericAppearance.blend(cache.vacuumSpriteRed, cache.spriteRed,
								info.atmosphereFade) * weatherTransmission,
						AtmosphericAppearance.blend(cache.vacuumSpriteGreen, cache.spriteGreen,
								info.atmosphereFade) * weatherTransmission,
						AtmosphericAppearance.blend(cache.vacuumSpriteBlue, cache.spriteBlue,
								info.atmosphereFade) * weatherTransmission, 1.0f);
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
			Vector3 transformedPosition = visualTransform == null ? null : lightDirection;
			Vector3 transformedNormal = visualTransform == null ? null : new Vector3();

			int longc, latc;

			for(longc=0; longc<cache.longn; longc++){
				for(latc=0; latc<cache.latn; latc++){
					int longcd=(longc+1)%cache.longn;
					float longd=(float)longc/(float)cache.longn;
					float latd=1.0f-(float)latc/(float)cache.latn;
					float longdd=(float)(longc+1)/(float)cache.longn;
					float latdd=1.0f-(float)(latc+1)/(float)cache.latn;

					if(visualTransform == null) {
						addVertex(info, cache.sunPos[longc][latc], cache.sunNormal[longc][latc],
								longd, latd, brightness);
						addVertex(info, cache.sunPos[longcd][latc], cache.sunNormal[longcd][latc],
								longdd, latd, brightness);
						addVertex(info, cache.sunPos[longcd][latc+1], cache.sunNormal[longcd][latc+1],
								longdd, latdd, brightness);
						addVertex(info, cache.sunPos[longc][latc+1], cache.sunNormal[longc][latc+1],
								longd, latdd, brightness);
					} else {
						addTransformedVertex(info, visualTransform, cache.sunPos[longc][latc],
								cache.sunNormal[longc][latc], transformedPosition, transformedNormal,
								longd, latd, brightness);
						addTransformedVertex(info, visualTransform, cache.sunPos[longcd][latc],
								cache.sunNormal[longcd][latc], transformedPosition, transformedNormal,
								longdd, latd, brightness);
						addTransformedVertex(info, visualTransform, cache.sunPos[longcd][latc+1],
								cache.sunNormal[longcd][latc+1], transformedPosition, transformedNormal,
								longdd, latdd, brightness);
						addTransformedVertex(info, visualTransform, cache.sunPos[longc][latc+1],
								cache.sunNormal[longc][latc+1], transformedPosition, transformedNormal,
								longd, latdd, brightness);
					}
				}
			}

			info.builder.finishDrawing();
			info.renderer.draw(info.builder);
			info.unbindTexShader();
		}
	}

	private static void addTransformedVertex(LayerRHelper info, SunVisualTransform transform,
			Vector3 position, Vector3 normal, Vector3 transformedPosition,
			Vector3 transformedNormal, float u, float v, float brightness) {
		transform.transform(position, transformedPosition);
		transform.transform(normal, transformedNormal);
		addVertex(info, transformedPosition, transformedNormal, u, v, brightness);
	}

	private static void addVertex(LayerRHelper info, Vector3 position, Vector3 normal,
			float u, float v, float brightness) {
		info.builder.pos(position, LayerRHelper.DEEP_DEPTH * 0.8f);
		info.builder.tex(u, v);
		info.builder.color(brightness, brightness, brightness, 1.0f);
		info.builder.normal(normal);
		info.builder.endVertex();
	}

	private static float getTwilightScatter(double sinAltitude) {
		double astronomicalTwilight = Math.sin(Math.toRadians(-18.0));
		double value = (sinAltitude - astronomicalTwilight) / -astronomicalTwilight;
		value = Math.max(0.0, Math.min(1.0, value));
		return (float) (value * value * (3.0 - 2.0 * value));
	}
}
