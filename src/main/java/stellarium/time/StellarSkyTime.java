package stellarium.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.World;
import stellarium.stellars.StellarManager;

/**
 * Keeps the daylight clock independent from the simulation tick rate.
 */
public final class StellarSkyTime {
	public static final double MIN_MULTIPLIER = -20.0;
	public static final double MAX_MULTIPLIER = 72.0;

	private static final Map<Integer, ClientTimeState> clientStates = new ConcurrentHashMap<>();
	private static final Map<World, Long> correctionDeadlines = new WeakHashMap<>();
	/**
	 * The orbital elements bundled with StellarSky use the J2000 epoch. The
	 * date part is kept here; {@link #getAstronomicalYear(World, long)} also
	 * removes the twelve hours between civil midnight and J2000.0.
	 */
	private static final LocalDate SYSTEM_TIME_EPOCH = LocalDate.of(2000, 1, 1);
	public static final double REAL_TIME_YEAR_DAYS = 365.2422;

	private StellarSkyTime() {
	}

	public static double getMultiplier(World world) {
		if(world == null)
			return 1;
		int dimension = world.provider.getDimension();
		if(world.isRemote)
			return getClientState(dimension).multiplier;

		World overworld = world.getMinecraftServer().getEntityWorld();
		return StellarManager.getManager(overworld).getTimeMultiplier(dimension);
	}

	public static void setMultiplier(World world, double multiplier) {
		StellarManager.getManager(world.getMinecraftServer().getEntityWorld())
				.setTimeMultiplier(world.provider.getDimension(), clamp(multiplier));
	}

	public static void setClientTimeState(int dimension, double multiplier, boolean systemTimeSync, int offsetMinutes,
			int syncIntervalSeconds) {
		clientStates.put(dimension, new ClientTimeState(clamp(multiplier), systemTimeSync, offsetMinutes,
				syncIntervalSeconds));
	}

	public static boolean isSystemTimeSyncEnabled(World world) {
		if(world == null)
			return false;
		if(world.isRemote)
			return getClientState(world.provider.getDimension()).systemTimeSync;
		return StellarManager.getManager(world.getMinecraftServer().getEntityWorld())
				.isSystemTimeSyncEnabled(world.provider.getDimension());
	}

	public static double clamp(double multiplier) {
		if(Double.isNaN(multiplier) || Double.isInfinite(multiplier))
			return 1.0;
		return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
	}

	/**
	 * Converts the system-time world clock into a tropical-year fraction. In
	 * real-time mode world time is anchored at midnight, while Minecraft tick
	 * zero conventionally represents 06:00.
	 */
	public static double getAstronomicalYear(World world, long worldTime) {
		if(isSystemTimeSyncEnabled(world)) {
			double offsetTicks = getSystemTimeOffsetMinutes(world) * (24000.0 / 1440.0);
			// worldTime uses Minecraft's 06:00 phase (+18000 from civil
			// midnight). J2000.0 is 2000-01-01 12:00 UTC.
			return (worldTime - 18000.0 - offsetTicks - 12000.0)
					/ (24000.0 * REAL_TIME_YEAR_DAYS);
		}
		if(world.isRemote)
			return StellarManager.getManager(world).getSkyYear(worldTime);
		return StellarManager.getManager(world.getMinecraftServer().getEntityWorld()).getSkyYear(worldTime);
	}

	public static long getCivilTimeTicks(long worldTime) {
		return Math.floorMod(worldTime - 18000L, 24000L);
	}

	public static LocalDate getSystemCivilDate(long worldTime) {
		return SYSTEM_TIME_EPOCH.plusDays(Math.floorDiv(worldTime - 18000L, 24000L));
	}

	/**
	 * Returns the clock value that should replace vanilla's {@code currentTime + 1}.
	 * The world keeps ticking in every case; only the daylight clock changes.
	 */
	public static long nextWorldTime(World world, long currentTime) {
		if(isSystemTimeSyncEnabled(world)) {
			long now = System.currentTimeMillis();
			if(shouldCorrectSystemTime(world, now)) {
				int offset = world.isRemote
						? getClientState(world.provider.getDimension()).systemTimeOffsetMinutes
						: getServerTimeOffsetMinutes();
				return getSystemWorldTime(offset, now);
			}
			return currentTime + getScaledDelta(world, 72.0);
		}

		double multiplier = getMultiplier(world);
		if(multiplier == 0.0)
			return currentTime;
		return currentTime + getScaledDelta(world, multiplier);
	}

