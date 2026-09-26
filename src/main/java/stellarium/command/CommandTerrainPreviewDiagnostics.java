package stellarium.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import stellarium.world.ring.terrain.TerrainPreviewTrace;

/**
 * Read-only server-side terrain-preview diagnostics. The counters live in the process that observes them, so
 * a client can never print the server's copy: this command renders the server JVM's own snapshot.
 * <p>{@code /ssterrain status} only reads current values. It sends no seed, starts no preview session, touches
 * no world, connection, lease or wire state, and neither resets nor increments any counter, so repeated calls
 * are idempotent and a quiet server reports an explicit zero state.</p>
 */
public final class CommandTerrainPreviewDiagnostics extends CommandBase {

	private static final String USAGE = "/ssterrain status";

	@Override
	public String getName() {
		return "ssterrain";
	}

	@Override
	public String getUsage(ICommandSender sender) {
		return USAGE;
	}

	@Override
	public int getRequiredPermissionLevel() {
		return 2;
	}

	@Override
	public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
		// 1.12.2 declares ICommand#execute as void, so the decision is returned by report(...) below and this
		// entry point stays a plain override. The server argument is deliberately unused: no world, dimension
		// or preview session is required, so the command also works from the console before any world is up.
		report(sender, args);
	}

	/**
	 * @return true when the report was printed, false when the usage line was sent instead. Missing or unknown
	 *         arguments never print a report line and never throw.
	 */
	boolean report(ICommandSender sender, String[] args) {
		if(args.length != 1 || !"status".equalsIgnoreCase(args[0])) {
			sender.sendMessage(new TextComponentString(USAGE));
			return false;
		}
		for(String line : TerrainPreviewTrace.serverReportLines())
			sender.sendMessage(new TextComponentString(line));
		return true;
	}
}
