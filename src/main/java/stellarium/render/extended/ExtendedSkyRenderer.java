package stellarium.render.extended;

import java.io.IOException;
import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.optics.Wavelength;
import stellarium.StellarSky;
import stellarium.StellarSkyResources;
import stellarium.client.ClientSettings;
import stellarium.client.SkyRendererMode;
import stellarium.client.overlay.objectinfo.CelestialNameCatalog;
import stellarium.render.shader.IShaderObject;
import stellarium.render.shader.ShaderHelper;
import stellarium.render.stellars.StellarRI;
import stellarium.stellars.OpticsHelper;
import stellarium.time.StellarSkyTime;

public enum ExtendedSkyRenderer {
	INSTANCE;

	private static final int GL_POINT_SPRITE = 0x8861;
	private static final int GL_COORD_REPLACE = 0x8862;
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
	private IShaderObject milkyWayShader;
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
				this.milkyWayShader = ShaderHelper.getInstance().buildShader(
						"extended_milky_way",
						StellarSkyResources.vertexExtendedDeepSkyImage,
						StellarSkyResources.fragmentExtendedMilkyWay);
			}
			if(starShader == null || (!settings.lowPowerRenderer
					&& (deepSkyShader == null || deepSkyImageShader == null
							|| milkyWayShader == null)))
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

	/** Finds the catalogue object nearest to the view ray, in equatorial coordinates. */
	public ExtendedTarget findTarget(Vector3 equatorialLook, double toleranceDegrees,
			double epochYears) {
		Vector3 look = new Vector3(equatorialLook).normalize();
		ExtendedTarget best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		double tolerance = Math.toRadians(toleranceDegrees);

		if(stars != null && stars.query != null) {
			ExtendedCatalogueLoader.StarQueryIndex index = stars.query;
			double minimumDot = Math.cos(tolerance);
			double prefilterDot = Math.cos(tolerance + Math.toRadians(0.05));
			for(int star = 0; star < index.size(); star++) {
				int base = star * 6;
				double baseDot = index.vectors[base] * look.getX()
						+ index.vectors[base + 1] * look.getY()
						+ index.vectors[base + 2] * look.getZ();
				if(baseDot < prefilterDot)
					continue;
				double x = index.vectors[base] + index.vectors[base + 3] * epochYears;
				double y = index.vectors[base + 1] + index.vectors[base + 4] * epochYears;
				double z = index.vectors[base + 2] + index.vectors[base + 5] * epochYears;
				double inverseLength = 1.0 / Math.sqrt(x * x + y * y + z * z);
				x *= inverseLength; y *= inverseLength; z *= inverseLength;
				double dot = x * look.getX() + y * look.getY() + z * look.getZ();
				if(dot < minimumDot)
					continue;
				double angle = Math.acos(clampDot(dot));
				// Use angular separation as the primary selector, with only a tiny
				// brightness bias to keep a faint neighbour from stealing a bright star.
				double score = angle / tolerance
						+ Math.max(-2.0f, index.magnitude[star]) * 0.001;
				if(score < bestScore) {
					String identifier = index.hipId[star] > 0 ? "HIP " + index.hipId[star]
							: "Gaia DR3 " + Long.toUnsignedString(index.gaiaId[star]);
					if(index.component[star] > 0)
						identifier += " component " + (index.component[star] & 0xff);
					CelestialNameCatalog.LocalizedName names = CelestialNameCatalog.resolveStar(
							index.hipId[star], index.gaiaId[star]);
					best = new ExtendedTarget(identifier, names.english, names.chinese,
							"Star", index.magnitude[star],
							new Vector3(x, y, z), Math.toDegrees(angle));
					bestScore = score;
				}
			}
		}

		if(deepSky != null && deepSky.query != null) {
			ExtendedCatalogueLoader.DeepSkyQueryIndex index = deepSky.query;
			for(int object = 0; object < index.size(); object++) {
				int base = object * 7;
				double dot = index.values[base] * look.getX()
						+ index.values[base + 1] * look.getY()
						+ index.values[base + 2] * look.getZ();
				double angle = Math.acos(clampDot(dot));
				double hitRadius = Math.max(tolerance,
						Math.toRadians(index.values[base + 4] / 120.0));
				if(angle > hitRadius)
					continue;
				double score = angle / hitRadius;
				if(score < bestScore) {
					String identifier = index.names[object];
					CelestialNameCatalog.LocalizedName names = CelestialNameCatalog.resolveDso(identifier);
					best = new ExtendedTarget(identifier, names.english, names.chinese,
							deepSkyTypeName(index.types[object]), index.values[base + 3],
							new Vector3(index.values[base], index.values[base + 1],
									index.values[base + 2]), Math.toDegrees(angle));
					bestScore = score;
				}
			}
		}
		return best;
	}

	private static double clampDot(double value) {
		return Math.max(-1.0, Math.min(1.0, value));
	}

	private static String deepSkyTypeName(String type) {
		if(type.contains("GX") || type.equals("G")) return "Galaxy";
		if(type.contains("GC")) return "Globular cluster";
		if(type.contains("OC") || type.contains("CL")) return "Open cluster";
		if(type.contains("PN")) return "Planetary nebula";
		if(type.contains("SNR")) return "Supernova remnant";
		if(type.contains("NB") || type.contains("RN") || type.contains("EN")
				|| type.contains("HII")) return "Nebula";
		return "Deep-sky object";
	}

	public static final class ExtendedTarget {
		public final String identifier;
		public final String englishName;
		public final String chineseName;
		public final String type;
		public final double magnitude;
		public final Vector3 equatorialDirection;
		public final double separationDegrees;

		ExtendedTarget(String identifier, String englishName, String chineseName,
				String type, double magnitude,
				Vector3 equatorialDirection, double separationDegrees) {
			this.identifier = identifier;
			this.englishName = englishName;
			this.chineseName = chineseName;
			this.type = type;
			this.magnitude = magnitude;
			this.equatorialDirection = equatorialDirection;
			this.separationDegrees = separationDegrees;
		}
	}

	public void render(ClientSettings settings, StellarRI info) {
		if(!isActive(settings))
			return;

		boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		Matrix3 catalogueToGround = new Matrix3(
				info.info.coordinate.getProjectionToGround())
				.postMult(EQUATORIAL_TO_ECLIPTIC);
		GlStateManager.pushMatrix();
		GL11.glMultMatrix(toOpenGlMatrix(catalogueToGround));
		try {
			// Minecraft enters sky rendering with face culling enabled. The
			// catalogue meshes are viewed from inside the celestial sphere.
			GlStateManager.disableCull();
			if(!settings.lowPowerRenderer && settings.renderMilkyWay)
				renderMilkyWay(settings);
			if(!settings.lowPowerRenderer && settings.renderDeepSky) {
				if(settings.renderDeepSkyImages)
					renderDeepSkyImages();
				if(settings.renderDeepSkyCatalog)
					renderDeepSky(info);
			}
			if(settings.renderBrightStars)
				renderStars(settings, info, catalogueToGround);
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
			if(cullEnabled)
				GlStateManager.enableCull();
			else
				GlStateManager.disableCull();
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
		float brightness = 0.085f * settings.extendedMilkyWayBrightness;
		GlStateManager.color(brightness, brightness, brightness, 1.0f);
		milkyWayShader.bindShader();
		milkyWayShader.getField("skyImage").setInteger(0);
		milkyWayShader.getField("texelSize").setDouble2(1.0 / 2048.0, 1.0 / 1024.0);
		milkyWay.buffer.drawArrays();
		milkyWayShader.releaseShader();
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

	private void renderStars(ClientSettings settings, StellarRI info,
			Matrix3 catalogueToGround) {
		preparePointSprites();
		try {
			GlStateManager.enableTexture2D();
			GlStateManager.enableBlend();
			// Stellarium point sources carry their luminance in RGB and are added
			// directly to the sky buffer. SRC_ALPHA multiplies the radial profile
			// a second time and makes adapted stars disappear after twilight.
			GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
			net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
					.bindTexture(StellarSkyResources.resourceExtendedStarHalo);
			starShader.bindShader();
			starShader.getField("starHalo").setInteger(0);
			starShader.getField("brightnessScale").setDouble(settings.extendedStarBrightness);
			starShader.getField("pixelScale").setDouble(
					Math.max(0.75, info.minecraft.displayHeight / 1080.0));
			double fieldOfViewScale = Math.max(1.0, info.info.multiplyingPower);
			double visibleMagnitude = Math.min(settings.extendedStarMagnitudeLimit,
					7.0 + 2.5 * Math.log10(fieldOfViewScale));
			starShader.getField("magnitudeLimit").setDouble(visibleMagnitude);
			starShader.getField("twinkleAmount").setDouble(
					info.info.sky.getSeeing(Wavelength.V) > 0.0
							? OpticsHelper.twinkleAmount() : 0.0);
			double animationTime = info.minecraft.getRenderViewEntity() == null ? 0.0
					: (info.minecraft.getRenderViewEntity().ticksExisted
							+ info.partialTicks) / 20.0;
			starShader.getField("animationTime").setDouble(animationTime);
			Vector3 catalogueZenith = new Matrix3(catalogueToGround).transpose()
					.transform(new Vector3(0.0, 0.0, 1.0));
			starShader.getField("zenithDirection").setVector3(catalogueZenith);
			double epochYears = StellarSkyTime.getAstronomicalYear(info.world,
					info.world.getWorldTime()) - 2016.0;
			starShader.getField("epochYears").setDouble(epochYears);
			stars.buffer.drawArrays();
		} finally {
			starShader.releaseShader();
			finishPointSprites();
			GlStateManager.disableBlend();
		}
	}

	private static void preparePointSprites() {
		GlStateManager.disableTexture2D();
		GL11.glEnable(GL_POINT_SPRITE);
		GL11.glTexEnvi(GL_POINT_SPRITE, GL_COORD_REPLACE, GL11.GL_TRUE);
		GL11.glEnable(GL_VERTEX_PROGRAM_POINT_SIZE);
	}

	private static void finishPointSprites() {
		GL11.glDisable(GL_VERTEX_PROGRAM_POINT_SIZE);
		GL11.glTexEnvi(GL_POINT_SPRITE, GL_COORD_REPLACE, GL11.GL_FALSE);
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
