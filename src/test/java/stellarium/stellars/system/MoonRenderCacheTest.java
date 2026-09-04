package stellarium.stellars.system;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import net.minecraftforge.common.config.Configuration;
import stellarium.client.ClientSettings;
import stellarium.render.stellars.access.EnumStellarPass;

public class MoonRenderCacheTest {

	@Test
	public void disabledMoonSettingClearsTheCacheAndSkipsEveryMoonRenderPass() {
		Configuration config = new Configuration();
		ClientSettings settings = new ClientSettings();
		settings.setupConfig(config, "clientconfig");
		config.get("clientconfig", "Render_Moon", true).set(false);
		settings.loadFromConfig(config, "clientconfig");

		SolarSystemClientSettings solarSettings = new SolarSystemClientSettings();
		solarSettings.imgFrac = 4;
		MoonRenderCache cache = new MoonRenderCache();
		cache.shouldRender = true;
		cache.shouldRenderDominate = true;
		cache.updateSettings(settings, solarSettings, null);

		assertFalse(cache.shouldRender);
		assertFalse(cache.shouldRenderDominate);
		cache.updateCache(null, null);
		MoonRenderer.INSTANCE.render(cache, EnumStellarPass.Opaque, null);
		MoonRenderer.INSTANCE.render(cache, EnumStellarPass.DominateScatter, null);
	}
}
