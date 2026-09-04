package stellarium.client;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.minecraftforge.common.config.Configuration;

public class ClientSettingsTest {

	@Test
	public void clientSettingsStartsWithMoonRenderingEnabled() {
		assertTrue(new ClientSettings().renderMoon);
	}

	@Test
	public void missingMoonSettingKeepsMoonRenderingEnabled() {
		Configuration config = new Configuration();
		ClientSettings settings = new ClientSettings();
		settings.setupConfig(config, "clientconfig");
		settings.loadFromConfig(config, "clientconfig");

		assertTrue(settings.renderMoon);
	}
}
