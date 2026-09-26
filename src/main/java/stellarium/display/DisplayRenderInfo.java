package stellarium.display;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import stellarapi.api.lib.math.Vector3;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.client.ring.RingworldSpatialAirFrameOptics;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplaySnapshot;


public class DisplayRenderInfo {
	public final Minecraft mc;
	public final Tessellator tessellator;
	public final BufferBuilder builder;
	public final float partialTicks;
	/**
	 * Frozen for this display pass. A missing ringworld snapshot retains legacy
	 * atmospheric refraction exactly.
	 */
	public final double atmosphereFade;

	/**
	 * Render pass which display is rendered.
	 * To accept this as false, the depth should be farther than {@link EnumRenderPass#getDeepDepth()}.
	 * */
	public final boolean isPostCelesitals;

	public DisplayRenderInfo(Minecraft mc, Tessellator tessellator, BufferBuilder worldRenderer, float partialTicks, boolean isPostCelesitals) {
		this.mc = mc;
		this.tessellator = tessellator;
		this.builder = worldRenderer;
		this.partialTicks = partialTicks;
		this.isPostCelesitals = isPostCelesitals;
		RingworldDisplaySnapshot snapshot = mc.world == null ? null
				: RingworldRenderSnapshots.currentFor(mc.world, StellarScene.getScene(mc.world));
		RingworldSpatialAirFrameOptics optics = mc.world == null ? null
				: RingworldRenderSnapshots.currentFrameOpticsFor(mc.world, StellarScene.getScene(mc.world));
		this.atmosphereFade = optics == null ? (snapshot == null ? 1.0 : snapshot.atmosphereFade())
				: optics.legacyAtmosphereFade();
	}

	/**
	 * Writes one cached display direction without allocating a per-vertex vector.
	 * Mid-fade normalization is a small-angle visual interpolation between the
	 * raw and refracted unit directions, not a physical GLSL refraction mapping.
	 */
	public BufferBuilder posWithAtmosphereRefraction(Vector3 rawDirection, Vector3 refractedDirection) {
		if(atmosphereFade == 0.0)
			return builder.pos(rawDirection.getX(), rawDirection.getY(), rawDirection.getZ());
		if(atmosphereFade == 1.0)
			return builder.pos(refractedDirection.getX(), refractedDirection.getY(), refractedDirection.getZ());

		double rawWeight = 1.0 - atmosphereFade;
		double x = rawDirection.getX() * rawWeight + refractedDirection.getX() * atmosphereFade;
		double y = rawDirection.getY() * rawWeight + refractedDirection.getY() * atmosphereFade;
		double z = rawDirection.getZ() * rawWeight + refractedDirection.getZ() * atmosphereFade;
		double inverseLength = 1.0 / Math.sqrt(x * x + y * y + z * z);
		return builder.pos(x * inverseLength, y * inverseLength, z * inverseLength);
	}
}
