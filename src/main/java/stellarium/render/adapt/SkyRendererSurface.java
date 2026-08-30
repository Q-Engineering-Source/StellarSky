package stellarium.render.adapt;

import java.lang.reflect.Field;

import org.lwjgl.opengl.GL11;

import com.google.common.base.Throwables;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import stellarapi.api.render.IAdaptiveRenderer;
import stellarium.StellarSky;
import stellarium.client.ClientSettings;
import stellarium.world.AtmosphereGeometry;
import stellarium.world.StellarScene;

public class SkyRendererSurface extends IAdaptiveRenderer {

	private IRenderHandler subRenderer;
	private IRenderHandler otherRenderer;

	private static Field skyVBOField = ReflectionHelper.findField(RenderGlobal.class, "skyVBO", "field_175012_t");
	private static Field sky2VBOField = ReflectionHelper.findField(RenderGlobal.class, "sky2VBO", "field_175011_u");
	private static Field glSkyListField = ReflectionHelper.findField(RenderGlobal.class, "glSkyList", "field_72771_w");
	private static Field glSkyList2Field = ReflectionHelper.findField(RenderGlobal.class, "glSkyList2", "field_72781_x");

	private static Field starVBOField = ReflectionHelper.findField(RenderGlobal.class, "starVBO", "field_175013_s");
	private static Field glStarListField = ReflectionHelper.findField(RenderGlobal.class, "starGLCallList", "field_72772_v");

	private static Field vertexBufferField = ReflectionHelper.findField(Tessellator.class, "buffer", "field_178183_a");

	private static int skyList, skyList2, starList;
	private static net.minecraft.client.renderer.vertex.VertexBuffer skyVBO, sky2VBO, starVBO;
	private static BufferBuilder placeholder, backgroundPlaceholder;

