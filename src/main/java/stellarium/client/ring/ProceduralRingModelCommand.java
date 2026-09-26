package stellarium.client.ring;

import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import stellarium.world.ring.terrain.TerrainPreviewTrace;

/** Client-local preview selector. No server state or persistent configuration is changed. */
public final class ProceduralRingModelCommand extends CommandBase {
    @Override public String getName() { return "ssmodel"; }
    @Override public String getUsage(ICommandSender sender) {
        // The counters are process-local: this client reports its own; a dedicated server needs /ssterrain status.
        return "/ssmodel on|preview|off|status|diagnose|terrain (terrain: this client process only; on a dedicated server use /ssterrain status)";
    }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void execute(MinecraftServer server,ICommandSender sender,String[] args) throws CommandException {
        if (args.length!=1) throw new WrongUsageException(getUsage(sender));
        switch (args[0]) {
            case "on" -> ProceduralRingModelRenderer.setEnabled(true);
            case "preview" -> ProceduralRingModelRenderer.setPreview();
            case "off" -> ProceduralRingModelRenderer.setEnabled(false);
            case "status" -> { }
            case "terrain" -> {
                // Plain counters only: no world, no renderer and no GL state is required.
                for(var line:TerrainPreviewTrace.reportLines())sender.sendMessage(new TextComponentString(line));
                return;
            }
            case "diagnose" -> {
                // The pass happens on a later frame, so this replies immediately with the
                // queue acknowledgement; the report follows in the log and in chat.
                sender.sendMessage(new TextComponentString(RingworldDrawDiagnostics.PREFIX + ' '
                        + RingworldDrawDiagnostics.request(ProceduralRingModelRenderer.diagnosticsUnavailable())));
                return;
            }
            default -> throw new WrongUsageException(getUsage(sender));
        }
        sender.sendMessage(new TextComponentString(ProceduralRingModelRenderer.status()));
    }
    @Override public List<String> getTabCompletions(MinecraftServer server,ICommandSender sender,String[] args,BlockPos targetPos) {
        return args.length==1?getListOfStringsMatchingLastWord(args,"on","preview","off","status","diagnose","terrain"):List.of();
    }
}
