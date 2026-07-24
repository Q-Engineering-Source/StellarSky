package stellarium.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import stellarium.time.StellarSkyTime;
import stellarium.StellarSky;

@SideOnly(Side.CLIENT)
public final class TimeMultiplierHud {
	@SubscribeEvent
	public void renderText(RenderGameOverlayEvent.Text event) {
		Minecraft minecraft = Minecraft.getMinecraft();
		if(minecraft.world == null)
			return;
		if(!StellarSky.PROXY.getClientSettings().showTimeMultiplierHud)
			return;

		double multiplier = StellarSkyTime.getMultiplier(minecraft.world);
		if(StellarSkyTime.isSystemTimeSyncEnabled(minecraft.world)) {
			minecraft.fontRenderer.drawStringWithShadow("Time: SYSTEM", 2, 2, 0xFFFFFF);
			return;
		}
		if(multiplier == 1.0)
			return;

		String scale = multiplier == Math.rint(multiplier) ? Long.toString((long) multiplier)
				: String.format(java.util.Locale.ROOT, "%.3f", multiplier);
		String text = multiplier == 0.0 ? "Time: PAUSED"
				: multiplier < 0.0 ? "Time: " + scale.substring(1) + "x (Reverse)"
				: "Time: " + scale + "x";
		minecraft.fontRenderer.drawStringWithShadow(text, 2, 2, 0xFFFFFF);
	}
}
