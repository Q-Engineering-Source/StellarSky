package stellarium.stellars.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.world.World;
import stellarium.StellarSky;
import stellarapi.api.celestials.CelestialCollection;
import stellarapi.api.celestials.CelestialObject;
import stellarapi.api.celestials.IEffectorType;
import stellarium.stellars.StellarManager;
import stellarium.stellars.layer.CelestialManager;
import stellarium.stellars.layer.StellarCollection;
import stellarium.stellars.layer.StellarLayer;

/**
 * Owns the mutable celestial graph for one loaded {@link World} instance.
 *
 * <p>The saved {@link StellarManager} remains the authority for settings and time state.
 * Dedicated-server scenes construct their own graph, so evaluating one world's solar
 * objects cannot overwrite the graph registered by another world. Client scenes copy
 * the base catalogue and prepare their own graph before publishing its render models.</p>
 */
public final class WorldCelestialRuntime {
	private final World owner;
	private final StellarManager timeAuthority;
	private final CelestialManager celestialManager;
	private final List<CelestialObject> suns = new ArrayList<>();
	private final List<CelestialObject> moons = new ArrayList<>();

	private Lifecycle lifecycle = Lifecycle.NEW;

	public WorldCelestialRuntime(World owner, StellarManager timeAuthority) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.timeAuthority = Objects.requireNonNull(timeAuthority, "timeAuthority");
		if(owner.isRemote) {
			this.celestialManager = Objects.requireNonNull(StellarSky.PROXY.getClientCelestialManager(),
					"Client catalogue must be initialized before its scene runtime").copyFromClient();
		} else {
			this.celestialManager = new CelestialManager(false);
		}
	}

	/**
	 * Initializes and snapshots this runtime's graph exactly once. This must run before
	 * collection registration or any update.
	 */
	public void prepare() {
		requireLifecycle(Lifecycle.NEW, "prepare");
		celestialManager.initializeCommon(timeAuthority,
				Objects.requireNonNull(timeAuthority.getSettings(), "Server settings must be loaded before runtime preparation"));

		updateGraph(0.0);
		suns.clear();
		moons.clear();
		for(StellarCollection collection : celestialManager.getLayers()) {
			StellarLayer layer = collection.getType();
			layer.initialUpdate(collection);
			suns.addAll(layer.getSuns(collection));
			moons.addAll(layer.getMoons(collection));
		}
		lifecycle = Lifecycle.PREPARED;
	}

	/** Evaluates only the celestial objects owned by this world's runtime. */
	public void update(World world, double astronomicalYear) {
		verifyOwner(world);
		requireLifecycle(Lifecycle.PREPARED, "update");
		updateGraph(astronomicalYear);
	}

	public void registerCollections(Consumer<CelestialCollection> collectionRegistry,
			BiConsumer<IEffectorType, CelestialObject> effectorRegistry) {
		Objects.requireNonNull(collectionRegistry, "collectionRegistry");
		Objects.requireNonNull(effectorRegistry, "effectorRegistry");
		requireLifecycle(Lifecycle.PREPARED, "registerCollections");
		for(CelestialCollection collection : celestialManager.getLayers())
			collectionRegistry.accept(collection);
		for(CelestialObject sun : suns)
			effectorRegistry.accept(IEffectorType.Light, sun);
		for(CelestialObject moon : moons)
			effectorRegistry.accept(IEffectorType.Tide, moon);
	}

	public List<CelestialObject> getSuns() {
		requireLifecycle(Lifecycle.PREPARED, "getSuns");
		return Collections.unmodifiableList(suns);
	}

	public List<CelestialObject> getMoons() {
		requireLifecycle(Lifecycle.PREPARED, "getMoons");
		return Collections.unmodifiableList(moons);
	}

	/** Only a committed scene may publish this already-initialized graph to its client models. */
	public CelestialManager getPreparedGraph() {
		requireLifecycle(Lifecycle.PREPARED, "getPreparedGraph");
		return this.celestialManager;
	}

	private void updateGraph(double astronomicalYear) {
		celestialManager.update(astronomicalYear);
	}

	private void verifyOwner(World world) {
		if(owner != world)
			throw new IllegalArgumentException("Celestial runtime was updated for a different World instance");
	}

	private void requireLifecycle(Lifecycle required, String operation) {
		if(lifecycle != required)
			throw new IllegalStateException("Cannot " + operation + " a world celestial runtime in state " + lifecycle);
	}

	private enum Lifecycle {
		NEW,
		PREPARED
	}
}
