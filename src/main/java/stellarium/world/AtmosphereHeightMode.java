package stellarium.world;

import java.util.Locale;

/**
 * Selects the source used to place the observer inside the spherical
 * atmosphere model.
 */
public enum AtmosphereHeightMode {
	MINECRAFT_Y("minecraft_y"),
	OBSERVER_ALTITUDE("observer_altitude"),
	FIXED("fixed");

	public static final String[] NAMES = {
			MINECRAFT_Y.serializedName,
			OBSERVER_ALTITUDE.serializedName,
			FIXED.serializedName
	};

	private final String serializedName;

	AtmosphereHeightMode(String serializedName) {
		this.serializedName = serializedName;
	}

	public String getSerializedName() {
		return this.serializedName;
	}

	public static AtmosphereHeightMode fromName(String name) {
		if(name != null) {
			String normalized = name.toLowerCase(Locale.ROOT);
			for(AtmosphereHeightMode mode : values()) {
				if(mode.serializedName.equals(normalized))
					return mode;
			}
		}
		return MINECRAFT_Y;
	}
}
