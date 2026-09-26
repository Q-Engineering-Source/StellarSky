package stellarium.world.ring.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;

/** Disposable-world A/B and persistence probe. No production registration or runtime command. */
@Mod(modid = "c53generationprobe", name = "C53 Generation Probe", version = "1", serverSideOnly = true,
        acceptableRemoteVersions = "*", dependencies = "required-after:stellarsky")
public final class C53GenerationProbeMod {
    @Mod.EventHandler public void run(FMLServerStartedEvent event) throws Exception {
        var request = Path.of("c53-generation.request");
        if (!Files.exists(request)) return;
        String mode = Files.readString(request).trim();
        boolean ring = mode.endsWith("ring");
        boolean reload = mode.startsWith("reload");
        WorldServer world = FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if (!world.getWorldInfo().getWorldName().equals("disposable-ring-world")) {
            throw new IllegalStateException("Probe requires disposable world");
        }
        if ((world.getChunkProvider().chunkGenerator instanceof RingworldChunkGenerator) != ring) {
            throw new AssertionError("Wrong generator for " + mode);
        }
        world.getGameRules().setOrCreateGameRule("randomTickSpeed", "0");
        var results = new Properties();
        results.setProperty("mode", mode);
        results.setProperty("generator", world.getChunkProvider().chunkGenerator.getClass().getName());
        results.setProperty("provider", world.provider.getClass().getName());
        results.setProperty("biomeProvider", world.getBiomeProvider().getClass().getName());
        // Opposite traversal directions at the two boundaries exercise neighbour population ordering.
        for (int centerZ : new int[] {0, 512, -513}) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int step = -2; step <= 2; step++) {
                    world.getChunkProvider().provideChunk(1024 + dx, centerZ + (centerZ < 0 ? -step : step));
                }
            }
            Chunk chunk = world.getChunkProvider().provideChunk(1024, centerZ);
            boolean outside = centerZ != 0;
            if (outside) {
                var marker = new BlockPos(16384, 240, centerZ * 16);
                if (reload) {
                    if (chunk.getBlockState(marker).getBlock() != Blocks.DIAMOND_BLOCK
                            || chunk.getBlockState(marker.add(1, 0, 0)).getBlock() != Blocks.STONE) {
                        throw new AssertionError("Saved exterior placement lost");
                    }
                } else {
                    if (chunk.isEmptyBetween(0, 255) != ring) throw new AssertionError("Wrong exterior terrain");
                    if (!world.setBlockState(marker, Blocks.DIAMOND_BLOCK.getDefaultState(), 2)) {
                        throw new AssertionError("Ordinary exterior placement blocked");
                    }
                    chunk.setBlockState(marker.add(1, 0, 0), Blocks.STONE.getDefaultState());
                }
                boolean space = SpaceBiome.suppressesNaturalGeneration(chunk.getBiome(marker, world.getBiomeProvider()));
                if (space != ring) throw new AssertionError("Wrong saved biome");
                if (ring) {
                    var ringGenerator = (RingworldChunkGenerator) world.getChunkProvider().chunkGenerator;
                    var blocked = marker.add(2, 0, 0);
                    try {
                        try (var scope = ringGenerator.openScope()) {
                            if (world.setBlockState(blocked, Blocks.STONE.getDefaultState(), 2)
                                    || chunk.setBlockState(blocked, Blocks.STONE.getDefaultState()) != null) {
                                throw new AssertionError("Natural generation crossed into Space");
                            }
                            throw new ExpectedFailure();
                        }
                    } catch (ExpectedFailure expected) {
                        if (!world.setBlockState(blocked, Blocks.STONE.getDefaultState(), 2)) {
                            throw new AssertionError("Generation scope leaked after exception");
                        }
                        world.setBlockState(blocked, Blocks.AIR.getDefaultState(), 2);
                    }
                }
            }
            results.setProperty("chunk." + centerZ, fingerprint(chunk));
        }
        results.setProperty("result", "PASS");
        try (var writer = Files.newBufferedWriter(Path.of("c53-generation-results.properties"))) {
            results.store(writer, "Semantic blocks and biome fingerprints; lighting/tick metadata excluded");
        }
    }

    private static String fingerprint(Chunk chunk) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        hash.update(chunk.getBiomeArray());
        var pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int state = Block.getStateId(chunk.getBlockState(pos.setPos(x, y, z)));
            hash.update((byte) state);
            hash.update((byte) (state >>> 8));
            hash.update((byte) (state >>> 16));
            hash.update((byte) (state >>> 24));
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static final class ExpectedFailure extends RuntimeException {}
}
