package stellarium.command;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import stellarapi.api.CelestialPeriod;
import stellarapi.api.PeriodHelper;
import stellarapi.api.SAPIReferences;
import stellarapi.api.celestials.CelestialEffectors;
import stellarapi.api.celestials.IEffectorType;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.view.ICCoordinates;
import stellarium.StellarSky;
import stellarium.api.observer.ObserverSkyContext;
import stellarium.api.observer.ObserverSkyResolvers;
import stellarium.stellars.StellarManager;
import stellarium.time.StellarSkyTime;
import stellarium.world.StellarScene;

/**
 * B3M-compatible daylight and location controls. Values are stored per
 * dimension in StellarManager, while the astronomical rendering remains
 * StellarSky's own implementation.
 */
public final class CommandStellarTime extends CommandBase {
	@Override
	public String getName() {
		return "stellartime";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/stellartime [dimension] <info|time|add|scale|pause|resume|reset|sync|real|location|latitude|longitude|altitude|offset> ...";
	}

	@Override
	public List<String> getAliases() {
		return Arrays.asList("b3m", "ts");
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
		World senderWorld = sender.getEntityWorld();
		int dimension = senderWorld.provider.getDimension();
		int index = 0;
		if(args.length == 0) {
			send(sender, dimension, StellarManager.getManager(server.getEntityWorld()));
			return;
		}
		if(args.length >= 2 && isInteger(args[0])) {
			dimension = parseInt(args[0]);
			index = 1;
		}
		if(args.length <= index)
			throw new CommandException(getUsage(sender));

		StellarManager manager = StellarManager.getManager(server.getEntityWorld());
		String action = args[index].toLowerCase(Locale.ROOT);
		if("set".equals(action)) {
			if(args.length <= ++index)
				throw new CommandException(getUsage(sender));
			action = args[index].toLowerCase(Locale.ROOT);
		}
		if("info".equals(action) || "status".equals(action)) {
			requireArgCount(args, index, 1);
			sendInfo(server, sender, dimension, StellarManager.getManager(server.getEntityWorld()));
			return;
		}
		if("sync".equals(action) && args.length == index + 3
				&& "interval".equalsIgnoreCase(args[index + 1])) {
			manager.setSystemTimeSyncIntervalSeconds(dimension, parseInt(args[index + 2], 1, 3600));
			resetCorrection(server, dimension);
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("syncinterval".equals(action)) {
			requireArgCount(args, index, 2);
			manager.setSystemTimeSyncIntervalSeconds(dimension, parseInt(args[index + 1], 1, 3600));
			resetCorrection(server, dimension);
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("timezone".equals(action) || "tz".equals(action)) {
			requireArgCount(args, index, 2);
			String value = args[index + 1];
			if("server".equalsIgnoreCase(value) || "auto".equalsIgnoreCase(value)) {
				manager.clearTimeZoneOverride(dimension);
			} else {
				manager.setTimeZoneOffsetMinutes(dimension, parseTimeZoneOffsetMinutes(value));
			}
			resetCorrection(server, dimension);
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("sync".equals(action) || "system".equals(action) || "systemtime".equals(action)) {
			requireArgCount(args, index, 2);
			manager.setSystemTimeSyncEnabled(dimension, parseOnOff(args[index + 1]));
			resetCorrection(server, dimension);
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("real".equals(action)) {
			requireArgRange(args, index, 3, 4);
			setLocation(server, dimension, parseDecimal(args[index + 1]),
					parseDecimal(args[index + 2]),
					args.length == index + 4 ? parseAltitude(args[index + 3]) : 0.0,
					manager);
			manager.setSystemTimeSyncEnabled(dimension, true);
			resetCorrection(server, dimension);
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("location".equals(action)) {
			requireArgRange(args, index, 3, 4);
			StellarManager.TimeState current = manager.getTimeStates().get(dimension);
			double altitude = args.length == index + 4 ? parseAltitude(args[index + 3])
					: current != null && current.hasLocationOverride()
							? current.getAltitude() : 0.0;
			setLocation(server, dimension, parseDecimal(args[index + 1]),
					parseDecimal(args[index + 2]), altitude, manager);
			send(sender, dimension, manager);
			return;
		}
		if("latitude".equals(action)) {
			requireArgCount(args, index, 2);
			StellarManager.TimeState current = manager.getTimeStates().get(dimension);
			setLocation(server, dimension, parseDecimal(args[index + 1]),
					current != null && current.hasLocationOverride() ? current.getLongitude()
							: configuredLocation(server, dimension, false),
					currentAltitude(current), manager);
			send(sender, dimension, manager);
			return;
		}
		if("longitude".equals(action)) {
			requireArgCount(args, index, 2);
			StellarManager.TimeState current = manager.getTimeStates().get(dimension);
			setLocation(server, dimension,
					current != null && current.hasLocationOverride() ? current.getLatitude()
							: configuredLocation(server, dimension, true),
					parseDecimal(args[index + 1]), currentAltitude(current), manager);
			send(sender, dimension, manager);
			return;
		}
		if("altitude".equals(action) || "height".equals(action)) {
			requireArgCount(args, index, 2);
			StellarManager.TimeState current = manager.getTimeStates().get(dimension);
			double latitude = current != null && current.hasLocationOverride()
					? current.getLatitude() : configuredLocation(server, dimension, true);
			double longitude = current != null && current.hasLocationOverride()
					? current.getLongitude() : configuredLocation(server, dimension, false);
			setLocation(server, dimension, latitude, longitude,
					parseAltitude(args[index + 1]), manager);
			send(sender, dimension, manager);
			return;
		}
		if("offset".equals(action) || "localoffset".equals(action)) {
			requireArgCount(args, index, 2);
			StellarManager.TimeState current = manager.getTimeStates().get(dimension);
			double latitude = current != null && current.hasLocationOverride() ? current.getLatitude()
					: configuredLocation(server, dimension, true);
			setLocation(server, dimension, latitude, parseDecimal(args[index + 1]) * 15.0,
					currentAltitude(current), manager);
			send(sender, dimension, manager);
			return;
		}
		if("resetlocation".equals(action)) {
			requireArgCount(args, index, 1);
			manager.clearLocationOverride(dimension);
			World targetWorld = server.getWorld(dimension);
			if(targetWorld != null) {
				StellarScene scene = StellarScene.getScene(targetWorld);
				if(scene != null)
					scene.clearDynamicLocation();
			}
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("time".equals(action)) {
			requireArgCount(args, index, 2);
			int[] clock = parseClock(args[index + 1]);
			World targetWorld = requireWorld(server, dimension);
			manager.setSystemTimeSyncEnabled(dimension, false);
			setCivilTime(targetWorld, clock[0], clock[1], clock[2]);
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}
		if("add".equals(action)) {
			requireArgCount(args, index, 2);
			World targetWorld = requireWorld(server, dimension);
			manager.setSystemTimeSyncEnabled(dimension, false);
			addCivilTime(targetWorld, parseDurationTicks(args[index + 1]));
			sync(server, dimension, manager);
			send(sender, dimension, manager);
			return;
		}

		double multiplier;
		if("pause".equals(action)) {
			multiplier = 0.0;
		} else if("resume".equals(action)) {
			multiplier = manager.getSavedTimeMultiplier(dimension);
		} else if("reset".equals(action)) {
			multiplier = 1.0;
		} else if("scale".equals(action) || "multiplier".equals(action) || "timemultiplier".equals(action)) {
			requireArgCount(args, index, 2);
			multiplier = parseMultiplier(args[index + 1]);
		} else {
			multiplier = parseMultiplier(action);
		}

		manager.setTimeMultiplier(dimension, multiplier);
		sync(server, dimension, manager);
		send(sender, dimension, manager);
	}

	private static void setLocation(MinecraftServer server, int dimension, double latitude,
			double longitude, double altitude, StellarManager manager) throws CommandException {
		if(latitude < -90.0 || latitude > 90.0)
			throw new CommandException("Latitude must be between -90 and 90.");
		manager.setLocation(dimension, latitude, longitude, altitude);
		World targetWorld = server.getWorld(dimension);
		if(targetWorld != null) {
			StellarScene scene = StellarScene.getScene(targetWorld);
			if(scene != null)
				scene.setDynamicLocation(latitude, longitude);
		}
		sync(server, dimension, manager);
	}

	private static void sync(MinecraftServer server, int dimension, StellarManager manager) {
		StellarSky.INSTANCE.getNetworkManager().sendTimeState(dimension,
				manager.getTimeMultiplier(dimension), manager.isSystemTimeSyncEnabled(dimension));
		StellarSky.INSTANCE.getNetworkManager().sendObserverContextsInDimension(dimension, manager, server);
	}

	private static void resetCorrection(MinecraftServer server, int dimension) {
		World targetWorld = server.getWorld(dimension);
		if(targetWorld != null)
			StellarSkyTime.resetSystemTimeCorrection(targetWorld);
	}

	private static double configuredLocation(MinecraftServer server, int dimension, boolean latitude) {
		World targetWorld = server.getWorld(dimension);
		if(targetWorld == null)
			return 0.0;
		StellarScene scene = StellarScene.getScene(targetWorld);
		if(scene == null)
			return 0.0;
		return latitude ? scene.getSettings().latitude : scene.getSettings().longitude;
	}

	private static double currentAltitude(StellarManager.TimeState state) {
		return state != null && state.hasLocationOverride() ? state.getAltitude() : 0.0;
	}

	private static void send(ICommandSender sender, int dimension, StellarManager manager) {
		StellarManager.TimeState state = manager.getTimeStates().get(dimension);
		String scale = manager.isSystemTimeSyncEnabled(dimension) ? "system" : format(manager.getTimeMultiplier(dimension)) + "x";
		if(manager.isSystemTimeSyncEnabled(dimension))
			scale += " (resync " + manager.getSystemTimeSyncIntervalSeconds(dimension) + "s)";
		String location = state != null && state.hasLocationOverride()
				? ", lat " + format(state.getLatitude()) + ", lon " + format(state.getLongitude())
						+ ", altitude " + format(state.getAltitude()) + "m" : "";
		if(manager.isSystemTimeSyncEnabled(dimension)) {
			int zone = manager.getSystemTimeZoneOffsetMinutes(dimension);
			location += ", timezone UTC" + format(zone / 60.0)
					+ (manager.hasTimeZoneOverride(dimension) ? " (dimension)" : " (server)");
		}
		sender.sendMessage(new TextComponentString("Stellar time dimension " + dimension + ": " + scale + location));
	}

	private static void sendInfo(MinecraftServer server, ICommandSender sender, int dimension,
			StellarManager manager) throws CommandException {
		World world = server.getWorld(dimension);
		if(world == null)
			throw new CommandException("Dimension " + dimension + " is not loaded.");

		long worldTime = world.getWorldTime();
		long civilTicks;
		try {
			StellarSkyTime.refreshAstronomicalState(world);
			civilTicks = StellarSkyTime.getCivilTimeTicks(world, worldTime, 0.0f);
		} catch(IllegalStateException exception) {
			throw new CommandException(exception.getMessage());
		}
		long civilSeconds = Math.round(civilTicks * (86400.0 / 24000.0)) % 86400L;
		String civilTime = String.format(Locale.ROOT, "%02d:%02d:%02d",
				civilSeconds / 3600L, civilSeconds / 60L % 60L, civilSeconds % 60L);
		StellarManager.TimeState state = manager.getTimeStates().get(dimension);
		boolean systemSync = manager.isSystemTimeSyncEnabled(dimension);
		String dimensionName = world.provider.getDimensionType().getName();

		sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
				"World: dimension %d (%s), worldTime %d, totalWorldTime %d, civil %s.",
				dimension, dimensionName, worldTime, world.getTotalWorldTime(), civilTime)));
		sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
				"Time: %s, multiplier %sx, sync %s, interval %ds, rain %d, thunder %d.",
				systemSync ? "system" : "simulated",
				format(manager.getTimeMultiplier(dimension)), systemSync ? "on" : "off",
				manager.getSystemTimeSyncIntervalSeconds(dimension),
				world.getWorldInfo().getRainTime(), world.getWorldInfo().getThunderTime())));
		if(systemSync)
			sender.sendMessage(new TextComponentString("System date: "
					+ StellarSkyTime.getSystemCivilDate(worldTime) + "."));

		double latitude = 0.0;
		double longitude = 0.0;
		double altitude = 0.0;
		StellarManager.TimeState configuredState = state;
		if(configuredState != null && configuredState.hasLocationOverride()) {
			latitude = configuredState.getLatitude();
			longitude = configuredState.getLongitude();
			altitude = configuredState.getAltitude();
		} else {
			StellarScene scene = StellarScene.getScene(world);
			if(scene != null) {
				latitude = scene.getSettings().latitude;
				longitude = scene.getSettings().longitude;
			}
		}
		Entity entity = sender.getCommandSenderEntity();
		if(entity != null && entity.world != world)
			entity = null;
		ObserverSkyContext fallback =
				ObserverSkyContext.dimensionDefault(dimension, latitude, longitude, altitude);
		ObserverSkyContext observer = ObserverSkyResolvers.resolve(world, entity, fallback);
		String observerName = entity == null ? "server" : entity.getName();
		if(entity == null) {
			sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
					"Observer: %s, system %s, body %s, frame %s, lat %s, lon %s, altitude %s.",
					observerName, observer.getSystemId(), observer.getBodyId(), observer.getFrameId(),
					format(observer.getLatitude()), format(observer.getLongitude()), format(observer.getAltitude()))));
		} else {
			sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
					"Observer: %s at (%s, %s, %s), system %s, body %s, frame %s.",
					observerName, format(entity.posX), format(entity.posY), format(entity.posZ),
					observer.getSystemId(), observer.getBodyId(), observer.getFrameId())));
			sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
					"Observer sky: lat %s, lon %s, altitude %s.",
					format(observer.getLatitude()), format(observer.getLongitude()),
					format(observer.getAltitude()))));
		}

		sendSolarDiagnostics(sender, world, worldTime, longitude);
	}

	private static void sendSolarDiagnostics(ICommandSender sender, World world,
			long worldTime, double longitude) {
		CelestialPeriod period = PeriodHelper.getDayPeriod(world);
		ICCoordinates coordinate = SAPIReferences.getCoordinates(world);
		CelestialEffectors lights = SAPIReferences.getEffectors(world, IEffectorType.Light);
		if(period == null || coordinate == null || lights == null)
			return;

		Vector3 horizontal = coordinate.getProjectionToGround().transform(
				new Vector3(lights.getPrimarySource().getCurrentPos()));
		SpCoord sun = new SpCoord().setWithVec(horizontal);
		double providerAngle = world.provider.calculateCelestialAngle(worldTime, 0.0f);
		double phase = period.getOffset(worldTime, 0.0f);
		sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
				"Sun: altitude %.3f deg, azimuth %.3f deg, solar phase %.6f, provider angle %.6f.",
				sun.y, sun.x, phase, providerAngle)));
		sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
				"Solar period: %.3f ticks, zero phase %.6f.",
				period.getPeriodLength(), period.getZerotimeOffset())));

		int zoneOffsetMinutes = StellarSkyTime.getSystemTimeOffsetMinutes(world);
		double zoneMeridian = zoneOffsetMinutes / 4.0;
		double longitudeDifference = longitude - zoneMeridian;
		longitudeDifference -= Math.floor((longitudeDifference + 180.0) / 360.0) * 360.0;
		sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
				"Timezone: UTC%+.2f (%s), zone meridian %.3f deg, longitude solar correction %+.2f min (equation of time excluded).",
				zoneOffsetMinutes / 60.0,
				managerTimeZoneSource(world),
				zoneMeridian, longitudeDifference * 4.0)));
	}

	private static String managerTimeZoneSource(World world) {
		StellarManager manager = StellarManager.getManager(world.getMinecraftServer().getEntityWorld());
		return manager.hasTimeZoneOverride(world.provider.getDimension()) ? "dimension" : "server";
	}

	private static double parseMultiplier(String value) throws CommandException {
		double multiplier = parseDecimal(value);
		if(multiplier < StellarSkyTime.MIN_MULTIPLIER || multiplier > StellarSkyTime.MAX_MULTIPLIER)
			throw new CommandException("Time scale must be between -20 and 72.");
		return multiplier;
	}

	private static double parseDecimal(String value) throws CommandException {
		try {
			double result = Double.parseDouble(value);
			if(Double.isNaN(result) || Double.isInfinite(result))
				throw new NumberFormatException(value);
			return result;
		} catch(NumberFormatException exception) {
			throw new CommandException("Invalid number: " + value);
		}
	}

	private static double parseAltitude(String value) throws CommandException {
		double altitude = parseDecimal(value);
		if(altitude < -12000.0 || altitude > 1.0e9)
			throw new CommandException("Altitude must be between -12000 and 1000000000 meters.");
		return altitude;
	}

	private static int parseTimeZoneOffsetMinutes(String value) throws CommandException {
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if(normalized.startsWith("UTC"))
			normalized = normalized.substring(3);
		if(normalized.isEmpty() || "+".equals(normalized) || "-".equals(normalized))
			throw new CommandException("Invalid timezone offset: " + value);
		try {
			boolean negative = normalized.charAt(0) == '-';
			if(normalized.charAt(0) == '+' || negative)
				normalized = normalized.substring(1);
			String[] parts = normalized.split(":", -1);
			int hours = Integer.parseInt(parts[0]);
			int minutes = parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
			if(hours < 0 || hours > 14 || minutes < 0 || minutes >= 60
					|| (hours == 14 && minutes != 0))
				throw new NumberFormatException(value);
			int result = hours * 60 + minutes;
			return negative ? -result : result;
		} catch(NumberFormatException exception) {
			throw new CommandException("Timezone must be UTC offset between -14:00 and +14:00.");
		}
	}

	public static int[] parseClock(String value) throws CommandException {
		String[] parts = value.split(":", -1);
		if(parts.length < 1 || parts.length > 3)
			throw new CommandException("Expected time as HH[:MM[:SS]].");
		int hour = parseBounded(parts[0], 0, 23, "hour");
		int minute = parts.length >= 2 ? parseBounded(parts[1], 0, 59, "minute") : 0;
		int second = parts.length >= 3 ? parseBounded(parts[2], 0, 59, "second") : 0;
		return new int[] { hour, minute, second };
	}

	public static long parseDurationTicks(String value) throws CommandException {
		String normalized = value.toLowerCase(Locale.ROOT);
		double seconds;
		if(normalized.indexOf(':') >= 0) {
			String[] parts = normalized.split(":", -1);
			if(parts.length < 2 || parts.length > 3)
				throw new CommandException("Expected duration as HH:MM[:SS].");
			double hours = parseDecimal(parts[0]);
			double minutes = parseDecimal(parts[1]);
			double extraSeconds = parts.length == 3 ? parseDecimal(parts[2]) : 0.0;
			if(minutes < 0 || minutes >= 60 || extraSeconds < 0 || extraSeconds >= 60)
				throw new CommandException("Duration minutes and seconds must be between 0 and 59.");
			seconds = Math.copySign(Math.abs(hours) * 3600.0 + minutes * 60.0 + extraSeconds, hours);
		} else {
			double factor = 3600.0;
			if(normalized.endsWith("d")) {
				factor = 86400.0;
				normalized = normalized.substring(0, normalized.length() - 1);
			} else if(normalized.endsWith("h")) {
				normalized = normalized.substring(0, normalized.length() - 1);
			} else if(normalized.endsWith("m")) {
				factor = 60.0;
				normalized = normalized.substring(0, normalized.length() - 1);
			} else if(normalized.endsWith("s")) {
				factor = 1.0;
				normalized = normalized.substring(0, normalized.length() - 1);
			}
			// A bare number is an hour count, never an implementation tick.
			seconds = parseDecimal(normalized) * factor;
		}
		return StellarSkyTime.ticksForCivilSeconds(seconds);
	}

	static void setCivilTime(World world, int hour, int minute, int second) throws CommandException {
		try {
			StellarSkyTime.refreshAstronomicalState(world);
			world.setWorldTime(StellarSkyTime.withCivilTime(world, world.getWorldTime(), hour, minute, second));
			StellarSkyTime.refreshAstronomicalState(world);
		} catch(IllegalStateException exception) {
			throw new CommandException(exception.getMessage());
		}
	}

	static void addCivilTime(World world, long civilTicks) throws CommandException {
		try {
			StellarSkyTime.refreshAstronomicalState(world);
			world.setWorldTime(StellarSkyTime.addCivilTime(world, world.getWorldTime(), civilTicks));
			StellarSkyTime.refreshAstronomicalState(world);
		} catch(IllegalStateException exception) {
			throw new CommandException(exception.getMessage());
		}
	}

	private static int parseBounded(String value, int min, int max, String name) throws CommandException {
		try {
			int parsed = Integer.parseInt(value);
			if(parsed < min || parsed > max)
				throw new NumberFormatException(value);
			return parsed;
		} catch(NumberFormatException exception) {
			throw new CommandException("Invalid " + name + ": " + value);
		}
	}

	private static World requireWorld(MinecraftServer server, int dimension) throws CommandException {
		World world = server.getWorld(dimension);
		if(world == null)
			throw new CommandException("Dimension " + dimension + " is not loaded.");
		return world;
	}

	private static boolean parseOnOff(String value) throws CommandException {
		if("on".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value))
			return true;
		if("off".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value))
			return false;
		throw new CommandException("Expected on or off.");
	}

	private static void requireArgCount(String[] args, int index, int required) throws CommandException {
		if(args.length != index + required)
			throw new CommandException("Invalid argument count.");
	}

	private static void requireArgRange(String[] args, int index, int minimum,
			int maximum) throws CommandException {
		int count = args.length - index;
		if(count < minimum || count > maximum)
			throw new CommandException("Invalid argument count.");
	}

	private static boolean isInteger(String value) {
		try {
			Integer.parseInt(value);
			return true;
		} catch(NumberFormatException ignored) {
			return false;
		}
	}

	private static String format(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.3f", value);
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
		int index = args.length > 0 && isInteger(args[0]) ? 1 : 0;
		if(args.length == index + 1)
			return getListOfStringsMatchingLastWord(args, "info", "status", "set", "time", "add", "pause", "resume", "reset", "sync", "scale",
					"real", "location", "Latitude", "Longitude", "Altitude",
					"LocalOffset", "TimeZone", "resetlocation");
		if(args.length == index + 2 && ("sync".equalsIgnoreCase(args[index])
				|| "system".equalsIgnoreCase(args[index])))
			return getListOfStringsMatchingLastWord(args, "on", "off", "yes", "no", "interval");
		if(args.length == index + 2 && "set".equalsIgnoreCase(args[index]))
			return getListOfStringsMatchingLastWord(args, "TimeMultiplier", "SystemTime", "Latitude",
					"Longitude", "Altitude", "LocalOffset", "SyncInterval");
		return Collections.emptyList();
	}
}
