package stellarium.world;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import stellarapi.api.SAPIReferences;
import stellarapi.api.celestials.CelestialCollection;
import stellarapi.api.celestials.CelestialObject;
import stellarapi.api.celestials.IEffectorType;
import stellarapi.api.lib.config.INBTConfig;
import stellarapi.api.pack.ICelestialScene;
import stellarapi.api.render.IAdaptiveRenderer;
import stellarapi.api.view.IAtmosphereEffect;
import stellarapi.api.view.ICCoordinates;
import stellarapi.api.world.ICelestialHelper;
import stellarapi.api.world.worldset.WorldSet;
import stellarapi.example.CelestialHelperSimple;
import stellarium.StellarSky;
import stellarium.stellars.StellarManager;
import stellarium.stellars.runtime.WorldCelestialRuntime;
import stellarium.time.StellarSkyTime;
import stellarium.world.ring.RingworldCelestialHelper;
import stellarium.world.ring.RingworldClockClientState;
import stellarium.world.ring.RingworldClockMirror;
import stellarium.world.ring.RingworldClockPublisher;
import stellarium.world.ring.RingworldClockSample;
import stellarium.world.ring.RingworldClockContinuityTracker;
import stellarium.world.ring.RingworldAirProfile;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldThinAtmosphere;
import stellarium.world.ring.RingworldRuntimeGeneration;
import stellarium.world.ring.RingworldWorldTimeMutationAccess;
import stellarium.world.ring.RingworldLightFrame;
import stellarium.world.ring.RingworldLighting;
import stellarium.world.ring.RingworldSunshade;

public final class StellarScene implements ICelestialScene {
	private final StellarManager committedManager;
	private StellarManager manager;
	private final World world;
	private final WorldSet worldSet;
	private final WorldCelestialRuntime celestialRuntime;

	private PerDimensionSettings settings;
	private IStellarSkySet skyset;
	private StellarCoordinates coordinate;
	private double configuredLatitude;
	private double configuredLongitude;
    private RingworldSunshade ringworldSunshade;
    private final RingworldClockPublisher ringworldClockPublisher;
    private UUID ringworldRuntimeGeneration;
    private final RingworldClockMirror ringworldClockMirror = new RingworldClockMirror();
    private final RingworldClockContinuityTracker ringworldClockContinuity = new RingworldClockContinuityTracker();

	@Deprecated
	public static StellarScene getScene(World world) {
		ICelestialScene scene = SAPIReferences.getActivePack(world);
		return (scene instanceof StellarScene)? (StellarScene) scene : null;
	}

	public StellarScene(World world, WorldSet worldSet, PerDimensionSettings settings) {
		this.world = world;
		this.worldSet = worldSet;
		this.committedManager = StellarManager.getManager(world);
		this.manager = world.isRemote ? committedManager.createClientCandidate() : committedManager;
		this.celestialRuntime = new WorldCelestialRuntime(world, this.manager);
		this.ringworldClockPublisher = world.isRemote ? null : new RingworldClockPublisher(UUID.randomUUID());
		this.settings = settings;
	}

	public PerDimensionSettings getSettings() {
		return this.settings;
	}

	public void setDynamicLocation(double latitude, double longitude) {
		if(this.coordinate != null)
			this.coordinate.setLocationDegrees(latitude, longitude);
	}

	public void clearDynamicLocation() {
		if(this.coordinate != null)
			this.coordinate.setLocationDegrees(this.configuredLatitude, this.configuredLongitude);
	}

	private void loadSettingsFromConfig() {
		this.settings = (PerDimensionSettings) ((INBTConfig) StellarSky.PROXY.getDimensionSettings().getSubConfig(worldSet.name)).copy();
	}


