package stellarium.stellars;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import stellarapi.api.CelestialPeriod;
import stellarium.StellarSky;
import stellarium.common.ServerSettings;
import stellarium.stellars.layer.CelestialManager;
import stellarium.stellars.layer.StellarCollection;

public final class StellarManager extends WorldSavedData {
	// TODO Remove most of StellarManager with configuration. Or, what's day length?
	private static final String ID = "stellarskymanagerdata";

	private ServerSettings settings;
	private CelestialManager celestialManager;
	private boolean locked = false, setup = false;
	private final Map<Integer, TimeState> timeStates = new HashMap<>();
	private final StellarManager clientCandidateSource;
	
	public StellarManager(String id) {
		this(id, null);
	}

	private StellarManager(String id, StellarManager clientCandidateSource) {
		super(id);
		this.clientCandidateSource = clientCandidateSource;
	}

	public static @Nonnull StellarManager loadOrCreateManager(World world) {		
		WorldSavedData data = world.getMapStorage().getOrLoadData(StellarManager.class, ID);

		if(!(data instanceof StellarManager))
		{
			StellarManager manager = new StellarManager(ID);
			world.getMapStorage().setData(ID, manager);
			
			manager.loadSettingsFromConfig();
			
			data = manager;
		}

		return (StellarManager) data;
	}

	public static @Nonnull StellarManager getManager(World world) {
		WorldSavedData data = world.getMapStorage().getOrLoadData(StellarManager.class, ID);

		if(!(data instanceof StellarManager)) {
			throw new IllegalStateException(
					String.format("There is illegal data %s in storage!", data));
		}
		
		return (StellarManager)data;
	}

	
	private void loadSettingsFromConfig() {
		this.settings = (ServerSettings) StellarSky.PROXY.getServerSettings().copy();
		this.markDirty();
	}
	
	public void handleServerWithoutMod() {
		settings.setDefault();
	}

	@Override
	public void readFromNBT(NBTTagCompound compound) {
		this.syncFromNBT(compound, false);
	}

	public void syncFromNBT(NBTTagCompound compound, boolean isRemote) {
		this.locked = compound.getBoolean("locked");
		if(this.locked || isRemote)
		{
			this.settings = new ServerSettings();
			settings.readFromNBT(compound);
		} else {
			this.loadSettingsFromConfig();
		}
		this.timeStates.clear();
		if(compound.hasKey("DimensionTimeStates", 10)) {
			NBTTagCompound states = compound.getCompoundTag("DimensionTimeStates");
			for(String key : states.getKeySet()) {
				try {
					int dimension = Integer.parseInt(key);
					this.timeStates.put(dimension, TimeState.read(states.getCompoundTag(key),
							defaultMultiplier(), defaultSystemTimeSync(), defaultSystemTimeSyncInterval()));
				} catch(NumberFormatException ignored) {
					// Ignore malformed third-party or old save data.
				}
			}
		} else if(compound.hasKey("TimeMultiplier", 3)) {
			// Migration path for the first global time-control implementation.
			double multiplier = clampTimeMultiplier(compound.getInteger("TimeMultiplier"));
			double saved = compound.hasKey("SavedTimeMultiplier", 3)
					? clampTimeMultiplier(compound.getInteger("SavedTimeMultiplier")) : multiplier;
			this.timeStates.put(0, new TimeState(multiplier, saved, defaultSystemTimeSync(),
					defaultSystemTimeSyncInterval()));
		}
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound compound) {
		compound.setBoolean("locked", this.locked);
		NBTTagCompound states = new NBTTagCompound();
		for(Map.Entry<Integer, TimeState> entry : this.timeStates.entrySet())
			states.setTag(Integer.toString(entry.getKey()), entry.getValue().write());
		compound.setTag("DimensionTimeStates", states);
		settings.writeToNBT(compound);
		return compound;
	}


	public void setup(CelestialManager manager) {
		if(!this.setup) {
			StellarSky.INSTANCE.getLogger().info("Starting Common Initialization...");
			this.celestialManager = manager;
			StellarSky.PROXY.setupStellarLoad(this);
			manager.initializeCommon(this, this.settings);
			StellarSky.INSTANCE.getLogger().info("Common Initialization Ended.");
		}
		
		this.setup = true;
	}

