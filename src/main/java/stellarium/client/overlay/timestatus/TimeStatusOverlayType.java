package stellarium.client.overlay.timestatus;

import stellarapi.api.gui.overlay.IOverlayType;
import stellarapi.api.gui.overlay.IRawHandler;
import stellarapi.api.gui.pos.EnumHorizontalPos;
import stellarapi.api.gui.pos.EnumVerticalPos;

public class TimeStatusOverlayType implements IOverlayType<TimeStatusOverlay, TimeStatusSettings> {
    @Override public TimeStatusOverlay generateElement() { return new TimeStatusOverlay(); }
    @Override public TimeStatusSettings generateSettings() { return new TimeStatusSettings(); }
    @Override public String getName() { return "Time_Status"; }
    @Override public String overlayType() { return "Time"; }
    @Override public EnumHorizontalPos defaultHorizontalPos() { return EnumHorizontalPos.LEFT; }
    @Override public EnumVerticalPos defaultVerticalPos() { return EnumVerticalPos.CENTER; }
    @Override public boolean accepts(EnumHorizontalPos horizontal, EnumVerticalPos vertical) {
        return !(horizontal == EnumHorizontalPos.RIGHT && vertical == EnumVerticalPos.CENTER);
    }
    @Override public IRawHandler<TimeStatusOverlay> generateRawHandler() { return null; }
    @Override public boolean isUniversal() { return false; }
    @Override public boolean isOnMain() { return true; }
}
