package stellarium.client.ring;

import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

/** Client-local cloud mesh inspection command. It neither contacts nor changes the server. */
public final class CloudDebugCommand extends CommandBase {
    @Override public String getName() { return "sscloud"; }
    @Override public String getUsage(ICommandSender sender) { return "/sscloud debug off|vertices|triangles|both | material exact|cached | status | profile"; }
    @Override public int getRequiredPermissionLevel() { return 0; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 1 && args[0].equals("status")) {
            sender.sendMessage(new TextComponentString(CloudDebugSettings.status()));
            return;
        }
        if (args.length == 1 && args[0].equals("profile")) {
            RingworldGpuProfile.dispose();
            sender.sendMessage(new TextComponentString("SS cloud GPU profile reset; hold the same view for a fresh sample window"));
            sender.sendMessage(new TextComponentString(CloudDebugSettings.status()));
            return;
        }
        if (args.length == 2 && args[0].equals("material")) {
            CloudDebugSettings.MaterialMode material;
            try {
                material = CloudDebugSettings.MaterialMode.fromCommand(args[1]);
            } catch (IllegalArgumentException invalid) {
                throw new WrongUsageException(getUsage(sender));
            }
            CloudDebugSettings.setMaterialMode(material);
            // A new mode starts a new sample generation; it does not rebuild/upload the mesh.
            RingworldGpuProfile.dispose();
            sender.sendMessage(new TextComponentString("SS cloud material=" + material.commandName()
                    + "; applies to embedded far pages; near physical pages use local coordinates; GPU profile reset"));
            return;
        }
        if (args.length == 2 && args[0].equals("debug")) {
            CloudDebugSettings.Mode mode;
            try {
                mode = parseMode(args[1]);
            } catch (IllegalArgumentException ignored) {
                throw new WrongUsageException(getUsage(sender));
            }
            CloudDebugSettings.setMode(mode);
            // The debug draw belongs to the connected model renderer. Enabling it is
            // intentional; turning the overlay off leaves the user's model choice intact.
            if (mode != CloudDebugSettings.Mode.OFF) ProceduralRingModelRenderer.setEnabled(true);
            sender.sendMessage(new TextComponentString(CloudDebugSettings.status()));
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }

    static CloudDebugSettings.Mode parseMode(String argument) {
        return CloudDebugSettings.Mode.fromCommand(argument);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "debug", "material", "status", "profile");
        if (args.length == 2 && args[0].equals("debug")) {
            return getListOfStringsMatchingLastWord(args, "off", "vertices", "triangles", "both");
        }
        if (args.length == 2 && args[0].equals("material")) {
            return getListOfStringsMatchingLastWord(args, "exact", "cached");
        }
        return List.of();
    }
}
