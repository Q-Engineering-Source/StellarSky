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
import stellarium.stellars.StellarManager;
import stellarium.time.StellarSkyTime;
import stellarium.world.StellarScene;

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
				StellarSkyTime.getServerTimeOffsetMinutes(),
				state == null ? 60 : state.getSystemTimeSyncIntervalSeconds()));
	}

	public void sendTimeStates(EntityPlayerMP player, StellarManager manager) {
		for(java.util.Map.Entry<Integer, StellarManager.TimeState> entry : manager.getTimeStates().entrySet()) {
			StellarManager.TimeState state = entry.getValue();
			wrapper.sendTo(new MessageTimeMultiplierSync(entry.getKey(), state.getMultiplier(),
					state.isSystemTimeSync(), StellarSkyTime.getServerTimeOffsetMinutes(),
					state.getSystemTimeSyncIntervalSeconds()), player);
		}
		int currentDimension = player.world.provider.getDimension();
		if(!manager.getTimeStates().containsKey(currentDimension)) {
			wrapper.sendTo(new MessageTimeMultiplierSync(currentDimension,
					manager.getTimeMultiplier(currentDimension),
					manager.isSystemTimeSyncEnabled(currentDimension),
					StellarSkyTime.getServerTimeOffsetMinutes(),
					manager.getSystemTimeSyncIntervalSeconds(currentDimension)), player);
		}
	}

	public void sendObserverContext(EntityPlayerMP player, StellarManager manager, boolean force) {
		ObserverSkyContext context = ObserverSkyResolvers.resolve(player.world, player,
				getDimensionDefaultContext(player.world, manager));
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

	private static ObserverSkyContext getDimensionDefaultContext(World world, StellarManager manager) {
		int dimension = world.provider.getDimension();
		double latitude = 0.0;
		double longitude = 0.0;
		StellarManager.TimeState state = manager.getTimeStates().get(dimension);
		if(state != null && state.hasLocationOverride()) {
			latitude = state.getLatitude();
			longitude = state.getLongitude();
		} else {
			StellarScene scene = StellarScene.getScene(world);
			if(scene != null) {
				latitude = scene.getSettings().latitude;
				longitude = scene.getSettings().longitude;
			}
		}
		return ObserverSkyContext.dimensionDefault(dimension, latitude, longitude);
	}
}
