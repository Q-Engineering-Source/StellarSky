package stellarium;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import stellarium.stellars.StellarManager;
import stellarium.stellars.layer.CelestialManager;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldLighting;
import stellarium.world.ring.RingworldClockClientState;

public class StellarForgeEventHook {
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        RingworldLighting.publish(event.getWorld(), null);
        if (event.getWorld().isRemote) {
            StellarScene scene = StellarScene.getScene(event.getWorld());
            if (scene != null) {
                scene.clearRingworldClockSample(event.getWorld());
            }
            RingworldClockClientState.onClientWorldUnloaded(event.getWorld());
        }
    }

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void preAttachCapabilities(AttachCapabilitiesEvent<World> event) {
		World world = event.getObject();
		// Check if it's initial
		if(!world.isRemote && world.provider.getDimension() != 0)
			return;

		// Now setup StellarManager here
		StellarManager manager = StellarManager.loadOrCreateManager(world);
		if(!world.isRemote) {
			manager.setup(new CelestialManager(false));
		} else {
			// Client fallback must never erase the server's configured day/year.
			manager.handleServerWithoutMod();
			if(manager.getCelestialManager() == null)
				manager.setup(StellarSky.PROXY.getClientCelestialManager().copyFromClient());
		}
	}

	@SubscribeEvent
	public void onSyncConfig(ConfigChangedEvent.OnConfigChangedEvent event) {
		if(StellarSkyReferences.MODID.equals(event.getModID()))
			StellarSky.INSTANCE.getCelestialConfigManager().syncFromGUI();
	}

	@SubscribeEvent
	public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
		if(!event.player.world.isRemote) {
			StellarSky.INSTANCE.getNetworkManager().sendTimeStates(
					(net.minecraft.entity.player.EntityPlayerMP) event.player,
					StellarManager.getManager(event.player.getServer().getEntityWorld()));
			StellarSky.INSTANCE.getNetworkManager().sendObserverContext(
					(net.minecraft.entity.player.EntityPlayerMP) event.player,
					StellarManager.getManager(event.player.getServer().getEntityWorld()), true);
		}
	}

	@SubscribeEvent
	public void onPlayerChangedDimension(PlayerChangedDimensionEvent event) {
		if(!event.player.world.isRemote) {
			MinecraftServer server = event.player.getServer();
			StellarManager manager = StellarManager.getManager(server.getEntityWorld());
			int dimension = event.toDim;
			World targetWorld = server.getWorld(dimension);
			if(targetWorld == null)
				throw new IllegalStateException("Missing target world for dimension " + dimension);
			StellarSky.INSTANCE.getNetworkManager().sendTimeState(dimension,
					manager.getTimeMultiplier(dimension), manager.isSystemTimeSyncEnabled(dimension));
			StellarSky.INSTANCE.getNetworkManager().sendObserverContext(
					(net.minecraft.entity.player.EntityPlayerMP) event.player,
					targetWorld, manager, true);
		}
	}

	@SubscribeEvent
	public void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if(event.phase != TickEvent.Phase.END || event.player.world.isRemote)
			return;
		net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) event.player;
		if(event.player.ticksExisted % 20 != 0)
			return;
		StellarSky.INSTANCE.getNetworkManager().sendObserverContext(
				player,
				StellarManager.getManager(event.player.getServer().getEntityWorld()), false);
	}

	@SubscribeEvent
	public void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
		if(!event.player.world.isRemote) {
			StellarSky.INSTANCE.getNetworkManager().forgetObserver(
					(net.minecraft.entity.player.EntityPlayerMP) event.player);
		}
	}

}