	static {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder vertexbuffer = tessellator.getBuffer();

        skyList = GLAllocation.generateDisplayLists(1);
        GlStateManager.glNewList(skyList, 4864);
        GlStateManager.glEndList();
        
        skyList2 = GLAllocation.generateDisplayLists(1);
        GlStateManager.glNewList(skyList2, 4864);
        GlStateManager.glEndList();

        starList = GLAllocation.generateDisplayLists(1);
        GlStateManager.glNewList(starList, 4864);
        GlStateManager.glEndList();

        skyVBO = new net.minecraft.client.renderer.vertex.VertexBuffer(DefaultVertexFormats.POSITION);
        vertexbuffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        vertexbuffer.finishDrawing();
        vertexbuffer.reset();
        skyVBO.bufferData(vertexbuffer.getByteBuffer());
        
        sky2VBO = new net.minecraft.client.renderer.vertex.VertexBuffer(DefaultVertexFormats.POSITION);
        vertexbuffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        vertexbuffer.finishDrawing();
        vertexbuffer.reset();
        sky2VBO.bufferData(vertexbuffer.getByteBuffer());

        starVBO =  new net.minecraft.client.renderer.vertex.VertexBuffer(DefaultVertexFormats.POSITION);
        vertexbuffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        vertexbuffer.finishDrawing();
        vertexbuffer.reset();
        starVBO.bufferData(vertexbuffer.getByteBuffer());

        placeholder = new BufferBuilderPlaceholder(32768,
                GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        backgroundPlaceholder = new BufferBuilderPlaceholder(32768,
                GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);

		skyVBOField.setAccessible(true);
		sky2VBOField.setAccessible(true);
		starVBOField.setAccessible(true);

		glSkyListField.setAccessible(true);
		glSkyList2Field.setAccessible(true);
		glStarListField.setAccessible(true);

		vertexBufferField.setAccessible(true);
	}

	public SkyRendererSurface(IRenderHandler subRenderer) {
		this.subRenderer = subRenderer;
	}

	@Override
	public void setReplacedRenderer(IRenderHandler handler) {
		this.otherRenderer = handler;
	}

	@Override
	public void render(float partialTicks, WorldClient world, Minecraft mc) {
		GlStateManager.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
		GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);

		StellarScene dimManager = StellarScene.getScene(world);
		ClientSettings settings = StellarSky.PROXY.getClientSettings();
		if(settings.lowPowerRenderer) {
			this.renderLowPowerSky(partialTicks, world, mc, dimManager);
			subRenderer.render(partialTicks, world, mc);
			return;
		}

		if(dimManager.getSettings().renderPrevSky()) {
			RenderGlobal renderGlobal = mc.renderGlobal;
			float lat = (float) dimManager.getSettings().latitude;

			try {
				net.minecraft.client.renderer.vertex.VertexBuffer sky1 = (net.minecraft.client.renderer.vertex.VertexBuffer)skyVBOField.get(renderGlobal);
				net.minecraft.client.renderer.vertex.VertexBuffer sky2 = (net.minecraft.client.renderer.vertex.VertexBuffer)sky2VBOField.get(renderGlobal);
				net.minecraft.client.renderer.vertex.VertexBuffer star = (net.minecraft.client.renderer.vertex.VertexBuffer)starVBOField.get(renderGlobal);
				int sky1id = (Integer)glSkyListField.get(renderGlobal);
				int sky2id = (Integer)glSkyList2Field.get(renderGlobal);
				int starid = (Integer)glStarListField.get(renderGlobal);

				BufferBuilder buffer = (BufferBuilder)vertexBufferField.get(Tessellator.getInstance());
				
				skyVBOField.set(renderGlobal, skyVBO);
				sky2VBOField.set(renderGlobal, sky2VBO);
				starVBOField.set(renderGlobal, starVBO);
				glSkyListField.set(renderGlobal, skyList);
				glSkyList2Field.set(renderGlobal, skyList2);
				glStarListField.set(renderGlobal, starList);
	
				vertexBufferField.set(Tessellator.getInstance(), placeholder);

				GlStateManager.pushMatrix();
				GlStateManager.rotate(lat, 1.0f, 0.0f, 0.0f);
				if(this.otherRenderer != null)
					otherRenderer.render(partialTicks, world, mc);
				else {
					IRenderHandler renderer = world.provider.getSkyRenderer();
					world.provider.setSkyRenderer(null);
					renderGlobal.renderSky(partialTicks, 0);
					world.provider.setSkyRenderer(renderer);
				}
				GlStateManager.popMatrix();

				skyVBOField.set(renderGlobal, sky1);
				sky2VBOField.set(renderGlobal, sky2);
				starVBOField.set(renderGlobal, star);
				glSkyListField.set(renderGlobal, sky1id);
				glSkyList2Field.set(renderGlobal, sky2id);
				glStarListField.set(renderGlobal, starid);

				vertexBufferField.set(Tessellator.getInstance(), buffer);
			} catch (Exception exc) {
				throw new RuntimeException(exc);
			}

			this.renderDarkening(partialTicks, world, mc);
		}

		subRenderer.render(partialTicks, world, mc);
	}