	public ServerSettings getSettings() {
		return this.settings;
	}

	/** Scene-local metadata; never registered in the world's MapStorage. */
	public StellarManager createClientCandidate() {
		StellarManager candidate = new StellarManager(ID + "-client-candidate", this);
		candidate.syncFromNBT(this.serializeNBT(), true);
		return candidate;
	}

	/**
	 * Installs state after scene commit, without invoking display callbacks. The
	 * scene must switch its authority to this manager before binding client models.
	 */
	public void adoptPreparedClientState(World world, StellarManager candidate, CelestialManager prepared) {
		requirePreparedClientGraph(world, prepared);
		if (candidate == null || candidate.clientCandidateSource != this) {
			throw new IllegalArgumentException("Client metadata candidate belongs to another manager");
		}
		for (StellarCollection collection : prepared.getLayers()) {
			if (collection.getManager() != candidate) {
				throw new IllegalArgumentException("Prepared graph belongs to another client metadata candidate");
			}
		}
		// Decode the complete snapshot before touching committed metadata. Keep the
		// saved-data identity and its dirty bit; client adoption is not a server save.
		StellarManager snapshot = new StellarManager(ID + "-client-adoption");
		snapshot.syncFromNBT(candidate.serializeNBT(), true);
		this.settings = snapshot.settings;
		this.locked = snapshot.locked;
		this.timeStates.clear();
		this.timeStates.putAll(snapshot.timeStates);
		this.installPreparedClientGraph(prepared);
	}

	/** Installs a prepared, committed client scene graph; never repeats common initialization. */
	public void adoptPreparedClientGraph(World world, CelestialManager prepared) {
		requirePreparedClientGraph(world, prepared);
		this.installPreparedClientGraph(prepared);
		StellarSky.PROXY.setupStellarLoad(this);
	}

	private void installPreparedClientGraph(CelestialManager prepared) {
		// Preparation used detached metadata. No published collection may retain
		// that candidate as an alternative mutable authority after commit.
		for (StellarCollection collection : prepared.getLayers()) {
			collection.setManager(this);
		}
		this.celestialManager = prepared;
		this.setup = true;
	}

	private void requirePreparedClientGraph(World world, CelestialManager prepared) {
		if (!world.isRemote || StellarManager.getManager(world) != this
				|| prepared == null || !prepared.commonInitialized()) {
			throw new IllegalStateException("A prepared client graph must belong to its active world manager");
		}
	}

	public CelestialManager getCelestialManager() {
		return this.celestialManager;
	}

	public double getSkyYear(double currentTick) {
		return (currentTick + (settings.yearOffset * settings.year + settings.dayOffset)
				* settings.day + settings.tickOffset) / (settings.day * settings.year);
	}

	public CelestialPeriod getYearPeriod() {
		return new CelestialPeriod("Year", settings.day * settings.year,
				(settings.yearOffset * settings.year + settings.dayOffset)
				* settings.day + settings.tickOffset);
	}
	
	
	public void update(double time){
		double currentYear = this.getSkyYear(time);
		this.updateSkyYear(currentYear);
	}

	public void updateSkyYear(double currentYear) {
		celestialManager.update(currentYear);
	}

	public void setLocked(boolean locked) {
		this.locked = locked;
		this.markDirty();
	}

	public boolean isLocked() {
		return this.locked;
	}

	public double getTimeMultiplier(int dimension) {
		return getTimeState(dimension).multiplier;
	}

	public void setTimeMultiplier(int dimension, double timeMultiplier) {
		TimeState state = getTimeState(dimension);
		state.multiplier = clampTimeMultiplier(timeMultiplier);
		if(state.multiplier != 0)
			state.savedMultiplier = state.multiplier;
		this.markDirty();
	}

	public double getSavedTimeMultiplier(int dimension) {
		return getTimeState(dimension).savedMultiplier;
	}

