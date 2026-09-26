package stellarium;

import java.io.IOException;
import java.io.File;
import java.util.Objects;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import stellarium.world.ring.terrain.ServerPreviewRuntime;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;
import stellarapi.api.SAPIReferences;
import stellarapi.api.lib.config.ConfigManager;
import stellarapi.api.world.worldset.WorldSets;
import stellarium.api.SkyRenderTypeSurface;
import stellarium.api.SkySetTypeDefault;
import stellarium.api.StellarSkyAPI;
import stellarium.command.CommandLock;
import stellarium.command.CommandAstronomicalTime;
import stellarium.command.CommandStellarTime;
import stellarium.command.CommandTerrainPreviewDiagnostics;
import stellarium.render.adapt.SkyRenderTypeEnd;
import stellarium.render.adapt.SkySetTypeEnd;
import stellarium.sync.StellarNetworkManager;
import stellarium.world.StellarPack;
import stellarium.world.provider.EndReplacer;
import stellarium.world.ring.generation.RingworldGenerationConfig;
import stellarium.world.ring.terrain.ServerPreviewTransport;

@Mod(modid=StellarSkyReferences.MODID, version=StellarSkyReferences.VERSION,
acceptedMinecraftVersions="[1.12.0, 1.13.0)",
dependencies="required-after:stellarapi@[1.12.2-0.5.2.1, 1.12.2-0.5.3.0);required-after-client:actinium@[alpha-0.0.8]", guiFactory="stellarium.client.config.StellarConfigGuiFactory")
public class StellarSky {
	// The instance of Stellar Sky
	@Mod.Instance(StellarSkyReferences.MODID)
	public static StellarSky INSTANCE;

	@SidedProxy(clientSide="stellarium.ClientProxy", serverSide="stellarium.CommonProxy")
	public static IProxy PROXY;


	private Logger logger;
	private ConfigManager celestialConfigManager;
	private StellarForgeEventHook eventHook = new StellarForgeEventHook();
	private StellarTickHandler tickHandler = new StellarTickHandler();
	private StellarNetworkManager networkManager;
    private RingworldGenerationConfig generationConfig;

    public RingworldGenerationConfig getGenerationConfig() {
        return Objects.requireNonNull(generationConfig, "Generation config not initialized");
    }

	public Logger getLogger() {
		return this.logger;
	}

	public StellarNetworkManager getNetworkManager() {
		return this.networkManager;
	}

	public ConfigManager getCelestialConfigManager() {
		return this.celestialConfigManager;
	}

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) { 
		this.logger = event.getModLog();
        this.generationConfig = RingworldGenerationConfig.load(new File(
                new File(event.getModConfigurationDirectory(), StellarSkyReferences.MODID), "world-generation.cfg"));

		this.celestialConfigManager = new ConfigManager(
				StellarSkyReferences.getConfiguration(event.getModConfigurationDirectory(),
						StellarSkyReferences.CELESTIAL_SETTINGS));


		PROXY.setupCelestialConfigManager(this.celestialConfigManager);
		PROXY.preInit(event);

		this.networkManager = new StellarNetworkManager();

		MinecraftForge.EVENT_BUS.register(this.eventHook);
		MinecraftForge.EVENT_BUS.register(this.tickHandler);
        MinecraftForge.EVENT_BUS.register(ServerPreviewRuntime.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ServerPreviewTransport.INSTANCE);

		StellarSkyResources.init();

		SAPIReferences.registerPack(StellarPack.INSTANCE);
	}

	@SuppressWarnings("deprecation")
	@Mod.EventHandler
	public void load(FMLInitializationEvent event) throws IOException {
		PROXY.load(event);

		// TODO Remove this when it's not needed
		SAPIReferences.registerWorldProviderReplacer(new EndReplacer());

		StellarSkyAPI.registerSkyType(WorldSets.exactOverworld(), SkySetTypeDefault.INSTANCE);
		StellarSkyAPI.registerSkyType(WorldSets.overworldType(), SkySetTypeDefault.INSTANCE);
		StellarSkyAPI.registerSkyType(WorldSets.endType(), SkySetTypeEnd.INSTANCE);

		StellarSkyAPI.registerDefaultRenderer(WorldSets.exactOverworld(), SkyRenderTypeSurface.INSTANCE);
		StellarSkyAPI.registerDefaultRenderer(WorldSets.overworldType(), SkyRenderTypeSurface.INSTANCE);
		StellarSkyAPI.registerDefaultRenderer(WorldSets.endType(), SkyRenderTypeEnd.INSTANCE);

		StellarSkyAPI.registerRendererType(SkyRenderTypeSurface.INSTANCE);
		StellarSkyAPI.registerRendererType(SkyRenderTypeEnd.INSTANCE);
	}

	@Mod.EventHandler
	public void postInit(FMLPostInitializationEvent event) {
		celestialConfigManager.syncFromFile();
		PROXY.postInit(event);
	}

	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent event) {
        ServerPreviewRuntime.INSTANCE.start(event.getServer());
        ServerPreviewTransport.INSTANCE.start();
		event.registerServerCommand(new CommandLock());
		event.registerServerCommand(new CommandStellarTime());
		// StellarAPI registers its tick-based replacement first. Register this
		// afterwards so /time uses the same civil-time model as /stellartime.
		event.registerServerCommand(new CommandAstronomicalTime());
		event.registerServerCommand(new CommandTerrainPreviewDiagnostics());
	}

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ServerPreviewTransport.INSTANCE.stop();
        ServerPreviewRuntime.INSTANCE.stop();
    }


	public boolean existOnServer() {
		return this.existOnServer;
	}

	private boolean existOnServer = true;

	@NetworkCheckHandler
	public boolean checkNetwork(Map<String, String> modsNversions, Side from) {
		boolean remoteInstalled = modsNversions.containsKey(StellarSkyReferences.MODID);
		if(from.isServer())
			this.existOnServer = remoteInstalled;
		// Scene NBT is part of the wire contract. Both installed copies must
		// understand the same schema; retain the existing modless-server fallback.
		return !remoteInstalled
				|| StellarSkyReferences.VERSION.equals(modsNversions.get(StellarSkyReferences.MODID));
	}
}
