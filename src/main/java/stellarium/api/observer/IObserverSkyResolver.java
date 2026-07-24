package stellarium.api.observer;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

/**
 * Resolves a per-observer sky context. Return the fallback unchanged when the
 * resolver does not own the current dimension or observer.
 */
public interface IObserverSkyResolver {
	ObserverSkyContext resolve(World world, Entity observer, ObserverSkyContext fallback);
}
