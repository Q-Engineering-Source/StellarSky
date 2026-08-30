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
		boolean systemTimeSync = StellarSkyTime.isSystemTimeSyncEnabled(minecraft.world);
		if(multiplier == 1.0 && !systemTimeSync)
			return;

		String scale = multiplier == Math.rint(multiplier) ? Long.toString((long) multiplier)
				: String.format(java.util.Locale.ROOT, "%.3f", multiplier);
		String text = systemTimeSync ? "Time: SYSTEM"
				: multiplier == 0.0 ? "Time: PAUSED"
				: multiplier < 0.0 ? "Time: " + scale.substring(1) + "x (Reverse)"
				: "Time: " + scale + "x";
		int y = 2;
		minecraft.fontRenderer.drawStringWithShadow(text, 2, y, 0xFFFFFF);
		y += 10;

		int mapped = StellarSkyTime.getMappedTimeZoneOffsetMinutes(minecraft.world);
		int localSolar = StellarSkyTime.getLocalSolarTimeOffsetMinutes(minecraft.world);
		int system = StellarSkyTime.getClientSystemTimeOffsetMinutes();
		minecraft.fontRenderer.drawStringWithShadow("Mapped TZ: " + formatOffset(mapped), 2, y, 0xFFFFFF);
		minecraft.fontRenderer.drawStringWithShadow("Local solar: " + formatOffset(localSolar), 2, y + 10, 0xFFFFFF);
		minecraft.fontRenderer.drawStringWithShadow("System TZ: "
				+ StellarSkyTime.getClientSystemTimeZoneId() + " " + formatOffset(system), 2, y + 20, 0xFFFFFF);
	}

	private static String formatOffset(int minutes) {
		int sign = minutes < 0 ? -1 : 1;
		int absolute = Math.abs(minutes);
		return String.format(java.util.Locale.ROOT, "UTC%c%02d:%02d",
				sign < 0 ? '-' : '+', absolute / 60, absolute % 60);
	}
}
