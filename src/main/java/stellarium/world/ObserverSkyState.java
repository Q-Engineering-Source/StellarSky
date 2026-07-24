package stellarium.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.World;
import stellarium.api.observer.ObserverSkyContext;

/**
 * Client-side contexts keyed by dimension. Each client receives only its own
 * resolved observer context.
 */
public final class ObserverSkyState {
	private static final Map<Integer, ObserverSkyContext> clientContexts = new ConcurrentHashMap<>();

	private ObserverSkyState() {
	}

	public static void setClientContext(ObserverSkyContext context) {
		clientContexts.put(context.getDimension(), context);
	}

	public static ObserverSkyContext getClientContext(World world) {
		return clientContexts.get(world.provider.getDimension());
	}

	public static void applyClientContext(World world, StellarCoordinates coordinates) {
		ObserverSkyContext context = getClientContext(world);
		if(context != null)
			coordinates.setLocationDegrees(context.getLatitude(), context.getLongitude());
	}
}
