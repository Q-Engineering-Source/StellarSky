package stellarium.render.stellars;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import stellarium.StellarSky;
import stellarium.client.ClientSettings;
import stellarium.render.extended.ExtendedSkyRenderer;
import stellarium.render.stellars.access.EnumStellarPass;
import stellarium.render.stellars.atmosphere.AtmosphereRenderer;
import stellarium.render.stellars.atmosphere.AtmosphereSettings;
import stellarium.render.stellars.atmosphere.EnumAtmospherePass;
import stellarium.render.stellars.layer.LayerRHelper;
import stellarium.render.stellars.phased.StellarPhasedRenderer;
import stellarium.util.OpenGlUtil;

/**
 * Renderer for various visual effects
 * */
public enum StellarRenderer {
	INSTANCE;

	private PostProcess postProcessor = new PostProcess();
	private int prevWidth = 0, prevHeight = 0;
	private UtilShaders shaders = new UtilShaders();

	public void initialize(ClientSettings settings) {
		if(!settings.lowPowerRenderer && settings.renderAtmosphere) {
			AtmosphereSettings atmSettings = (AtmosphereSettings) settings.getSubConfig(AtmosphereSettings.KEY);
			AtmosphereRenderer.INSTANCE.initialize(atmSettings);
		}
		if(!settings.lowPowerRenderer && settings.renderPostProcessing)
			postProcessor.initialize();
		shaders.reloadShaders();
		ExtendedSkyRenderer.INSTANCE.initialize(settings);
	}

	public void preRender(ClientSettings settings, StellarRI info) {
		if(!settings.lowPowerRenderer && settings.renderAtmosphere && info.atmosphereFade > 0.0) {
			AtmosphereSettings atmSettings = (AtmosphereSettings) settings.getSubConfig(AtmosphereSettings.KEY);
			AtmosphereRenderer.INSTANCE.preRender(atmSettings, info);
		}

		if(!settings.lowPowerRenderer && settings.renderPostProcessing) {
			Framebuffer mcBuffer = info.minecraft.getFramebuffer();
			if(mcBuffer.framebufferWidth != this.prevWidth || mcBuffer.framebufferHeight != this.prevHeight) {
				postProcessor.onResize(mcBuffer.framebufferWidth, mcBuffer.framebufferHeight);
				this.prevWidth = mcBuffer.framebufferWidth;
				this.prevHeight = mcBuffer.framebufferHeight;
			}
		}
	}

	public void render(StellarModel model, StellarRI info) {
		LayerRHelper layerInfo = new LayerRHelper(info, this.shaders);

		ClientSettings settings = StellarSky.PROXY.getClientSettings();
		if(settings.lowPowerRenderer) {
			GlStateManager.shadeModel(GL11.GL_SMOOTH);
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
			ExtendedSkyRenderer.INSTANCE.render(settings, info);
			StellarPhasedRenderer.INSTANCE.render(model.layersModel, EnumStellarPass.Source, layerInfo, true);

			GlStateManager.enableDepth();
			GlStateManager.depthMask(true);
			GlStateManager.disableBlend();
			StellarPhasedRenderer.INSTANCE.render(model.layersModel, EnumStellarPass.Opaque, layerInfo, true);

			GlStateManager.depthMask(true);
			GlStateManager.enableDepth();
			GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			return;
		}

		int callerFramebuffer = GlStateManager.glGetInteger(OpenGlUtil.FRAMEBUFFER_BINDING);
		boolean atmosphereActive = settings.renderAtmosphere && info.atmosphereFade > 0.0;
		boolean atmospherePrepared = false;

		try {
			if(settings.renderPostProcessing)
				postProcessor.preProcess();
			GlStateManager.shadeModel(GL11.GL_SMOOTH);
			GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
			// TODO AX Use better value for positions
			if(atmosphereActive) {
				AtmosphereRenderer.INSTANCE.render(model.atmModel, EnumAtmospherePass.Prepare, info);
				atmospherePrepared = true;
			}

			ExtendedSkyRenderer.INSTANCE.render(settings, info);
			StellarPhasedRenderer.INSTANCE.render(model.layersModel, EnumStellarPass.Source, layerInfo);

			GlStateManager.enableDepth();
			GlStateManager.depthMask(true);
			GlStateManager.disableBlend();
			StellarPhasedRenderer.INSTANCE.render(model.layersModel, EnumStellarPass.Opaque, layerInfo);

			GlStateManager.disableDepth();
			GlStateManager.depthMask(false);
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
			GlStateManager.shadeModel(GL11.GL_FLAT);

			if(atmosphereActive) {
				AtmosphereRenderer.INSTANCE.render(model.atmModel, EnumAtmospherePass.SetupDominateScatter, info);
				layerInfo.apply(info);
				StellarPhasedRenderer.INSTANCE.render(model.layersModel, EnumStellarPass.DominateScatter, layerInfo);
				AtmosphereRenderer.INSTANCE.render(model.atmModel, EnumAtmospherePass.Finalize, info);
				atmospherePrepared = false;
			}

			if(settings.renderPostProcessing)
				postProcessor.postProcess(info);
		} finally {
			try {
				if(atmospherePrepared)
					AtmosphereRenderer.INSTANCE.abort();
			} finally {
				OpenGlUtil.bindFramebuffer(OpenGlUtil.FRAMEBUFFER_GL, callerFramebuffer);
				GlStateManager.shadeModel(GL11.GL_FLAT);
				GlStateManager.depthMask(true);
				GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
				GlStateManager.enableDepth();
				GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			}
		}
	}
}