	@Override
	public NBTTagCompound serializeNBT() {
		NBTTagCompound nbt = new NBTTagCompound();

		// Writes Stellar Manager.
		// TODO Stellar API Separate networking code and serialization code
		nbt.setTag("main", manager.serializeNBT());
		settings.writeToNBT(nbt);
		if (settings.getRingworldSettings().sunshade() != null) {
			if (ringworldClockPublisher != null) {
				RingworldRuntimeGeneration.write(nbt, ringworldClockPublisher.generation());
			}
		}
		return nbt;
	}

	@Override
	public void deserializeNBT(NBTTagCompound nbt) {
		// When it's the default world and there's the manager nbt, read it.
		if(world.provider.getDimension() == 0 || world.isRemote) {
			if(nbt.hasKey("main", 10)) {
				manager.syncFromNBT(nbt.getCompoundTag("main"), world.isRemote);
			}
		}

		if(manager.isLocked() || world.isRemote) {
			this.settings = new PerDimensionSettings(this.worldSet);
			settings.readFromNBT(nbt);
		} else {
			this.loadSettingsFromConfig();
		}
		if (world.isRemote) {
			this.ringworldRuntimeGeneration = settings.getRingworldSettings().sunshade() == null
					? null : RingworldRuntimeGeneration.readClient(nbt);
			ringworldClockMirror.clear();
		}
	}

	public List<CelestialObject> getSuns() {
		return this.celestialRuntime.getSuns();
	}

	public List<CelestialObject> getMoons() {
		return this.celestialRuntime.getMoons();
	}

    public RingworldClockSample getRingworldClockSample() {
        return world.isRemote && StellarSky.PROXY.getDefWorld() == world && StellarScene.getScene(world) == this
                ? ringworldClockMirror.currentSampleFor(world, this) : null;
    }

    public RingworldDisplaySnapshot getRingworldDisplaySnapshot(RingworldRenderObserver observer) {
        if (!world.isRemote || StellarSky.PROXY.getDefWorld() != world || StellarScene.getScene(world) != this
                || ringworldSunshade == null) {
            return null;
        }
        RingworldClockMirror.DisplayTime displayTime = ringworldClockMirror.displayTimeFor(world, this);
        RingworldAirProfile airProfile = settings.getRingworldSettings().atmosphereProfile();
        return new RingworldDisplaySnapshot(world, this, displayTime, ringworldSunshade,
                displayTime == null ? null : ringworldSunshade.phase(displayTime.previous().worldTime(), displayTime.current().worldTime(),
                        displayTime.fraction()), settings.getRingworldSettings().sunshadeHeightBlocks(),
                settings.getRingworldSettings().sunshadeThicknessBlocks(), observer,
                RingworldThinAtmosphere.fadeAt(observer.y(), observer.z(), airProfile),
                AtmosphereGeometry.resolveHeight(world, observer.y(), settings), airProfile);
    }

    public boolean acceptRingworldClockSample(RingworldClockSample sample, RingworldClockClientState.Receipt receipt) {
        if (!world.isRemote || StellarSky.PROXY.getDefWorld() != world || StellarScene.getScene(world) != this
                || ringworldRuntimeGeneration == null
                || ringworldSunshade == null) {
            return false;
        }
        return ringworldClockMirror.acceptForCommittedScene(world.provider.getDimension(),
                ringworldRuntimeGeneration, sample, world, this, receipt);
    }

    public void clearRingworldClockSample(World candidateWorld) {
        if (candidateWorld == world) {
            ringworldClockMirror.clear();
        }
    }

