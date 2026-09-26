package stellarium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Random;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.structure.MapGenStructure;
import net.minecraft.world.gen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import stellarium.world.ring.generation.RingworldBiomeProvider;
import stellarium.world.ring.generation.RingworldStructureAdmission;
import stellarium.world.ring.generation.RingworldStructureLookup;

@Mixin(MapGenStructure.class)
public abstract class MixinMapGenStructureSpace extends MapGenBase implements RingworldStructureLookup {
    @Shadow protected Long2ObjectMap<StructureStart> structureMap;
    @Shadow protected abstract void initializeStructureData(World world);
    @Shadow protected abstract boolean canSpawnStructureAtCoords(int x, int z);
    @Shadow protected abstract StructureStart getStructureStart(int x, int z);

    @Override public synchronized boolean stellarium$allowsLocate(World lookupWorld, int x, int z) {
        if (!(lookupWorld.getBiomeProvider() instanceof RingworldBiomeProvider biomes)) {
            return canSpawnStructureAtCoords(x, z);
        }
        initializeStructureData(lookupWorld);
        // Replay MapGenBase.generate + recursiveGenerate without publishing a start or touching chunks.
        // Swap the RNG object so its caller's exact state survives success and exceptions.
        Random previousRandom = rand;
        World previousWorld = world;
        try {
            world = lookupWorld;
            rand = new Random();
            setupChunkSeed(lookupWorld.getSeed(), rand, x, z);
            rand.nextInt();
            return RingworldStructureLookup.allows(biomes, x, z, structureMap.get(ChunkPos.asLong(x, z)),
                    () -> canSpawnStructureAtCoords(x, z), () -> getStructureStart(x, z));
        } finally {
            rand = previousRandom;
            world = previousWorld;
        }
    }

    @Redirect(method = "findNearestStructurePosBySpacing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/structure/MapGenStructure;canSpawnStructureAtCoords(II)Z"),
            require = 1, allow = 1)
    private static boolean stellarium$admitSpacingCandidate(MapGenStructure generator, int x, int z,
            World world, MapGenStructure structureType, BlockPos origin, int distance, int offset,
            int salt, boolean extraRandomness, int attempts, boolean unexplored) {
        return ((RingworldStructureLookup) generator).stellarium$allowsLocate(world, x, z);
    }
    // Vanilla has loaded its persisted map and excluded existing starts before this call.
    @Inject(method = "recursiveGenerate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/structure/MapGenStructure;canSpawnStructureAtCoords(II)Z"),
            cancellable = true, require = 1, allow = 1)
    private void stellarium$admitNewStart(World world, int x, int z, int originalX, int originalZ,
                                        ChunkPrimer primer, CallbackInfo ci) {
        if (world.getBiomeProvider() instanceof RingworldBiomeProvider biomes
                && !RingworldStructureAdmission.allowsStart(biomes, x, z)) ci.cancel();
    }

    @Inject(method = "recursiveGenerate", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;", remap = false),
            locals = LocalCapture.CAPTURE_FAILHARD, cancellable = true, require = 1, allow = 1)
    private void stellarium$admitComponentsBeforeIndex(World world, int x, int z, int originalX, int originalZ,
                                                     ChunkPrimer primer, CallbackInfo ci, StructureStart start) {
        if (world.getBiomeProvider() instanceof RingworldBiomeProvider biomes
                && !RingworldStructureAdmission.allowsNewStructure(biomes, start)) ci.cancel();
    }
}
