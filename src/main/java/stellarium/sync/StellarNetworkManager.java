package stellarium.sync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import stellarium.api.observer.ObserverSkyContext;
import stellarium.api.observer.ObserverSkyResolvers;
import stellarium.StellarSky;
import stellarium.stellars.StellarManager;
import stellarium.time.StellarSkyTime;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldClockSample;

public final class StellarNetworkManager {
	
	private SimpleNetworkWrapper wrapper;
	private String id = "stellarskychannel";
	private final Map<UUID, ObserverSkyContext> lastObserverContexts = new ConcurrentHashMap<>();
	
	public StellarNetworkManager() {
		this.wrapper = NetworkRegistry.INSTANCE.newSimpleChannel(this.id);

		wrapper.registerMessage(MessageLockSync.MessageLockSyncHandler.class,
				MessageLockSync.class, 0, Side.CLIENT);
		wrapper.registerMessage(MessageTimeMultiplierSync.MessageTimeMultiplierSyncHandler.class,
				MessageTimeMultiplierSync.class, 1, Side.CLIENT);
		wrapper.registerMessage(MessageObserverSkySync.Handler.class,
				MessageObserverSkySync.class, 2, Side.CLIENT);
		wrapper.registerMessage(MessageRingworldClockSync.Handler.class,
				MessageRingworldClockSync.class, 3, Side.CLIENT);
	}

	public String getID() {
		return this.id;
	}

	public void sendLockInformation(boolean lock) {
		wrapper.sendToAll(new MessageLockSync(lock));
	}

	public void sendTimeState(int dimension, double multiplier, boolean systemTimeSync) {
		StellarManager.TimeState state = StellarManager.getManager(
				net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld())
				.getTimeStates().get(dimension);
		wrapper.sendToAll(new MessageTimeMultiplierSync(dimension, multiplier, systemTimeSync,
				StellarManager.getManager(net.minecraftforge.fml.common.FMLCommonHandler.instance()
						.getMinecraftServerInstance().getEntityWorld())
						.getSystemTimeZoneOffsetMinutes(dimension),
				state == null ? 60 : state.getSystemTimeSyncIntervalSeconds()));
	}

	public void sendTimeStates(EntityPlayerMP player, StellarManager manager) {
		for(java.util.Map.Entry<Integer, StellarManager.TimeState> entry : manager.getTimeStates().entrySet()) {
			StellarManager.TimeState state = entry.getValue();
			wrapper.sendTo(new MessageTimeMultiplierSync(entry.getKey(), state.getMultiplier(),
					state.isSystemTimeSync(), manager.getSystemTimeZoneOffsetMinutes(entry.getKey()),
					state.getSystemTimeSyncIntervalSeconds()), player);
		}
		int currentDimension = player.world.provider.getDimension();
		if(!manager.getTimeStates().containsKey(currentDimension)) {
			wrapper.sendTo(new MessageTimeMultiplierSync(currentDimension,
					manager.getTimeMultiplier(currentDimension),
					manager.isSystemTimeSyncEnabled(currentDimension),
					manager.getSystemTimeZoneOffsetMinutes(currentDimension),
					manager.getSystemTimeSyncIntervalSeconds(currentDimension)), player);
		}
	}

	public void sendObserverContext(EntityPlayerMP player, StellarManager manager, boolean force) {
		sendObserverContext(player, player.world, manager, force);
	}

	public void sendObserverContext(EntityPlayerMP player, World contextWorld,
			StellarManager manager, boolean force) {
		ObserverSkyContext context = ObserverSkyResolvers.resolve(contextWorld, player,
				getDimensionDefaultContext(contextWorld, manager));
		UUID playerId = player.getUniqueID();
		ObserverSkyContext previous = this.lastObserverContexts.get(playerId);
		if(force || !context.equals(previous)) {
			this.lastObserverContexts.put(playerId, context);
			wrapper.sendTo(new MessageObserverSkySync(context), player);
		}
	}

	public void sendObserverContextsInDimension(int dimension, StellarManager manager,
			net.minecraft.server.MinecraftServer server) {
		for(EntityPlayerMP player : server.getPlayerList().getPlayers()) {
			if(player.world.provider.getDimension() == dimension)
				sendObserverContext(player, manager, true);
		}
	}

	public void forgetObserver(EntityPlayerMP player) {
		this.lastObserverContexts.remove(player.getUniqueID());
	}

	public void sendRingworldClock(World world, StellarScene scene, RingworldClockSample sample) {
		if (world.isRemote || StellarScene.getScene(world) != scene
				|| world.provider.getDimension() != sample.dimension()) {
			return;
		}
		boolean sent = false;
		for (EntityPlayerMP player : world.getMinecraftServer().getPlayerList().getPlayers()) {
			if (player.world == world) {
				wrapper.sendTo(new MessageRingworldClockSync(sample), player);
				sent = true;
			}
		}
		if (sent && sample.sequence() == 1L && StellarSky.INSTANCE.getLogger().isDebugEnabled()) {
			StellarSky.INSTANCE.getLogger().debug("Published ringworld clock dimension={} generation={} sequence={} worldTime={} discontinuousBefore={}",
					sample.dimension(), sample.generation(), sample.sequence(), sample.worldTime(), sample.discontinuousBefore());
		} else if (sent && StellarSky.INSTANCE.getLogger().isTraceEnabled()) {
			StellarSky.INSTANCE.getLogger().trace("Published ringworld clock dimension={} generation={} sequence={} worldTime={} discontinuousBefore={}",
					sample.dimension(), sample.generation(), sample.sequence(), sample.worldTime(), sample.discontinuousBefore());
		}
	}

	private static ObserverSkyContext getDimensionDefaultContext(World world, StellarManager manager) {
		int dimension = world.provider.getDimension();
		double latitude = 0.0;
		double longitude = 0.0;
		double altitude = 0.0;
		StellarManager.TimeState state = manager.getTimeStates().get(dimension);
		if(state != null && state.hasLocationOverride()) {
			latitude = state.getLatitude();
			longitude = state.getLongitude();
			altitude = state.getAltitude();
		} else {
			StellarScene scene = StellarScene.getScene(world);
			if(scene != null) {
				latitude = scene.getSettings().latitude;
				longitude = scene.getSettings().longitude;
			}
		}
		return ObserverSkyContext.dimensionDefault(dimension, latitude, longitude, altitude);
	}
}