    /** Applies exactly one known cadence write without changing its calculated value. */
    public long applyKnownWorldTimeUpdate(World targetWorld, long calculationBase) {
        if (targetWorld != world || targetWorld.isRemote || StellarScene.getScene(targetWorld) != this) {
            throw new IllegalStateException("Ringworld time provenance requires the active server scene world");
        }
        if (ringworldSunshade == null) {
            long next = StellarSkyTime.nextWorldTime(targetWorld, calculationBase);
            targetWorld.setWorldTime(next);
            return next;
        }
        RingworldClockContinuityTracker.Observation before = observeWorldTime(targetWorld);
        RingworldClockContinuityTracker.KnownWrite write = ringworldClockContinuity.beginKnownWrite(before);
        boolean closed = false;
        try {
            StellarSkyTime.WorldTimeUpdate update = StellarSkyTime.calculateNextWorldTime(targetWorld, calculationBase);
            targetWorld.setWorldTime(update.worldTime());
            ringworldClockContinuity.finishKnownWrite(write, update.worldTime(), observeWorldTime(targetWorld),
                    update.arithmeticWrapped() || !update.normalCadence() || calculationBase != before.worldTime());
            closed = true;
            return update.worldTime();
        } finally {
            if (!closed) {
                ringworldClockContinuity.abortKnownWrite(write);
            }
        }
    }

	public void update(World world, long currentTick, long currentUniversalTick) {
		coordinate.setSystemTimeModel(manager.getSettings(), StellarSkyTime.isSystemTimeSyncEnabled(world),
				StellarSkyTime.getSystemTimeOffsetMinutes(world));
		double astronomicalYear = getAstronomicalYear(currentTick);
		celestialRuntime.update(world, astronomicalYear);
		coordinate.update(astronomicalYear);
        if (!world.isRemote && StellarScene.getScene(world) == this) {
            RingworldLightFrame frame = ringworldSunshade == null ? null
                    : new RingworldLightFrame(ringworldSunshade,
                            settings.getRingworldSettings().sunshadeHeightBlocks(),
                            settings.getRingworldSettings().sunshadeThicknessBlocks(), currentTick);
            RingworldLighting.publish(world, frame);
            if (frame != null) {
                RingworldClockContinuityTracker.Publication publication =
                        ringworldClockContinuity.previewPublication(observeWorldTime(world));
                boolean frameMatchesObservedTime = frame.worldTime() == publication.observation().worldTime();
                RingworldClockSample sample = ringworldClockPublisher.publish(world.provider.getDimension(),
                        frame.worldTime(), publication.discontinuousBefore() || !frameMatchesObservedTime);
                if (frameMatchesObservedTime)
                    ringworldClockContinuity.commitPublication(publication);
                else
                    ringworldClockContinuity.markUnproven();
                StellarSky.INSTANCE.getNetworkManager().sendRingworldClock(world, this, sample);
            }
        }
	}


	@Override
	public void prepare() {
        this.ringworldSunshade = settings.getRingworldSettings().sunshade();
        if (ringworldSunshade != null && (!settings.doesPatchProvider() || !world.provider.hasSkyLight())) {
            throw new IllegalArgumentException("Ringworld requires Patch_Provider and a world with skylight");
        }
		String dimName = world.provider.getDimensionType().getName();
		StellarSky.INSTANCE.getLogger().info(String.format("Initializing Dimension Settings on Dimension %s...", dimName));
		if(settings.allowRefraction())
			this.skyset = new RefractiveSkySet(this.settings);
		else this.skyset = new NonRefractiveSkySet(this.settings);
		this.configuredLatitude = this.settings.latitude;
		this.configuredLongitude = this.settings.longitude;
		this.coordinate = new StellarCoordinates(manager.getSettings(), this.settings,
				StellarSkyTime.isSystemTimeSyncEnabled(world));
		StellarManager.TimeState timeState = manager.getTimeStates().get(world.provider.getDimension());
		if(timeState != null && timeState.hasLocationOverride())
			this.coordinate.setLocationDegrees(timeState.getLatitude(), timeState.getLongitude());
		if(world.isRemote)
			ObserverSkyState.applyClientContext(world, this.coordinate);
		coordinate.setSystemTimeModel(manager.getSettings(), StellarSkyTime.isSystemTimeSyncEnabled(world),
				StellarSkyTime.getSystemTimeOffsetMinutes(world));
		coordinate.update(0.0);

		StellarSky.INSTANCE.getLogger().info(String.format("Initialized Dimension Settings on Dimension %s.", dimName));


		StellarSky.INSTANCE.getLogger().info("Evaluating Stellar Collections from Celestial State...");

		StellarSky.INSTANCE.getLogger().info("Preparing world-owned celestial runtime.");
		celestialRuntime.prepare();
		StellarSky.INSTANCE.getLogger().info("Prepared world-owned celestial runtime.");

		double currentYear = getAstronomicalYear(world.getWorldTime());
		celestialRuntime.update(world, currentYear);
		coordinate.update(currentYear);

		StellarSky.INSTANCE.getLogger().info("Evaluated Stellar Collections.");
	}