	/**
	 * Cheap full-sky gradient using the same apparent-horizon geometry as the
	 * physical atmosphere. This avoids RenderGlobal's hard black void cube,
	 * which is tied to the vanilla fixed world horizon.
	 */
	private void renderLowPowerSky(float partialTicks, WorldClient world,
			Minecraft mc, StellarScene scene) {
		Entity viewer = mc.getRenderViewEntity();
		if(viewer == null)
			return;

		Vec3d sky = world.getSkyColor(viewer, partialTicks);
		Vec3d fog = world.getFogColor(partialTicks);
		double observerY = viewer.prevPosY
				+ (viewer.posY - viewer.prevPosY) * partialTicks;
		double apparentHorizon = 0.0;
		if(scene != null) {
			double height = AtmosphereGeometry.resolveHeight(world, observerY,
					scene.getSettings());
			apparentHorizon = -AtmosphereGeometry.horizonDepressionDegrees(
					height, scene.getSettings().getInnerRadius());
		}

		boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		boolean fogEnabled = GL11.glIsEnabled(GL11.GL_FOG);
		boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
		boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
		GlStateManager.disableTexture2D();
		GlStateManager.disableFog();
		GlStateManager.disableAlpha();
		GlStateManager.disableBlend();
		GlStateManager.disableCull();
		GlStateManager.depthMask(false);
		GlStateManager.shadeModel(GL11.GL_SMOOTH);

		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		final int longitudeSegments = 64;
		final int latitudeSegments = 24;
		final double radius = 100.0;
		for(int latitude = 0; latitude < latitudeSegments; latitude++) {
			double altitude0 = -90.0 + 180.0 * latitude / latitudeSegments;
			double altitude1 = -90.0 + 180.0 * (latitude + 1) / latitudeSegments;
			float[] color0 = lowPowerSkyColor(altitude0, apparentHorizon, sky, fog);
			float[] color1 = lowPowerSkyColor(altitude1, apparentHorizon, sky, fog);
			buffer.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
			for(int longitude = 0; longitude <= longitudeSegments; longitude++) {
				double azimuth = Math.PI * 2.0 * longitude / longitudeSegments;
				addSkyVertex(buffer, radius, altitude0, azimuth, color0);
				addSkyVertex(buffer, radius, altitude1, azimuth, color1);
			}
			tessellator.draw();
		}

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.depthMask(true);
		if(cull) GlStateManager.enableCull();
		if(fogEnabled) GlStateManager.enableFog();
		if(alpha) GlStateManager.enableAlpha();
		if(blend) GlStateManager.enableBlend();
		GlStateManager.enableTexture2D();
		GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
	}

	private static void addSkyVertex(BufferBuilder buffer, double radius,
			double altitudeDegrees, double azimuth, float[] color) {
		double altitude = Math.toRadians(altitudeDegrees);
		double horizontal = Math.cos(altitude) * radius;
		buffer.pos(horizontal * Math.cos(azimuth), Math.sin(altitude) * radius,
				horizontal * Math.sin(azimuth))
				.color(color[0], color[1], color[2], 1.0f).endVertex();
	}

	private static float[] lowPowerSkyColor(double altitude, double horizon,
			Vec3d sky, Vec3d fog) {
		double r, g, b;
		if(altitude >= horizon) {
			double blend = smoothstep(horizon, horizon + 22.0, altitude);
			r = mix(fog.x, sky.x, blend);
			g = mix(fog.y, sky.y, blend);
			b = mix(fog.z, sky.z, blend);
		} else {
			double blend = smoothstep(horizon - 18.0, horizon, altitude);
			r = mix(fog.x * 0.32, fog.x, blend);
			g = mix(fog.y * 0.35, fog.y, blend);
			b = mix(fog.z * 0.42, fog.z, blend);
		}
		return new float[] {(float) clampColor(r), (float) clampColor(g),
				(float) clampColor(b)};
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
		return t * t * (3.0 - 2.0 * t);
	}

	private static double mix(double first, double second, double amount) {
		return first + (second - first) * amount;
	}

