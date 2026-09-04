package stellarium.render.stellars.layer;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.Vector3;
import stellarium.render.stellars.StellarRI;
import stellarium.render.stellars.UtilShaders;
import stellarium.render.stellars.access.IDominateRenderer;
import stellarium.render.util.BufferBuilderEx;
import stellarium.render.util.FloatVertexFormats;
import stellarium.render.util.VertexDirect;
import stellarium.render.util.VertexReferences;

public class LayerRHelper {
	public static final double DEEP_DEPTH = 100.0;

	public final Minecraft minecraft;
	public final WorldClient world;
	public final float partialTicks;
	public final double atmosphereFade;

	public final BufferBuilderEx builder;
	public final VertexDirect renderer;

	private final UtilShaders shaders;

	private final Vector3 xAxis, yAxis;
	private final double pointArea;

	private IDominateRenderer dominater;

	public LayerRHelper(StellarRI info, UtilShaders shaders) {
		this.minecraft = info.minecraft;
		this.world = info.world;
		this.partialTicks = info.partialTicks;
		this.atmosphereFade = info.atmosphereFade;

		this.builder = VertexReferences.getBuilder();
		this.renderer = VertexReferences.getRenderer();

		this.shaders = shaders;

		Entity viewer = minecraft.getRenderViewEntity();
		Matrix3 transformer = new Matrix3().setAsRotation(0.0, 0.0, 1.0, Math.toRadians(270.0 - viewer.rotationYaw))
				.postMult(new Matrix3().setAsRotation(0.0, 1.0, 0.0, Math.toRadians(-viewer.rotationPitch)));

		this.xAxis = transformer.transform(new Vector3(0.0, info.relativeWidth / minecraft.displayWidth, 0.0));
		this.yAxis = transformer.transform(new Vector3(0.0, 0.0, info.relativeHeight / minecraft.displayHeight));

		// Point sources look darker on smaller screen size because it's 'blurred' as it's picked up by each pixel
		this.pointArea = info.relativeWidth * info.relativeHeight / minecraft.displayWidth / minecraft.displayHeight;
	}


	public void bindTexShader() {
		shaders.bindTextureShader();
	}

	public void unbindTexShader() {
		shaders.releaseShader();
	}

	public void bindTexture(ResourceLocation location) {
		minecraft.getTextureManager().bindTexture(location);
	}


	public void beginPoint() {
		shaders.bindPointShader();
		builder.begin(GL11.GL_QUADS, FloatVertexFormats.POSITION_TEX_COLOR_F);
	}

	public void endPoint() {
		builder.finishDrawing();
		renderer.draw(this.builder);
		shaders.releaseShader();
	}

	public void renderPoint(Vector3 pos, double length, float red, float green, float blue) {
		builder.pos(
				pos.getX() + (xAxis.getX() - yAxis.getX()) * length,
				pos.getY() + (xAxis.getY() - yAxis.getY()) * length,
				pos.getZ() + (xAxis.getZ() - yAxis.getZ()) * length);
		builder.tex(1.0, 0.0).color(red, green, blue, 1.0f).endVertex();
		builder.pos(
				pos.getX() + (xAxis.getX() + yAxis.getX()) * length,
				pos.getY() + (xAxis.getY() + yAxis.getY()) * length,
				pos.getZ() + (xAxis.getZ() + yAxis.getZ()) * length);
		builder.tex(1.0, 1.0).color(red, green, blue, 1.0f).endVertex();
		builder.pos(
				pos.getX() + (- xAxis.getX() + yAxis.getX()) * length,
				pos.getY() + (- xAxis.getY() + yAxis.getY()) * length,
				pos.getZ() + (- xAxis.getZ() + yAxis.getZ()) * length);
		builder.tex(0.0, 1.0).color(red, green, blue, 1.0f).endVertex();
		builder.pos(
				pos.getX() + (- xAxis.getX() - yAxis.getX()) * length,
				pos.getY() + (- xAxis.getY() - yAxis.getY()) * length,
				pos.getZ() + (- xAxis.getZ() - yAxis.getZ()) * length);
		builder.tex(0.0, 0.0).color(red, green, blue, 1.0f).endVertex();
	}

	public void renderTexturedBillboard(Vector3 direction, double depth,
			double angularRadius, float minU, float minV, float maxU, float maxV,
			float red, float green, float blue, float alpha) {
		double halfSize = Math.tan(angularRadius) * depth;
		double xScale = halfSize / xAxis.size();
		double yScale = halfSize / yAxis.size();
		double centerX = direction.getX() * depth;
		double centerY = direction.getY() * depth;
		double centerZ = direction.getZ() * depth;

		builder.pos(
				centerX + xAxis.getX() * xScale - yAxis.getX() * yScale,
				centerY + xAxis.getY() * xScale - yAxis.getY() * yScale,
				centerZ + xAxis.getZ() * xScale - yAxis.getZ() * yScale);
		builder.tex(maxU, minV).color(red, green, blue, alpha).endVertex();
		builder.pos(
				centerX + xAxis.getX() * xScale + yAxis.getX() * yScale,
				centerY + xAxis.getY() * xScale + yAxis.getY() * yScale,
				centerZ + xAxis.getZ() * xScale + yAxis.getZ() * yScale);
		builder.tex(maxU, maxV).color(red, green, blue, alpha).endVertex();
		builder.pos(
				centerX - xAxis.getX() * xScale + yAxis.getX() * yScale,
				centerY - xAxis.getY() * xScale + yAxis.getY() * yScale,
				centerZ - xAxis.getZ() * xScale + yAxis.getZ() * yScale);
		builder.tex(minU, maxV).color(red, green, blue, alpha).endVertex();
		builder.pos(
				centerX - xAxis.getX() * xScale - yAxis.getX() * yScale,
				centerY - xAxis.getY() * xScale - yAxis.getY() * yScale,
				centerZ - xAxis.getZ() * xScale - yAxis.getZ() * yScale);
		builder.tex(minU, minV).color(red, green, blue, alpha).endVertex();
	}

	/** Area of a point in (rad)^2 */
	public double pointArea() {
		return this.pointArea;
	}


	public void apply(StellarRI info) {
		this.dominater = info.getDominateRenderer();
	}

	public void renderDominate(Vector3 lightDir, float red, float green, float blue) {
		dominater.renderDominate(lightDir, red, green, blue);
	}
}
