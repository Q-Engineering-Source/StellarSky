package stellarium.client.overlay.objectinfo;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import stellarapi.api.gui.overlay.EnumOverlayMode;
import stellarapi.api.gui.overlay.IOverlayElement;
import stellarapi.api.lib.math.SpCoord;

public class ObjectInfoOverlay implements IOverlayElement<ObjectInfoSettings> {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 70;
    private final CelestialTargetTracker tracker = new CelestialTargetTracker();
    private Minecraft mc;
    private ObjectInfoSettings settings;

    @Override public void initialize(Minecraft mc, ObjectInfoSettings settings) {
        this.mc = mc;
        this.settings = settings;
    }
    @Override public int getWidth() { return WIDTH; }
    @Override public int getHeight() { return HEIGHT; }
    @Override public float animationOffsetX(float partialTicks) { return 0; }
    @Override public float animationOffsetY(float partialTicks) { return 0; }
    @Override public void switchMode(EnumOverlayMode mode) { }
    @Override public void updateOverlay() {
        if(settings.enabled)
            tracker.update(mc, settings.cursorToleranceDegrees);
    }
    @Override public boolean mouseClicked(int mouseX, int mouseY, int eventButton) { return false; }
    @Override public boolean mouseClickMove(int mouseX, int mouseY, int eventButton, long timeSinceLastClick) { return false; }
    @Override public boolean mouseReleased(int mouseX, int mouseY, int eventButton) { return false; }
    @Override public boolean keyTyped(char eventChar, int eventKey) { return false; }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        if(!settings.enabled || mc.world == null)
            return;
        Gui.drawRect(0, 0, WIDTH, HEIGHT, 0x78000000);
        CelestialTarget target = tracker.getTarget();
        if(target == null) {
            boolean chinese = isChineseLocale();
            draw(chinese ? "没有指向天体" : "No celestial target", 5, 5, 0xFFB8C2CC);
            if(settings.showCoordinates) {
                SpCoord cursor = tracker.getCursorHorizontal();
                draw(String.format(Locale.ROOT, chinese
                        ? "光标  高度 %+.2f 度  方位 %.2f 度"
                        : "Cursor  Alt %+.2f deg  Az %.2f deg",
                        cursor.y, normalizeDegrees(90.0 - cursor.x)), 5, 18, 0xFF8F9AA5);
            }
            return;
        }

        boolean chinese = isChineseLocale();
        draw(formatDisplayName(target), 5, 5, 0xFFFFFFFF);
        String detail = translateType(target.type, chinese) + "  mag "
                + formatMagnitude(target.magnitude);
        if(target.phase != null)
            detail += String.format(Locale.ROOT, chinese ? "  月相 %.0f%%" : "  phase %.0f%%",
                    target.phase * 100.0);
        draw(detail, 5, 17, 0xFFB8C2CC);
        if(settings.showCoordinates) {
            draw(String.format(Locale.ROOT, chinese
                    ? "高度 %+.2f 度  方位 %.2f 度"
                    : "Alt %+.2f deg  Az %.2f deg",
                    target.altitude, target.azimuth), 5, 31, 0xFFD8DEE4);
            draw((chinese ? "赤经 " : "RA ") + formatRightAscension(target.rightAscension)
                    + String.format(Locale.ROOT, chinese ? "  赤纬 %+.2f 度" : "  Dec %+.2f deg",
                            target.declination),
                    5, 43, 0xFFD8DEE4);
        }
    }

    private static String formatDisplayName(CelestialTarget target) {
        String identifier = target.identifier == null ? "" : target.identifier;
        String english = target.englishName == null ? "" : target.englishName.trim();
        String chinese = target.chineseName == null ? "" : target.chineseName.trim();
        StringBuilder result = new StringBuilder(identifier);
        if(!chinese.isEmpty() && !chinese.equals(english))
            result.append("  ").append(chinese);
        if(!english.isEmpty() && !english.equals(chinese))
            result.append(" / ").append(english);
        return result.length() == 0 ? "Unknown celestial object" : result.toString();
    }

    private String translateType(String type, boolean chinese) {
        if(!chinese) return type;
        if("Star".equals(type)) return "恒星";
        if("Planet".equals(type)) return "行星";
        if("Satellite".equals(type)) return "卫星";
        if("Galaxy".equals(type)) return "星系";
        if("Globular cluster".equals(type)) return "球状星团";
        if("Open cluster".equals(type)) return "疏散星团";
        if("Planetary nebula".equals(type)) return "行星状星云";
        if("Supernova remnant".equals(type)) return "超新星遗迹";
        if("Nebula".equals(type)) return "星云";
        if("Deep-sky object".equals(type)) return "深空天体";
        return type;
    }

    private boolean isChineseLocale() {
        return mc != null && mc.getLanguageManager() != null
                && mc.getLanguageManager().getCurrentLanguage() != null
                && mc.getLanguageManager().getCurrentLanguage().getLanguageCode()
                        .toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private void draw(String text, int x, int y, int color) {
        mc.fontRenderer.drawStringWithShadow(text, x, y, color);
    }

    private static String formatMagnitude(double magnitude) {
        return Double.isFinite(magnitude) ? String.format(Locale.ROOT, "%.2f", magnitude) : "n/a";
    }

    private static String formatRightAscension(double degrees) {
        double hours = normalizeDegrees(degrees) / 15.0;
        int wholeHours = (int) hours;
        int minutes = (int) Math.round((hours - wholeHours) * 60.0);
        if(minutes == 60) { wholeHours = (wholeHours + 1) % 24; minutes = 0; }
        return String.format(Locale.ROOT, "%02dh %02dm", wholeHours, minutes);
    }

    private static double normalizeDegrees(double degrees) {
        double result = degrees % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }
}