	private static double clampColor(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	/**
	 * Low-power mode keeps the vanilla sky gradient while suppressing its
	 * stars, sun, and moon. StellarSky renders the celestial objects afterwards.
	 */
	private void renderVanillaSky(float partialTicks, WorldClient world, Minecraft mc) {
		RenderGlobal renderGlobal = mc.renderGlobal;
		net.minecraft.client.renderer.vertex.VertexBuffer savedStar = null;
		int savedStarList = -1;
		BufferBuilder savedBuffer = null;
		try {
			savedStar =
					(net.minecraft.client.renderer.vertex.VertexBuffer) starVBOField.get(renderGlobal);
			savedStarList = (Integer) glStarListField.get(renderGlobal);
			savedBuffer = (BufferBuilder) vertexBufferField.get(Tessellator.getInstance());
			starVBOField.set(renderGlobal, starVBO);
			glStarListField.set(renderGlobal, starList);
			vertexBufferField.set(Tessellator.getInstance(), backgroundPlaceholder);

			if(this.otherRenderer != null)
				this.otherRenderer.render(partialTicks, world, mc);
			else {
				IRenderHandler renderer = world.provider.getSkyRenderer();
				world.provider.setSkyRenderer(null);
				try {
					// RenderGlobal's sky dome is already in camera-horizontal
					// coordinates. Latitude belongs in StellarSky's celestial
					// coordinate transform, not as a rotation of this dome.
					renderGlobal.renderSky(partialTicks, 0);
				} finally {
					world.provider.setSkyRenderer(renderer);
				}
			}
		} catch (Exception exc) {
			throw new RuntimeException(exc);
		} finally {
			try {
				if(savedStar != null)
					starVBOField.set(renderGlobal, savedStar);
				if(savedStarList >= 0)
					glStarListField.set(renderGlobal, savedStarList);
				if(savedBuffer != null)
					vertexBufferField.set(Tessellator.getInstance(), savedBuffer);
			} catch(IllegalAccessException exc) {
				throw new RuntimeException(exc);
			}
		}
	}

	private void renderDarkening(float partialTicks, WorldClient world, Minecraft mc) {
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder vertexbuffer = tessellator.getBuffer();
		float brightness = (float) world.getSunBrightness(partialTicks);

		GlStateManager.disableAlpha();
		GlStateManager.disableFog();
		GlStateManager.enableBlend();
		GlStateManager.depthMask(false);
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

		for (int i = 0; i < 6; ++i)
		{
			GlStateManager.pushMatrix();

			if (i == 1)
				GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);

			if (i == 2)
				GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);

			if (i == 3)
				GlStateManager.rotate(180.0F, 1.0F, 0.0F, 0.0F);

			if (i == 4)
				GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);

			if (i == 5)
				GlStateManager.rotate(-90.0F, 0.0F, 0.0F, 1.0F);

			vertexbuffer.begin(7, DefaultVertexFormats.POSITION_COLOR);
			vertexbuffer.pos(-100.0D, -100.0D, -100.0D).color(0.0f, 0.0f, 0.0f, 0.5f + 0.1f * brightness).endVertex();
			vertexbuffer.pos(-100.0D, -100.0D, 100.0D).color(0.0f, 0.0f, 0.0f, 0.5f + 0.1f * brightness).endVertex();
			vertexbuffer.pos(100.0D, -100.0D, 100.0D).color(0.0f, 0.0f, 0.0f, 0.5f + 0.1f * brightness).endVertex();
			vertexbuffer.pos(100.0D, -100.0D, -100.0D).color(0.0f, 0.0f, 0.0f, 0.5f + 0.1f * brightness).endVertex();

			tessellator.draw();
			GlStateManager.popMatrix();
		}

		GlStateManager.enableFog();
		GlStateManager.enableAlpha();
		GlStateManager.depthMask(true);
	}

	private static class BufferBuilderPlaceholder extends BufferBuilder {

		private boolean flag = false;
		private final int excludedMode;
		private final VertexFormat excludedFormat;

		public BufferBuilderPlaceholder(int bufferSizeIn, int excludedMode,
				VertexFormat excludedFormat) {
			super(bufferSizeIn);
			this.excludedMode = excludedMode;
			this.excludedFormat = excludedFormat;
		}

		@Override
		public void begin(int glMode, VertexFormat format) {
			super.begin(glMode, format);
			if(glMode == this.excludedMode && format == this.excludedFormat)
				this.flag = true;
		}

		@Override
		public int getVertexCount() {
			return this.flag? 0 : super.getVertexCount();
		}

		@Override
		public void reset() {
			this.flag = false;
			super.reset();
		}
	}
}
