package stellarium.common;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public class ServerSettingsTest {
	@Test
	public void migratesIntegerTimeMultiplierWithoutLosingItsValue() {
		Configuration migratedConfig = new Configuration();
		migratedConfig.getCategory("serverconfig").put("Time_Multiplier",
				new Property("Time_Multiplier", "7", Property.Type.INTEGER));
		ServerSettings settings = new ServerSettings();
		settings.setupConfig(migratedConfig, "serverconfig");
		settings.loadFromConfig(migratedConfig, "serverconfig");

		assertEquals(Property.Type.DOUBLE,
				migratedConfig.getCategory("serverconfig").get("Time_Multiplier").getType());
		assertEquals(7.0, settings.timeMultiplier, 0.0);
	}
}
