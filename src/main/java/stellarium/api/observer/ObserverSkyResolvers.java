package stellarium.api.observer;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/**
 * Public resolver registry for coordinate maps and space-mod integrations.
 * Higher priority resolvers run first and may transform the preceding result.
 */
public final class ObserverSkyResolvers {
	private static final List<Entry> resolvers = new CopyOnWriteArrayList<>();

	private ObserverSkyResolvers() {
	}

	public static void register(ResourceLocation id, int priority, IObserverSkyResolver resolver) {
		unregister(id);
		resolvers.add(new Entry(id, priority, resolver));
		resolvers.sort(Comparator.comparingInt(Entry::getPriority).reversed());
	}

	public static void unregister(ResourceLocation id) {
		resolvers.removeIf(entry -> entry.id.equals(id));
	}

	public static ObserverSkyContext resolve(World world, Entity observer, ObserverSkyContext fallback) {
		ObserverSkyContext result = fallback;
		for(Entry entry : resolvers) {
			ObserverSkyContext resolved = entry.resolver.resolve(world, observer, result);
			if(resolved != null)
				result = resolved;
		}
		return result;
	}

	private static final class Entry {
		private final ResourceLocation id;
		private final int priority;
		private final IObserverSkyResolver resolver;

		private Entry(ResourceLocation id, int priority, IObserverSkyResolver resolver) {
			this.id = id;
			this.priority = priority;
			this.resolver = resolver;
		}

		private int getPriority() {
			return this.priority;
		}
	}
}
