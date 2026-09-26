package stellarium.stellars.layer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;
import stellarium.CommonProxy;
import stellarium.IProxy;
import stellarium.StellarSky;
import stellarium.build.StellarBuildProfile;
import stellarium.client.ClientSettings;
import stellarium.common.ServerSettings;
import stellarium.stellars.StellarManager;
import stellarium.stellars.deepsky.LayerDeepSky;
import stellarium.stellars.milkyway.LayerMilkyway;
import stellarium.stellars.star.brstar.LayerBrStar;
import stellarium.stellars.system.LayerSolarSystem;

public class StellarLayerRegistryBuildProfileTest {
	private static final String[] STAR_RESOURCES = {
			"assets/stellarium/catalog/stars_0.cat",
			"assets/stellarium/catalog/stars_1.cat",
			"assets/stellarium/catalog/stars_2.cat",
			"assets/stellarium/catalog/stars_3.cat",
			"assets/stellarium/catalog/names/common_star_names.fab",
			"assets/stellarium/catalog/names/star_names.zh_CN.fab",
			"data/bsc5.dat"
	};
	private static final String[] DEEP_SKY_RESOURCES = {
			"assets/stellarium/catalog/dso_catalog.txt",
			"assets/stellarium/catalog/names/dso_names.zh_CN.fab",
			"assets/stellarium/catalog/names/modern_iau.json",
			"assets/stellarium/dso/textures.json",
			"assets/stellarium/deepsky/messier/messier.json",
			"assets/stellarium/stellar/milkyway.png",
			"assets/stellarium/stellar/extended_milkyway.png",
			"assets/stellarium/textures/gui/milkyway.png",
			"assets/stellarium/textures/gui/milkywayfrag.png",
			"assets/stellarium/textures/gui/milkywaybrightness.png"
	};

	@Test
	public void localAndRemoteCelestialGraphsUseTheSameBuildProfileLayerList() {
		assertExpectedLayerTypes(new CelestialManager(false).getLayers());
		assertExpectedLayerTypes(new CelestialManager(true).getLayers());
	}

	@Test
	public void profileConstantsDescribeTheSelectedResourceSet() {
		assertEquals(!StellarBuildProfile.CATALOGUE_ONLY, StellarBuildProfile.INCLUDE_DEEP_SKY);
	}

	@Test
	public void selectedProfilePackagesStarsAndOnlyItsAllowedDeepSkyResources() {
		ClassLoader classLoader = StellarLayerRegistryBuildProfileTest.class.getClassLoader();
		for(String resource : STAR_RESOURCES)
			assertNotNull("both profiles must package " + resource, classLoader.getResource(resource));
		for(String resource : DEEP_SKY_RESOURCES) {
			if(StellarBuildProfile.INCLUDE_DEEP_SKY)
				assertNotNull("standard profile must package " + resource, classLoader.getResource(resource));
			else
				assertNull("catalogue profile must not package " + resource, classLoader.getResource(resource));
		}
	}

	@Test
	public void selectedProfileInitializesItsClientAndCommonLayerGraphs() {
		StellarSky previousInstance = StellarSky.INSTANCE;
		IProxy previousProxy = StellarSky.PROXY;
		try {
			StellarSky.INSTANCE = new TestStellarSky();
			ClientSettings clientSettings = new ClientSettings();
			StellarLayerRegistry.getInstance().composeSettings(clientSettings);
			Configuration configuration = new Configuration();
			clientSettings.setupConfig(configuration, "clientconfig");
			clientSettings.loadFromConfig(configuration, "clientconfig");
			// Bright-star initialization obtains the active client's magnitude
			// limit through the injected proxy, even though its data load is CPU-only.
			StellarSky.PROXY = new CommonProxy() {
				@Override
				public ClientSettings getClientSettings() { return clientSettings; }
			};
			CelestialManager clientGraph = new CelestialManager(true);
			clientGraph.initializeClient(clientSettings);
			assertExpectedLayerTypes(clientGraph.getLayers());

			StellarManager manager = new StellarManager("profile-layer-graph");
			NBTTagCompound persistedSettings = new NBTTagCompound();
			persistedSettings.setBoolean("locked", true);
			persistedSettings.setDouble("day", ServerSettings.DEFAULT_DAY_LENGTH_TICKS);
			manager.syncFromNBT(persistedSettings, true);
			CelestialManager commonGraph = new CelestialManager(false);
			commonGraph.initializeCommon(manager, manager.getSettings());
			assertExpectedLayerTypes(commonGraph.getLayers());
			assertTrue("both profiles must still initialize the SolarSystem sun",
					commonGraph.getLayers().stream().anyMatch(layer ->
							layer.getType() instanceof LayerSolarSystem
									&& layer.getLoadedObjects("sun").size() == 1));
		} finally {
			StellarSky.PROXY = previousProxy;
			StellarSky.INSTANCE = previousInstance;
		}
	}

	private static void assertExpectedLayerTypes(List<StellarCollection> layers) {
		if(StellarBuildProfile.CATALOGUE_ONLY) {
			assertEquals(2, layers.size());
			assertEquals(LayerBrStar.class, layers.get(0).getType().getClass());
			assertEquals(LayerSolarSystem.class, layers.get(1).getType().getClass());
			return;
		}

		assertEquals(4, layers.size());
		assertEquals(LayerBrStar.class, layers.get(0).getType().getClass());
		assertEquals(LayerMilkyway.class, layers.get(1).getType().getClass());
		assertEquals(LayerSolarSystem.class, layers.get(2).getType().getClass());
		assertEquals(LayerDeepSky.class, layers.get(3).getType().getClass());
	}

	private static final class TestStellarSky extends StellarSky {
		@Override
		public Logger getLogger() {
			return LogManager.getLogger(StellarLayerRegistryBuildProfileTest.class);
		}
	}
}
