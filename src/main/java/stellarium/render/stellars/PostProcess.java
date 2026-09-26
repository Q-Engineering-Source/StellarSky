package stellarium.render.stellars;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.opengl.ARBHalfFloatPixel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import net.minecraft.client.renderer.GlStateManager;
import stellarapi.api.optics.EyeDetector;
import stellarapi.api.optics.Wavelength;
import stellarium.StellarSkyResources;
import stellarium.render.shader.IShaderObject;
import stellarium.render.shader.IUniformField;
import stellarium.render.shader.ShaderHelper;
import stellarium.render.util.FramebufferCustom;
import stellarium.util.OpenGlUtil;
import stellarium.util.math.StellarMath;
import stellarium.view.ViewerInfo;

public class PostProcess {
	private FramebufferCustom frame1 = null, frame2 = null, brQuery = null, linearScene = null;
	private int prevFramebufferBound;
	private int width, height;
	private PostProcessInputRoute inputRoute;

	private IShaderObject scope, skyToQueried, hdrToldr, linearToSRGB, linearSceneToRgbE;
	private IUniformField fieldBrMult, fieldResDir, fieldBrScale, fieldRelative;

	private int maxLevel;
	private float screenRatio;
	private int[] pBuffer;
	private ByteBuffer brBuffer = null;

	private long prevTime = -1;
	private int index = 0;
	private float brightness = 0.0f;

	public void initialize() {
		this.setupShader();
		this.setupPixelBuffer();
	}

	public void onResize(int width, int height) {
		this.width = width;
		this.height = height;
		this.maxLevel = log2(Math.max(width, height) - 1) + 1;
		int texSize = 1 << this.maxLevel;
		this.screenRatio = (float)(width * height) / (texSize * texSize);

		if(this.frame1 != null)
			frame1.deleteFramebuffer();
		if(this.frame2 != null)
			frame2.deleteFramebuffer();
		if(this.brQuery != null)
			brQuery.deleteFramebuffer();
		if(this.linearScene != null) {
			linearScene.deleteFramebuffer();
			linearScene = null;
		}

		// RGBE format
		this.frame1 = FramebufferCustom.builder()
				.texFormat(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_BYTE)
				.depthStencil(false, false)
				.build(width, height);

		// RGBE format
		this.frame2 = FramebufferCustom.builder()
				.texFormat(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_BYTE)
				.depthStencil(false, false)
				.build(width, height);

		this.brQuery = FramebufferCustom.builder()
				.texFormat(OpenGlUtil.RGB16F, GL11.GL_RGB, OpenGlUtil.TEXTURE_FLOAT)
				.renderRegion(0, 0, width, height)
				.texMinMagFilter(GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_NEAREST)
				.depthStencil(false, false)
				.build(texSize, texSize);
	}