	public boolean isSystemTimeSyncEnabled(int dimension) {
		return getTimeState(dimension).systemTimeSync;
	}

	public void setSystemTimeSyncEnabled(int dimension, boolean enabled) {
		getTimeState(dimension).systemTimeSync = enabled;
		this.markDirty();
	}

	public int getSystemTimeSyncIntervalSeconds(int dimension) {
		return getTimeState(dimension).systemTimeSyncIntervalSeconds;
	}

	public void setSystemTimeSyncIntervalSeconds(int dimension, int seconds) {
		getTimeState(dimension).systemTimeSyncIntervalSeconds = Math.max(1, Math.min(3600, seconds));
		this.markDirty();
	}

	public int getSystemTimeZoneOffsetMinutes(int dimension) {
		TimeState state = getTimeState(dimension);
		return state.hasTimeZoneOverride ? state.timeZoneOffsetMinutes
				: stellarium.time.StellarSkyTime.getServerTimeOffsetMinutes();
	}

	public boolean hasTimeZoneOverride(int dimension) {
		return getTimeState(dimension).hasTimeZoneOverride;
	}

	public int getConfiguredTimeZoneOffsetMinutes(int dimension) {
		return getTimeState(dimension).timeZoneOffsetMinutes;
	}

	public void setTimeZoneOffsetMinutes(int dimension, int offsetMinutes) {
		TimeState state = getTimeState(dimension);
		state.hasTimeZoneOverride = true;
		state.timeZoneOffsetMinutes = Math.max(-14 * 60, Math.min(14 * 60, offsetMinutes));
		this.markDirty();
	}

	public void clearTimeZoneOverride(int dimension) {
		getTimeState(dimension).hasTimeZoneOverride = false;
		this.markDirty();
	}

	public void setLocation(int dimension, double latitude, double longitude) {
		TimeState state = getTimeState(dimension);
		setLocation(dimension, latitude, longitude,
				state.hasLocationOverride ? state.altitude : 0.0);
	}

	public void setLocation(int dimension, double latitude, double longitude, double altitude) {
		TimeState state = getTimeState(dimension);
		state.hasLocationOverride = true;
		state.latitude = Math.max(-90.0, Math.min(90.0, latitude));
		state.longitude = normalizeLongitude(longitude);
		state.altitude = altitude;
		this.markDirty();
	}

	public void setAltitude(int dimension, double altitude) {
		TimeState state = getTimeState(dimension);
		state.hasLocationOverride = true;
		state.altitude = altitude;
		this.markDirty();
	}

	public void clearLocationOverride(int dimension) {
		getTimeState(dimension).hasLocationOverride = false;
		this.markDirty();
	}

	public Map<Integer, TimeState> getTimeStates() {
		return new HashMap<>(this.timeStates);
	}

	private TimeState getTimeState(int dimension) {
		TimeState state = this.timeStates.get(dimension);
		if(state == null) {
			state = new TimeState(defaultMultiplier(), defaultMultiplier(), defaultSystemTimeSync(),
					defaultSystemTimeSyncInterval());
			this.timeStates.put(dimension, state);
		}
		return state;
	}

	private double defaultMultiplier() {
		return this.settings == null ? 1 : clampTimeMultiplier(this.settings.timeMultiplier);
	}

	private boolean defaultSystemTimeSync() {
		return this.settings != null && this.settings.systemTimeSync;
	}

	private int defaultSystemTimeSyncInterval() {
		return this.settings == null ? 60
				: Math.max(1, Math.min(3600, this.settings.systemTimeSyncIntervalSeconds));
	}

	private static double clampTimeMultiplier(double multiplier) {
		return Math.max(-20, Math.min(72, multiplier));
	}

	private static double normalizeLongitude(double longitude) {
		double normalized = longitude % 360.0;
		return normalized < 0.0 ? normalized + 360.0 : normalized;
	}

	public static final class TimeState {
		private double multiplier;
		private double savedMultiplier;
		private boolean systemTimeSync;
		private int systemTimeSyncIntervalSeconds;
		private boolean hasTimeZoneOverride;
		private int timeZoneOffsetMinutes;
		private boolean hasLocationOverride;
		private double latitude;
		private double longitude;
		private double altitude;

