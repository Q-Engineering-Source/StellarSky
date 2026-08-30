package stellarium.client.overlay.timestatus;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import stellarapi.api.gui.overlay.EnumOverlayMode;
import stellarapi.api.gui.overlay.IOverlayElement;
import stellarium.StellarSky;
import stellarium.time.StellarSkyTime;

/** Compact replacement for the old fixed RenderGameOverlayEvent HUD. */
public class TimeStatusOverlay implements IOverlayElement<TimeStatusSettings> {
    private static final int WIDTH = 190;
    private static final int HEIGHT = 40;
    private Minecraft mc;
    private TimeStatusSettings settings;

    @Override public void initialize(Minecraft mc, TimeStatusSettings settings) {
        this.mc = mc;
        this.settings = settings;
    }
    @Override public int getWidth() { return WIDTH; }
    @Override public int getHeight() { return HEIGHT; }
    @Override public float animationOffsetX(float partialTicks) { return 0; }
    @Override public float animationOffsetY(float partialTicks) { return 0; }
    @Override public void switchMode(EnumOverlayMode mode) { }
    @Override public void updateOverlay() { }

    @Override public boolean mouseClicked(int mouseX, int mouseY, int eventButton) { return false; }
    @Override public boolean mouseClickMove(int mouseX, int mouseY, int eventButton, long timeSinceLastClick) { return false; }
    @Override public boolean mouseReleased(int mouseX, int mouseY, int eventButton) { return false; }
    @Override public boolean keyTyped(char eventChar, int eventKey) { return false; }

    @Override public void render(int mouseX, int mouseY, float partialTicks) {
        if(mc.world == null || !StellarSky.PROXY.getClientSettings().showTimeMultiplierHud)
            return;
        double multiplier = StellarSkyTime.getMultiplier(mc.world);
        boolean sync = StellarSkyTime.isSystemTimeSyncEnabled(mc.world);
        if(multiplier == 1.0 && !sync)
            return;

        int y = 2;
        String scale = multiplier == Math.rint(multiplier) ? Long.toString((long) multiplier)
                : String.format(Locale.ROOT, "%.3f", multiplier);
        String text = sync ? "Time: SYSTEM" : multiplier == 0.0 ? "Time: PAUSED"
                : multiplier < 0.0 ? "Time: " + scale.substring(1) + "x (Reverse)"
                : "Time: " + scale + "x";
        draw(text, 2, y);
        draw("Mapped TZ: " + formatOffset(StellarSkyTime.getMappedTimeZoneOffsetMinutes(mc.world)), 2, y + 10);
        draw("Local solar: " + formatOffset(StellarSkyTime.getLocalSolarTimeOffsetMinutes(mc.world)), 2, y + 20);
        draw("System TZ: " + StellarSkyTime.getClientSystemTimeZoneId() + " "
                + formatOffset(StellarSkyTime.getClientSystemTimeOffsetMinutes()), 2, y + 30);
    }

    private void draw(String text, int x, int y) {
        mc.fontRenderer.drawStringWithShadow(text, x, y, 0xFFFFFF);
    }
    private static String formatOffset(int minutes) {
        int absolute = Math.abs(minutes);
        return String.format(Locale.ROOT, "UTC%c%02d:%02d", minutes < 0 ? '-' : '+',
                absolute / 60, absolute % 60);
    }
}
