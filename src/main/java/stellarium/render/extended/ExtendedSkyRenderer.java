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
	private ExtendedCatalogueLoader.DeepSkyCatalogue deepSkyImages;
	private ExtendedCatalogueLoader.CatalogueBuffer milkyWay;
	private IShaderObject starShader;
	private IShaderObject deepSkyShader;
	private IShaderObject deepSkyImageShader;
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
				this.deepSkyImageShader = ShaderHelper.getInstance().buildShader(
						"extended_deep_sky_images",
						StellarSkyResources.vertexExtendedDeepSkyImage,
						StellarSkyResources.fragmentExtendedDeepSkyImage);
			}
			if(starShader == null || (!settings.lowPowerRenderer
					&& (deepSkyShader == null || deepSkyImageShader == null)))
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
				if(deepSkyImages == null)
					deepSkyImages = ExtendedCatalogueLoader.loadDeepSkyImages();
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
			if(!settings.lowPowerRenderer && settings.renderDeepSky) {
				renderDeepSkyImages();
				if(settings.renderDeepSkyCatalog)
					renderDeepSky(info);
			}
			if(settings.renderBrightStars)
				renderStars(settings, info);
		} finally {
			ShaderHelper.getInstance().releaseCurrentShader();
			// StellarRenderer enters this layer with additive blending and
			// depth writes disabled. Restore that contract for subsequent
			// celestial layers instead of leaking the last sub-pass state.
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
			GlStateManager.disableDepth();
			GlStateManager.depthMask(false);
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

	private void renderDeepSkyImages() {
		GlStateManager.enableTexture2D();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE,
				GL11.GL_ONE, GL11.GL_ONE);
		deepSkyImageShader.bindShader();
		deepSkyImageShader.getField("skyImage").setInteger(0);
		LocalFrustum clipping = LocalFrustum.capture();
		for(ExtendedCatalogueLoader.DeepSkyBatch batch : deepSkyImages.batches) {
			ExtendedCatalogueLoader.Bounds bounds = batch.bounds;
			if(bounds != null && !clipping.isBoxInFrustum(bounds))
				continue;
			net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
					.bindTexture(batch.texture);
			GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
					GL11.GL_LINEAR);
			GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
					GL11.GL_LINEAR);
			batch.buffer.drawArrays();
		}
		deepSkyImageShader.releaseShader();
		GlStateManager.disableBlend();
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

	/**
	 * Captures the current sky matrices without touching Minecraft's shared
	 * ClippingHelperImpl. Reinitializing that singleton here corrupts the
	 * frustum later used for terrain chunk culling.
	 */
	private static final class LocalFrustum {
		private final float[][] planes = new float[6][4];

		static LocalFrustum capture() {
			FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
			FloatBuffer projection = BufferUtils.createFloatBuffer(16);
			GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
			GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);

			float[] model = new float[16];
			float[] project = new float[16];
			modelView.get(model);
			projection.get(project);
			float[] clip = multiply(project, model);

			LocalFrustum result = new LocalFrustum();
			result.setPlane(0, clip, 3, 0, 1.0f);
			result.setPlane(1, clip, 3, 0, -1.0f);
			result.setPlane(2, clip, 3, 1, 1.0f);
			result.setPlane(3, clip, 3, 1, -1.0f);
			result.setPlane(4, clip, 3, 2, 1.0f);
			result.setPlane(5, clip, 3, 2, -1.0f);
			return result;
		}

		private static float[] multiply(float[] left, float[] right) {
			float[] result = new float[16];
			for(int column = 0; column < 4; column++) {
				for(int row = 0; row < 4; row++) {
					float value = 0.0f;
					for(int index = 0; index < 4; index++)
						value += left[index * 4 + row] * right[column * 4 + index];
					result[column * 4 + row] = value;
				}
			}
			return result;
		}

		private void setPlane(int plane, float[] matrix, int baseRow, int otherRow,
				float sign) {
			float lengthSquared = 0.0f;
			for(int component = 0; component < 4; component++) {
				float value = matrix[component * 4 + baseRow]
						+ sign * matrix[component * 4 + otherRow];
				planes[plane][component] = value;
				if(component < 3)
					lengthSquared += value * value;
			}
			float inverseLength = 1.0f / (float) Math.sqrt(lengthSquared);
			for(int component = 0; component < 4; component++)
				planes[plane][component] *= inverseLength;
		}

		boolean isBoxInFrustum(ExtendedCatalogueLoader.Bounds bounds) {
			for(float[] plane : planes) {
				double x = plane[0] >= 0.0f ? bounds.maxX : bounds.minX;
				double y = plane[1] >= 0.0f ? bounds.maxY : bounds.minY;
				double z = plane[2] >= 0.0f ? bounds.maxZ : bounds.minZ;
				if(plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < 0.0)
					return false;
			}
			return true;
		}
	}
}