		private TimeState(double multiplier, double savedMultiplier, boolean systemTimeSync,
				int systemTimeSyncIntervalSeconds) {
			this.multiplier = clampTimeMultiplier(multiplier);
			this.savedMultiplier = savedMultiplier == 0 ? 1 : clampTimeMultiplier(savedMultiplier);
			this.systemTimeSync = systemTimeSync;
			this.systemTimeSyncIntervalSeconds = Math.max(1, Math.min(3600, systemTimeSyncIntervalSeconds));
			this.hasTimeZoneOverride = false;
			this.timeZoneOffsetMinutes = 0;
		}

		private static TimeState read(NBTTagCompound tag, double defaultMultiplier, boolean defaultSystemTimeSync,
				int defaultSystemTimeSyncInterval) {
			TimeState state = new TimeState(tag.hasKey("Multiplier", 6) ? tag.getDouble("Multiplier")
					: tag.hasKey("Multiplier", 3) ? tag.getInteger("Multiplier") : defaultMultiplier,
					tag.hasKey("SavedMultiplier", 6) ? tag.getDouble("SavedMultiplier")
					: tag.hasKey("SavedMultiplier", 3) ? tag.getInteger("SavedMultiplier") : defaultMultiplier,
					tag.hasKey("SystemTimeSync", 1) ? tag.getBoolean("SystemTimeSync") : defaultSystemTimeSync,
					tag.hasKey("SystemTimeSyncInterval", 3) ? tag.getInteger("SystemTimeSyncInterval")
							: defaultSystemTimeSyncInterval);
			state.hasLocationOverride = tag.getBoolean("LocationOverride");
			state.latitude = tag.getDouble("Latitude");
			state.longitude = tag.getDouble("Longitude");
			state.altitude = tag.hasKey("Altitude", 6) ? tag.getDouble("Altitude") : 0.0;
			state.hasTimeZoneOverride = tag.getBoolean("TimeZoneOverride");
			state.timeZoneOffsetMinutes = Math.max(-14 * 60,
					Math.min(14 * 60, tag.getInteger("TimeZoneOffsetMinutes")));
			return state;
		}

		private NBTTagCompound write() {
			NBTTagCompound tag = new NBTTagCompound();
			tag.setDouble("Multiplier", this.multiplier);
			tag.setDouble("SavedMultiplier", this.savedMultiplier);
			tag.setBoolean("SystemTimeSync", this.systemTimeSync);
			tag.setInteger("SystemTimeSyncInterval", this.systemTimeSyncIntervalSeconds);
			tag.setBoolean("TimeZoneOverride", this.hasTimeZoneOverride);
			tag.setInteger("TimeZoneOffsetMinutes", this.timeZoneOffsetMinutes);
			tag.setBoolean("LocationOverride", this.hasLocationOverride);
			tag.setDouble("Latitude", this.latitude);
			tag.setDouble("Longitude", this.longitude);
			tag.setDouble("Altitude", this.altitude);
			return tag;
		}

		public double getMultiplier() {
			return this.multiplier;
		}

		public boolean isSystemTimeSync() {
			return this.systemTimeSync;
		}

		public int getSystemTimeSyncIntervalSeconds() {
			return this.systemTimeSyncIntervalSeconds;
		}

		public boolean hasTimeZoneOverride() {
			return this.hasTimeZoneOverride;
		}

		public int getTimeZoneOffsetMinutes() {
			return this.timeZoneOffsetMinutes;
		}

		public boolean hasLocationOverride() {
			return this.hasLocationOverride;
		}

		public double getLatitude() {
			return this.latitude;
		}

		public double getLongitude() {
			return this.longitude;
		}

		public double getAltitude() {
			return this.altitude;
		}
	}

	public boolean hasSetup() {
		return this.setup;
	}

	public static boolean hasSetup(World world) {
		return StellarManager.getManager(world).hasSetup();
	}
}