	@Override
	public void onRegisterCollection(Consumer<CelestialCollection> colRegistry,
			BiConsumer<IEffectorType, CelestialObject> effRegistry) {
		celestialRuntime.registerCollections(colRegistry, effRegistry);
	}

	@Override
	public ICCoordinates createCoordinates() {
		return this.coordinate;
	}

	@Override
	public IAtmosphereEffect createAtmosphereEffect() {
		return this.skyset;
	}

	@Override
	public ICelestialHelper createCelestialHelper() {
		if(this.getSettings().doesPatchProvider()) {
            if (ringworldSunshade != null) {
                return new RingworldCelestialHelper((float) settings.getSunlightMultiplier(),
                        getSuns().get(0), getMoons().get(0), coordinate, skyset);
            }
			return new CelestialHelperSimple((float)this.getSettings().getSunlightMultiplier(), 1.0f,
					this.getSuns().get(0), this.getMoons().get(0), this.coordinate, this.skyset);
		} else return null;
	}

	@Override
	public IAdaptiveRenderer createSkyRenderer() {
		// CelestialPackManager calls this only after the candidate scene has been
		// committed. Publishing the client model from prepare() would let a later
		// pack-load failure leave the renderer bound to an uncommitted scene.
		if(world.isRemote) {
            if (StellarScene.getScene(world) != this)
                throw new IllegalStateException("Cannot publish an uncommitted client celestial scene");
            if (manager != committedManager) {
                committedManager.adoptPreparedClientState(world, manager, celestialRuntime.getPreparedGraph());
                manager = committedManager;
                // A failing display callback must not leave this committed scene
                // reading detached metadata while its graph reads the world manager.
                StellarSky.PROXY.setupStellarLoad(committedManager);
            } else {
                committedManager.adoptPreparedClientGraph(world, celestialRuntime.getPreparedGraph());
            }
            // The client waits for a server clock sample for this committed scene.
            // Never carry the previous scene's shade across reload/dimension changes.
            RingworldLighting.publish(world, null);
			RingworldClockClientState.onClientSceneCommitted(world, this);
			ringworldClockMirror.clear();
			if (ringworldSunshade != null && ringworldRuntimeGeneration == null) {
				StellarSky.INSTANCE.getLogger().warn("Ringworld scene has no server runtime generation; clock authority is unavailable");
			}
			StellarSky.PROXY.setupDimensionLoad(this);
        }
		return StellarSky.PROXY.setupSkyRenderer(this.world, this.worldSet, settings.getSkyRendererType());
	}

    private double getAstronomicalYear(long worldTime) {
        // A remote candidate must not evaluate its graph using the previous
        // committed world's day/year. System-time remains the existing S2C mirror.
        if (world.isRemote && !StellarSkyTime.isSystemTimeSyncEnabled(world)) {
            return manager.getSkyYear(worldTime);
        }
        return StellarSkyTime.getAstronomicalYear(world, worldTime);
    }

    private static RingworldClockContinuityTracker.Observation observeWorldTime(World world) {
        if (world.getWorldInfo() instanceof RingworldWorldTimeMutationAccess access) {
            return new RingworldClockContinuityTracker.Observation(world.getWorldTime(),
                    access.stellarium$getWorldTimeMutationRevision(),
                    access.stellarium$isWorldTimeMutationRevisionSaturated());
        }
        return new RingworldClockContinuityTracker.Observation(world.getWorldTime(), 0L, true);
    }
}
