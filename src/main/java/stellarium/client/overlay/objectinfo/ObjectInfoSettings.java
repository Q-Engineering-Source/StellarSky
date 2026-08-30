package stellarium.client.overlay.objectinfo;

import net.minecraftforge.common.config.Configuration;
import stellarapi.api.gui.overlay.PerOverlaySettings;
import stellarapi.api.lib.config.property.ConfigPropertyBoolean;
import stellarapi.api.lib.config.property.ConfigPropertyDouble;

public class ObjectInfoSettings extends PerOverlaySettings {
    boolean enabled = true;
    double cursorToleranceDegrees = 0.75;
    boolean showCoordinates = true;

    private final ConfigPropertyBoolean propEnabled =
            new ConfigPropertyBoolean("Enabled", "", enabled);
    private final ConfigPropertyDouble propCursorTolerance =
            new ConfigPropertyDouble("Cursor_Tolerance_Degrees", "", cursorToleranceDegrees);
    private final ConfigPropertyBoolean propShowCoordinates =
            new ConfigPropertyBoolean("Show_Coordinates", "", showCoordinates);

    public ObjectInfoSettings() {
        addConfigProperty(propEnabled);
        addConfigProperty(propCursorTolerance);
        addConfigProperty(propShowCoordinates);
    }

    @Override
    public void setupConfig(Configuration config, String category) {
        config.setCategoryComment(category, "Celestial object information overlay settings.");
        config.setCategoryRequiresMcRestart(category, false);
        super.setupConfig(config, category);
        propEnabled.setComment("Enable crosshair celestial-object identification.");
        propEnabled.setRequiresMcRestart(false);
        propCursorTolerance.setComment("Selection radius at the normal 70 degree field of view.");
        propCursorTolerance.setMinValue(0.1);
        propCursorTolerance.setMaxValue(3.0);
        propCursorTolerance.setRequiresMcRestart(false);
        propShowCoordinates.setComment("Show horizontal and equatorial coordinates.");
        propShowCoordinates.setRequiresMcRestart(false);
    }

    @Override
    public void loadFromConfig(Configuration config, String category) {
        super.loadFromConfig(config, category);
        enabled = propEnabled.getBoolean();
        cursorToleranceDegrees = propCursorTolerance.getDouble();
        showCoordinates = propShowCoordinates.getBoolean();
    }

    @Override
    public void saveToConfig(Configuration config, String category) {
        propEnabled.setBoolean(enabled);
        propCursorTolerance.setDouble(cursorToleranceDegrees);
        propShowCoordinates.setBoolean(showCoordinates);
        super.saveToConfig(config, category);
    }
}
