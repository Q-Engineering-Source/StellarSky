package stellarium.render.stellars;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import stellarapi.api.lib.math.Spmath;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.render.SkyRI;
import stellarium.render.stellars.access.IDominateRenderer;
import stellarium.util.MCUtil;
import stellarium.view.ViewerInfo;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplaySnapshot;

public class StellarRI {
	public final Minecraft minecraft;
	public final WorldClient world;
	public final float partialTicks;

	public final ViewerInfo info;
	public final double screenSize;
	public final double relativeWidth, relativeHeight;
	public final RingworldDisplaySnapshot ringworldSnapshot;
	public final double atmosphereFade;
	private IDominateRenderer dominater;

	public StellarRI(SkyRI info) {
		this.minecraft = info.minecraft;
		this.world = info.world;
		this.partialTicks = info.partialTicks;

		this.info = info.info;
		this.screenSize = info.screenSize;
		this.ringworldSnapshot = RingworldRenderSnapshots.currentFor(info.world,
				StellarScene.getScene(info.world));
		this.atmosphereFade = this.ringworldSnapshot == null ? 1.0
				: this.ringworldSnapshot.atmosphereFade();

		this.relativeHeight = 2 * Math.tan(0.5 *
				Math.toRadians(MCUtil.getFOVModifier(info.minecraft.entityRenderer, info.partialTicks, true)));
		this.relativeWidth = (this.relativeHeight * info.minecraft.displayWidth) / info.minecraft.displayHeight;
	}

	public void setDominateRenderer(IDominateRenderer renderer) {
		this.dominater = renderer;
	}

	public IDominateRenderer getDominateRenderer() {
		return this.dominater;
	}
}