	/**
	 * Applies the same cadence to vanilla weather countdown writes. Negative time
	 * follows B3M's established behavior: consume the timer at reverse speed, so
	 * weather cannot become permanently stuck while the daylight clock rewinds.
	 */
	public static int nextWeatherTime(World world, int currentTime) {
		if(isSystemTimeSyncEnabled(world))
			return currentTime - (int) Math.abs(getScaledDelta(world, 72.0));

		double multiplier = getMultiplier(world);
		if(multiplier == 0.0)
			return currentTime;
		return (int) Math.max(0L, currentTime - Math.abs(getScaledDelta(world, multiplier)));
	}

	private static long getScaledDelta(World world, double multiplier) {
		double magnitude = Math.abs(multiplier);
		long total = world.getTotalWorldTime();
		long previous = (long) Math.floor(total / magnitude);
		long next = (long) Math.floor((total + 1L) / magnitude);
		long delta = next - previous;
		return multiplier < 0.0 ? -delta : delta;
	}

	/**
	 * Minecraft tick zero is 06:00. March 20 is used as the astronomical year
	 * origin so seasonal coordinates remain close to the real solar calendar.
	 */
	public static int getServerTimeOffsetMinutes() {
		return ZoneOffset.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds() / 60;
	}

	public static int getSystemTimeOffsetMinutes(World world) {
		if(world != null && world.isRemote)
			return getClientState(world.provider.getDimension()).systemTimeOffsetMinutes;
		return getServerTimeOffsetMinutes();
	}

	public static long ticksForCivilSeconds(double seconds) {
		return Math.round(seconds * (24000.0 / 86400.0));
	}

	public static long withCivilTime(long worldTime, int hour, int minute, int second) {
		long civilDay = Math.floorDiv(worldTime - 18000L, 24000L);
		long seconds = hour * 3600L + minute * 60L + second;
		return civilDay * 24000L + 18000L + ticksForCivilSeconds(seconds);
	}

	public static void resetSystemTimeCorrection(World world) {
		synchronized(correctionDeadlines) {
			correctionDeadlines.remove(world);
		}
	}

	private static boolean shouldCorrectSystemTime(World world, long now) {
		int intervalSeconds;
		if(world.isRemote) {
			intervalSeconds = getClientState(world.provider.getDimension()).syncIntervalSeconds;
		} else {
			intervalSeconds = StellarManager.getManager(world.getMinecraftServer().getEntityWorld())
					.getSystemTimeSyncIntervalSeconds(world.provider.getDimension());
		}
		long intervalMillis = Math.max(1, intervalSeconds) * 1000L;
		synchronized(correctionDeadlines) {
			Long deadline = correctionDeadlines.get(world);
			if(deadline != null && now < deadline)
				return false;
			correctionDeadlines.put(world, now + intervalMillis);
			return true;
		}
	}

	private static long getSystemWorldTime(int offsetMinutes, long nowMillis) {
		LocalDateTime now = Instant.ofEpochMilli(nowMillis)
				.atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toLocalDateTime();
		long days = ChronoUnit.DAYS.between(SYSTEM_TIME_EPOCH, now.toLocalDate());
		LocalTime time = now.toLocalTime();
		long millisOfDay = time.toSecondOfDay() * 1000L + time.getNano() / 1_000_000L;
		long ticksOfDay = (millisOfDay * 24000L) / 86_400_000L;
		// Do not reduce the shifted value modulo one day. The +18000 phase
		// crosses a Minecraft day boundary at civil 06:00 and that carry is
		// part of the absolute astronomical date.
		return days * 24000L + ticksOfDay + 18000L;
	}

	private static ClientTimeState getClientState(int dimension) {
		ClientTimeState state = clientStates.get(dimension);
		return state == null ? ClientTimeState.DEFAULT : state;
	}

	private static final class ClientTimeState {
		private static final ClientTimeState DEFAULT = new ClientTimeState(1.0, false, 0, 60);
		private final double multiplier;
		private final boolean systemTimeSync;
		private final int systemTimeOffsetMinutes;
		private final int syncIntervalSeconds;

		private ClientTimeState(double multiplier, boolean systemTimeSync, int systemTimeOffsetMinutes,
				int syncIntervalSeconds) {
			this.multiplier = multiplier;
			this.systemTimeSync = systemTimeSync;
			this.systemTimeOffsetMinutes = systemTimeOffsetMinutes;
			this.syncIntervalSeconds = Math.max(1, Math.min(3600, syncIntervalSeconds));
		}
	}
}
