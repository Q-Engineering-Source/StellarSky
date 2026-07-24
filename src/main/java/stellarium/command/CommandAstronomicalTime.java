package stellarium.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import stellarium.StellarSky;
import stellarium.stellars.StellarManager;
import stellarium.time.StellarSkyTime;

/**
 * Location-aware replacement for vanilla's tick-based /time command.
 */
public final class CommandAstronomicalTime extends CommandBase {
	@Override
	public String getName() {
		return "time";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return "/time [dimension] <set HH[:MM[:SS]]|add <hours|duration>|query daytime>";
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
		int dimension = sender.getEntityWorld().provider.getDimension();
		int index = 0;
		if(args.length >= 3 && isInteger(args[0])) {
			dimension = parseInt(args[0]);
			index = 1;
		}
		if(args.length != index + 2)
			throw new CommandException(getUsage(sender));

		World world = server.getWorld(dimension);
		if(world == null)
			throw new CommandException("Dimension " + dimension + " is not loaded.");

		String action = args[index].toLowerCase(Locale.ROOT);
		String value = args[index + 1];
		StellarManager manager = StellarManager.getManager(server.getEntityWorld());
		if("set".equals(action)) {
			int[] clock;
			if("day".equalsIgnoreCase(value))
				clock = new int[] { 7, 0, 0 };
			else if("night".equalsIgnoreCase(value))
				clock = new int[] { 19, 0, 0 };
			else
				clock = CommandStellarTime.parseClock(value);
			manager.setSystemTimeSyncEnabled(dimension, false);
			world.setWorldTime(StellarSkyTime.withCivilTime(world.getWorldTime(), clock[0], clock[1], clock[2]));
			sync(server, dimension, manager);
			sender.sendMessage(new TextComponentString(String.format(Locale.ROOT,
					"Time in dimension %d set to %02d:%02d:%02d.", dimension, clock[0], clock[1], clock[2])));
			return;
		}
		if("add".equals(action)) {
			long ticks = CommandStellarTime.parseDurationTicks(value);
			manager.setSystemTimeSyncEnabled(dimension, false);
			world.setWorldTime(world.getWorldTime() + ticks);
			sync(server, dimension, manager);
			sender.sendMessage(new TextComponentString("Added " + value + " to dimension " + dimension + "."));
			return;
		}
		if("query".equals(action) && "daytime".equalsIgnoreCase(value)) {
			long civilTicks = Math.floorMod(world.getWorldTime() - 18000L, 24000L);
			int seconds = (int) Math.round(civilTicks * (86400.0 / 24000.0)) % 86400;
			sender.setCommandStat(CommandResultStats.Type.QUERY_RESULT, seconds);
			sender.sendMessage(new TextComponentString(String.format(Locale.ROOT, "Time in dimension %d is %02d:%02d:%02d.",
					dimension, seconds / 3600, seconds / 60 % 60, seconds % 60)));
			return;
		}
		throw new CommandException(getUsage(sender));
	}

	private static void sync(MinecraftServer server, int dimension, StellarManager manager) {
		StellarSky.INSTANCE.getNetworkManager().sendTimeState(dimension,
				manager.getTimeMultiplier(dimension), manager.isSystemTimeSyncEnabled(dimension));
	}

	private static boolean isInteger(String value) {
		try {
			Integer.parseInt(value);
			return true;
		} catch(NumberFormatException ignored) {
			return false;
		}
	}

	@Override
	public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
			BlockPos targetPos) {
		int index = args.length > 0 && isInteger(args[0]) ? 1 : 0;
		if(args.length == index + 1)
			return getListOfStringsMatchingLastWord(args, "set", "add", "query");
		if(args.length == index + 2 && "set".equalsIgnoreCase(args[index]))
			return getListOfStringsMatchingLastWord(args, Arrays.asList("day", "night", "06:00", "12:00", "18:00"));
		if(args.length == index + 2 && "query".equalsIgnoreCase(args[index]))
			return getListOfStringsMatchingLastWord(args, "daytime");
		return Collections.emptyList();
	}
}
