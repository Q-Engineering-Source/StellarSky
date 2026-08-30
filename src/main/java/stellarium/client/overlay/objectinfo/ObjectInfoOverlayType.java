package stellarium.client.overlay.objectinfo;

import stellarapi.api.gui.overlay.IOverlayType;
import stellarapi.api.gui.overlay.IRawHandler;
import stellarapi.api.gui.pos.EnumHorizontalPos;
import stellarapi.api.gui.pos.EnumVerticalPos;

public class ObjectInfoOverlayType implements IOverlayType<ObjectInfoOverlay, ObjectInfoSettings> {
    @Override public ObjectInfoOverlay generateElement() { return new ObjectInfoOverlay(); }
    @Override public ObjectInfoSettings generateSettings() { return new ObjectInfoSettings(); }
    @Override public String getName() { return "Object_Info"; }
    @Override public String overlayType() { return "Astronomy"; }
    @Override public EnumHorizontalPos defaultHorizontalPos() { return EnumHorizontalPos.RIGHT; }
    @Override public EnumVerticalPos defaultVerticalPos() { return EnumVerticalPos.DOWN; }
    @Override public boolean accepts(EnumHorizontalPos horizontal, EnumVerticalPos vertical) {
        return !(horizontal == EnumHorizontalPos.RIGHT && vertical == EnumVerticalPos.CENTER);
    }
    @Override public IRawHandler<ObjectInfoOverlay> generateRawHandler() { return null; }
    @Override public boolean isUniversal() { return false; }
    @Override public boolean isOnMain() { return true; }
}
