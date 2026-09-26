package stellarium.world.ring.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.MapGenStronghold;
import net.minecraft.world.gen.structure.MapGenMineshaft;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;

/** Isolated headless integration fixture; never packaged into the release mod. */
@Mod(modid = "c53locateprobe", name = "C53 Locate Probe", version = "1", serverSideOnly = true,
        acceptableRemoteVersions = "*", dependencies = "required-after:stellarsky")
public final class C53LocateProbeMod {
    @Mod.EventHandler public void run(FMLServerStartedEvent event) throws Exception {
        if (!Files.exists(Path.of("c53-locate.request"))) return;
        WorldServer world = FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(0);
        if (!world.getWorldInfo().getWorldName().equals("disposable-ring-world")) {
            throw new IllegalStateException("Probe requires its disposable world");
        }
        int before = world.getChunkProvider().getLoadedChunkCount();
        var lines = new ArrayList<String>();
        var policy = new RingworldGenerationPolicy();
        for (String type : new String[] {"Stronghold", "Village", "Mineshaft", "Temple", "Monument", "Mansion"}) {
            for (int z : new int[] {20744, -20744}) {
                var pos = world.getChunkProvider().getNearestStructurePos(world, type, new BlockPos(0, 64, z), false);
                if (pos != null && !policy.allowsStructureStart(pos.getZ() >> 4)) {
                    throw new AssertionError(type + " illegal candidate " + pos);
                }
                if (type.equals("Stronghold") && pos == null) throw new AssertionError("Stronghold search lost alternatives");
                lines.add(type + " originZ=" + z + " result=" + pos);
            }
            var unexplored = world.getChunkProvider().getNearestStructurePos(world, type, new BlockPos(0, 64, 20744), true);
            if (unexplored != null && (!policy.allowsStructureStart(unexplored.getZ() >> 4)
                    || world.isChunkGeneratedAt(unexplored.getX() >> 4, unexplored.getZ() >> 4))) {
                throw new AssertionError(type + " ignored unexplored filter");
            }
            lines.add(type + " unexplored=" + unexplored);
        }
        var stronghold = new StrongholdFixture(world);
        var rng = stronghold.random();
        rng.setSeed(9123);
        var candidate = stronghold.getNearestStructurePos(world, new BlockPos(0, 64, 20744), false);
        if (candidate == null) throw new AssertionError("Missing legal stronghold");
        if (stronghold.random() != rng || rng.nextLong() != new Random(9123).nextLong()) {
            throw new AssertionError("Locate altered generator RNG");
        }
        int indexed = stronghold.indexSize();
        var lookup = (RingworldStructureLookup) (Object) stronghold;
        if (!lookup.stellarium$allowsLocate(world, candidate.getX() >> 4, candidate.getZ() >> 4)
                || stronghold.indexSize() != indexed) throw new AssertionError("Prediction published an index");
        stronghold.verifyPreparedLayout(world, candidate);
        var mineshaft = new MineshaftFixture(world);
        var minePos = mineshaft.getNearestStructurePos(world, new BlockPos(0, 64, 20744), false);
        if (minePos == null || !((RingworldStructureLookup) (Object) mineshaft)
                .stellarium$allowsLocate(world, minePos.getX() >> 4, minePos.getZ() >> 4)) {
            throw new AssertionError("Missing legal mine");
        }
        mineshaft.verifyPreparedLayout(world, minePos);
        stronghold.installLegacy(0, 1296);
        if (!lookup.stellarium$allowsLocate(world, 0, 1296)) throw new AssertionError("Legacy index lost");
        var failure = new FailureFixture(world);
        Random original = failure.random();
        try {
            ((RingworldStructureLookup) (Object) failure).stellarium$allowsLocate(world, 0, 0);
            throw new AssertionError("Expected layout failure");
        } catch (ProbeFailure expected) {
            if (failure.random() != original || failure.boundWorld() != world) throw new AssertionError("Failure leaked state");
        }
        int after = world.getChunkProvider().getLoadedChunkCount();
        if (before != after) throw new AssertionError("Locate loaded chunks " + before + " -> " + after);
        lines.add("PASS loadedChunks=" + before + "->" + after + "; RNG restored; no predicted index; legacy retained; exception restored; Stronghold/Mineshaft layout NBT matches generation; unexplored filter");
        Files.write(Path.of("c53-locate-results.txt"), lines);
    }

    private static final class StrongholdFixture extends MapGenStronghold {
        private NBTTagCompound lastLayout;
        StrongholdFixture(World world) { this.world = world; }
        @Override public String getStructureName() { return "C53StrongholdFixture"; }
        @Override protected StructureStart getStructureStart(int x, int z) {
            var start = super.getStructureStart(x, z);
            lastLayout = start.writeStructureComponentsToNBT(x, z);
            return start;
        }
        void verifyPreparedLayout(World world, BlockPos pos) {
            var predicted = lastLayout.copy();
            range = 0;
            generate(world, pos.getX() >> 4, pos.getZ() >> 4, null);
            if (!predicted.equals(lastLayout) || !structureMap.containsKey(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
                throw new AssertionError("Stronghold prediction diverged from actual preparation");
            }
        }
        Random random() { return rand; }
        int indexSize() { return structureMap.size(); }
        void installLegacy(int x, int z) { structureMap.put(ChunkPos.asLong(x, z), new StructureStart(x, z) {}); }
    }
    private static final class MineshaftFixture extends MapGenMineshaft {
        private NBTTagCompound lastLayout;
        MineshaftFixture(World world) { this.world = world; }
        @Override public String getStructureName() { return "C53MineshaftFixture"; }
        @Override protected StructureStart getStructureStart(int x, int z) {
            var start = super.getStructureStart(x, z);
            lastLayout = start.writeStructureComponentsToNBT(x, z);
            return start;
        }
        void verifyPreparedLayout(World world, BlockPos pos) {
            var predicted = lastLayout.copy();
            range = 0;
            generate(world, pos.getX() >> 4, pos.getZ() >> 4, null);
            if (!predicted.equals(lastLayout) || !structureMap.containsKey(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
                throw new AssertionError("Mineshaft prediction diverged from actual preparation");
            }
        }
    }
    private static final class ProbeFailure extends RuntimeException {}
    private static final class FailureFixture extends MapGenStructure {
        FailureFixture(World world) { this.world = world; }
        Random random() { return rand; }
        World boundWorld() { return world; }
        @Override public String getStructureName() { return "C53FailureFixture"; }
        @Override protected boolean canSpawnStructureAtCoords(int x, int z) { return true; }
        @Override protected StructureStart getStructureStart(int x, int z) { throw new ProbeFailure(); }
        @Override public BlockPos getNearestStructurePos(World world, BlockPos pos, boolean unexplored) { return null; }
    }
}