	public void setupPixelBuffer() {
		this.pBuffer = new int[] {GL15.glGenBuffers(), GL15.glGenBuffers()};

		boolean isHalf = OpenGlUtil.HALF_FLOAT_SUPPORTED;

		GL15.glBindBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, this.pBuffer[0]);
		GL15.glBufferData(OpenGlUtil.PIXEL_PACK_BUFFER, isHalf? 4 << 1 : 4 << 2, GL15.GL_STREAM_READ);
		GL15.glBindBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, this.pBuffer[1]);
		GL15.glBufferData(OpenGlUtil.PIXEL_PACK_BUFFER, isHalf? 4 << 1 : 4 << 2, GL15.GL_STREAM_READ);
		GL15.glBindBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, 0);
	}

	private static int log2(int n) {
		return 31 - Integer.numberOfLeadingZeros(n);
	}

	public void setupShader() {
		this.scope = ShaderHelper.getInstance().buildShader("Scope",
				StellarSkyResources.vertexScope,
				StellarSkyResources.fragmentScope);
		requireShader(scope, "Scope");
		scope.bindShader();
		scope.getField("texture").setInteger(0);
		scope.releaseShader();
		this.fieldBrMult = scope.getField("brightnessMult");
		this.fieldResDir = scope.getField("resDirection");

		this.skyToQueried = ShaderHelper.getInstance().buildShader("SkyToQueried",
				StellarSkyResources.vertexSkyToQueried,
				StellarSkyResources.fragmentSkyToQueried);
		requireShader(skyToQueried, "SkyToQueried");
		skyToQueried.bindShader();
		skyToQueried.getField("texture").setInteger(0);
		skyToQueried.releaseShader();
		this.fieldRelative = skyToQueried.getField("relative");

		this.hdrToldr = ShaderHelper.getInstance().buildShader("HDRtoLDR",
				StellarSkyResources.vertexHDRtoLDR,
				StellarSkyResources.fragmentHDRtoLDR);
		requireShader(hdrToldr, "HDRtoLDR");
		hdrToldr.bindShader();
		hdrToldr.getField("texture").setInteger(0);
		hdrToldr.releaseShader();
		this.fieldBrScale = hdrToldr.getField("brScale");

		this.linearToSRGB = ShaderHelper.getInstance().buildShader("linearToSRGB",
				StellarSkyResources.vertexLinearToSRGB,
				StellarSkyResources.fragmentLinearToSRGB);
		requireShader(linearToSRGB, "linearToSRGB");
		linearToSRGB.bindShader();
		linearToSRGB.getField("texture").setInteger(0);
		linearToSRGB.releaseShader();

		this.linearSceneToRgbE = ShaderHelper.getInstance().buildShader("LinearSceneToRGBE",
				StellarSkyResources.vertexHDRtoLDR,
				StellarSkyResources.fragmentAtmRefraction);
		requireShader(linearSceneToRgbE, "LinearSceneToRGBE");
		linearSceneToRgbE.bindShader();
		linearSceneToRgbE.getField("texture").setInteger(0);
		linearSceneToRgbE.releaseShader();
	}

	private static void requireShader(IShaderObject shader, String name) {
		if(shader == null)
			throw new IllegalStateException("Unable to initialize post-process shader " + name);
	}

	public void preProcess(PostProcessInputRoute route) {
		if(route == null)
			throw new IllegalArgumentException("route must not be null");
		this.prevFramebufferBound = GlStateManager.glGetInteger(OpenGlUtil.FRAMEBUFFER_BINDING);
		this.inputRoute = route;

		FramebufferCustom target = route.requiresRgbEEncoding()? this.linearScene() : frame1;
		boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		GlStateManager.depthMask(true);
		try {
			target.bindFramebuffer(false);
			target.framebufferClear();
		} finally {
			GlStateManager.depthMask(depthMask);
		}
	}

	private FramebufferCustom linearScene() {
		if(this.linearScene == null) {
			if(this.width <= 0 || this.height <= 0)
				throw new IllegalStateException("Cannot create the linear scene before post-process resize");
			this.linearScene = FramebufferCustom.builder()
					.texFormat(OpenGlUtil.RGB32F, GL11.GL_RGB, OpenGlUtil.TEXTURE_FLOAT)
					.depthStencil(true, false)
					.build(this.width, this.height);
		}
		return this.linearScene;
	}

	public void postProcess(StellarRI info) {
		if(this.inputRoute == null)
			throw new IllegalStateException("Post-process input route was not prepared");
		if(this.inputRoute.requiresRgbEEncoding())
			this.encodeLinearSceneToRgbE();

		// TODO Render everything on floating framebuffers
		// TODO Refactor to make everything clean and sweat

		// Extract things needed
		long currentTime = System.currentTimeMillis();

		// Scope effects
		// TODO Every scope effects should come here
		// MAYBE Separate resolution for each of R, G, B component when wavelengths are far apart
		// MAYBE Apply star-shaped blur for eye
		ViewerInfo viewer = info.info;
		double multRed = viewer.colorMultiplier.getX();
		double multGreen = viewer.colorMultiplier.getY();
		double multBlue = viewer.colorMultiplier.getZ();

		// MAYBE Calculate blur for each pixel
		// Blur on X-axis
		frame2.bindFramebuffer(false);
		frame2.framebufferClear();

		/*
		 * Detector resolution and atmospheric seeing are independent blur
		 * sources, so combine them in quadrature. IAtmosphereEffect reports
		 * angular seeing in degrees; V is the representative luminance band
		 * for this shared RGB kernel.
		 */
		double atmosphericSeeing = viewer.sky.getSeeing(Wavelength.V);
		if(!Double.isFinite(atmosphericSeeing) || atmosphericSeeing < 0.0)
			atmosphericSeeing = 0.0;
		atmosphericSeeing = AtmosphericAppearance.scaleAtmosphericEffect(
				atmosphericSeeing, info.atmosphereFade);
		double opticalResolution = Math.hypot(
				EyeDetector.DEFAULT_RESOLUTION, atmosphericSeeing);
		double resolution = Math.toRadians(opticalResolution)
				/ viewer.multiplyingPower;

		scope.bindShader();
		fieldBrMult.setDouble4(1.0, 1.0, 1.0, 1.0);
		fieldResDir.setDouble2(resolution / info.relativeWidth, 0.0);
		frame1.bindFramebufferTexture();
		frame1.renderFullQuad();
		scope.releaseShader();

		// Blur on Y-axis and Light Power
		frame1.bindFramebuffer(false);
		frame1.framebufferClear();

		scope.bindShader();
		fieldBrMult.setDouble4(multRed, multGreen, multBlue, 1.0);
		fieldResDir.setDouble2(0.0, resolution / info.relativeHeight);
		frame2.bindFramebufferTexture();
		frame2.renderFullQuad();
		scope.releaseShader();

		// Visual effects
		// Brightness Query
		if(currentTime < this.prevTime || currentTime >= this.prevTime + 50) {
			// Render to Brightness Query
			brQuery.bindFramebuffer(true);
			brQuery.framebufferClear();

			skyToQueried.bindShader();
			fieldRelative.setDouble3(info.relativeWidth, info.relativeHeight, 1.0f);
			frame1.bindFramebufferTexture();
			frame1.renderFullQuad();
			skyToQueried.releaseShader();

			// Actual Calculation
			brQuery.bindFramebufferTexture();
			OpenGlUtil.generateMipmap(GL11.GL_TEXTURE_2D);

			this.index = (this.index + 1) % 2;
			int nextIndex = (this.index + 1) % 2;

			boolean isHalf = OpenGlUtil.HALF_FLOAT_SUPPORTED;
			GL15.glBindBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, this.pBuffer[this.index]);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, this.maxLevel, GL12.GL_BGR,
					isHalf? OpenGlUtil.HALF_FLOAT : GL11.GL_FLOAT, 0);

			GL15.glBindBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, this.pBuffer[nextIndex]);
			this.brBuffer = GL15.glMapBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY,
					isHalf? 3 << 1 : 3 << 2, this.brBuffer);

			if(this.brBuffer != null) {
				float readBr;
				if(isHalf) {
					ShortBuffer brBufferS = brBuffer.asShortBuffer();
					short readShBr = brBufferS.get(2);
					readBr = StellarMath.toFloat(readShBr);
				} else {
					readBr = brBuffer.asFloatBuffer().get(2);
				}

				float currentBrightness = readBr * 1000.0f / this.screenRatio;
				this.brightness += (currentBrightness - this.brightness) * 0.1f;
			}

			GL15.glUnmapBuffer(OpenGlUtil.PIXEL_PACK_BUFFER);
			GL15.glBindBuffer(OpenGlUtil.PIXEL_PACK_BUFFER, 0);

			this.prevTime = currentTime;
		}

		// HDR to LDR
		frame2.bindFramebuffer(true);
		frame2.framebufferClear();

		hdrToldr.bindShader();
		fieldBrScale.setDouble(Math.max(Math.min(
				Math.pow(4.5 * this.brightness, 0.5) * 10.0, 1000.0), 1.0));
		frame1.bindFramebufferTexture();
		frame1.renderFullQuad();
		hdrToldr.releaseShader();

		// Linear RGB to sRGB
		OpenGlUtil.bindFramebuffer(OpenGlUtil.FRAMEBUFFER_GL, this.prevFramebufferBound);

		linearToSRGB.bindShader();
		frame2.bindFramebufferTexture();
		frame2.renderFullQuad();
		linearToSRGB.releaseShader();
		this.inputRoute = null;
	}

	private void encodeLinearSceneToRgbE() {
		FramebufferCustom source = linearScene();
		frame1.bindFramebuffer(false);
		frame1.framebufferClear();

		boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
		GlStateManager.disableBlend();
		try {
			linearSceneToRgbE.bindShader();
			try {
				source.bindFramebufferTexture();
				frame1.renderFullQuad();
			} finally {
				linearSceneToRgbE.releaseShader();
			}
		} finally {
			if(blendEnabled)
				GlStateManager.enableBlend();
			else GlStateManager.disableBlend();
		}
	}

}
