package stellarium.render.extended;

import java.io.IOException;
import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import stellarapi.api.lib.math.Matrix3;
import stellarium.StellarSky;
import stellarium.StellarSkyResources;
import stellarium.client.ClientSettings;
import stellarium.client.SkyRendererMode;
import stellarium.render.shader.IShaderObject;
import stellarium.render.shader.ShaderHelper;
import stellarium.render.stellars.StellarRI;
import stellarium.time.StellarSkyTime;

public enum ExtendedSkyRenderer {
	INSTANCE;

	private static final int GL_POINT_SPRITE = 0x8861;
	private static final int GL_VERTEX_PROGRAM_POINT_SIZE = 0x8642;
	private static final Matrix3 EQUATORIAL_TO_ECLIPTIC = new Matrix3()
			.setAsRotation(1.0, 0.0, 0.0, -0.4090926);

	private ExtendedCatalogueLoader.CatalogueBuffer stars;
	private ExtendedCatalogueLoader.DeepSkyCatalogue deepSky;
	private ExtendedCatalogueLoader.CatalogueBuffer milkyWay;
	private IShaderObject starShader;
	private IShaderObject deepSkyShader;
	private float loadedStarLimit = Float.NaN;
	private float loadedDeepSkyLimit = Float.NaN;
	private boolean ready;

	public void initialize(ClientSettings settings) {
		if(settings.rendererMode != SkyRendererMode.EXTENDED)
			return;

		try {
			this.starShader = ShaderHelper.getInstance().buildShader("extended_stars",
					StellarSkyResources.vertexExtendedStar,
					StellarSkyResources.fragmentExtendedStar);
			if(!settings.lowPowerRenderer) {
				this.deepSkyShader = ShaderHelper.getInstance().buildShader("extended_deep_sky",
						StellarSkyResources.vertexExtendedDeepSky,
						StellarSkyResources.fragmentExtendedDeepSky);
			}
			if(starShader == null || (!settings.lowPowerRenderer && deepSkyShader == null))
				throw new IOException("Extended sky shader compilation failed");

			if(stars == null || loadedStarLimit != settings.extendedStarMagnitudeLimit) {
				delete(stars);
				stars = ExtendedCatalogueLoader.loadStars(settings.extendedStarMagnitudeLimit);
				loadedStarLimit = settings.extendedStarMagnitudeLimit;
			}
			if(!settings.lowPowerRenderer) {
				if(deepSky == null
						|| loadedDeepSkyLimit != settings.extendedDeepSkyMagnitudeLimit) {
					delete(deepSky);
					deepSky = ExtendedCatalogueLoader.loadDeepSky(
							settings.extendedDeepSkyMagnitudeLimit);
					loadedDeepSkyLimit = settings.extendedDeepSkyMagnitudeLimit;
				}
				if(milkyWay == null)
					milkyWay = ExtendedCatalogueLoader.buildMilkyWay();
			}
			ready = true;
		} catch(Exception exception) {
			ready = false;
			StellarSky.INSTANCE.getLogger().error(
					"Unable to initialize extended sky renderer; using legacy renderer", exception);
		}
	}

	public boolean isActive(ClientSettings settings) {
		return settings.rendererMode == SkyRendererMode.EXTENDED && ready;
	}

	public void render(ClientSettings settings, StellarRI info) {
		if(!isActive(settings))
			return;

		GlStateManager.pushMatrix();
		GL11.glMultMatrix(toOpenGlMatrix(new Matrix3(
				info.info.coordinate.getProjectionToGround())
				.postMult(EQUATORIAL_TO_ECLIPTIC)));
		try {
			if(!settings.lowPowerRenderer && settings.renderMilkyWay)
				renderMilkyWay(settings);
			if(!settings.lowPowerRenderer && settings.renderDeepSky
					&& settings.renderDeepSkyCatalog)
				renderDeepSky(info);
			if(settings.renderBrightStars)
				renderStars(settings, info);
		} finally {
			ShaderHelper.getInstance().releaseCurrentShader();
			GlStateManager.enableTexture2D();
			GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
			GlStateManager.popMatrix();
		}
	}

	private void renderMilkyWay(ClientSettings settings) {
		GlStateManager.enableTexture2D();
		net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
				.bindTexture(StellarSkyResources.resourceExtendedMilkyway);
		GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
				GL11.GL_LINEAR);
		GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
				GL11.GL_LINEAR);
		GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
				GL11.GL_REPEAT);
		GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
				GL11.GL_CLAMP);
		float brightness = 0.16f * settings.extendedMilkyWayBrightness;
		GlStateManager.color(brightness, brightness, brightness, 1.0f);
		milkyWay.buffer.drawArrays();
	}

	private void renderDeepSky(StellarRI info) {
		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE,
				GL11.GL_ONE, GL11.GL_ONE);
		deepSkyShader.bindShader();
		for(ExtendedCatalogueLoader.DeepSkyBatch batch : deepSky.batches) {
			if(batch.texture != null) {
				net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
						.bindTexture(batch.texture);
				deepSkyShader.getField("useTexture").setInteger(1);
			} else {
				GlStateManager.bindTexture(0);
				deepSkyShader.getField("useTexture").setInteger(0);
			}
			batch.buffer.drawArrays();
		}
		deepSkyShader.releaseShader();
		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
	}

	private void renderStars(ClientSettings settings, StellarRI info) {
		preparePointSprites();
		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE,
				GL11.GL_ONE, GL11.GL_ONE);
		net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
				.bindTexture(StellarSkyResources.resourceExtendedStarHalo);
		starShader.bindShader();
		starShader.getField("brightnessScale").setDouble(settings.extendedStarBrightness);
		starShader.getField("pixelScale").setDouble(
				Math.max(0.75, info.minecraft.displayHeight / 1080.0));
		double epochYears = StellarSkyTime.getAstronomicalYear(info.world,
				info.world.getWorldTime()) - 2016.0;
		starShader.getField("epochYears").setDouble(epochYears);
		stars.buffer.drawArrays();
		starShader.releaseShader();
		finishPointSprites();
		GlStateManager.disableBlend();
	}

	private static void preparePointSprites() {
		GlStateManager.disableTexture2D();
		GL11.glEnable(GL_POINT_SPRITE);
		GL11.glEnable(GL_VERTEX_PROGRAM_POINT_SIZE);
	}

	private static void finishPointSprites() {
		GL11.glDisable(GL_VERTEX_PROGRAM_POINT_SIZE);
		GL11.glDisable(GL_POINT_SPRITE);
		GlStateManager.enableTexture2D();
	}

	private static FloatBuffer toOpenGlMatrix(Matrix3 matrix) {
		FloatBuffer result = BufferUtils.createFloatBuffer(16);
		for(int column = 0; column < 3; column++) {
			for(int row = 0; row < 3; row++)
				result.put((float) matrix.getElement(row, column));
			result.put(0.0f);
		}
		result.put(0.0f).put(0.0f).put(0.0f).put(1.0f);
		result.flip();
		return result;
	}

	private static void delete(ExtendedCatalogueLoader.CatalogueBuffer buffer) {
		if(buffer != null)
			buffer.delete();
	}

	private static void delete(ExtendedCatalogueLoader.DeepSkyCatalogue buffer) {
		if(buffer != null)
			buffer.delete();
	}
}
