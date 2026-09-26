package stellarium.world.ring.generation;

import java.util.List;
import java.util.Random;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import stellarium.StellarSkyReferences;

/** Natural-generation exclusion, not a restriction on player/machine block placement. */
public final class SpaceBiome extends Biome {
    public SpaceBiome() {
        super(new BiomeProperties("Space").setRainDisabled().setRainfall(0.0F));
        setRegistryName(new ResourceLocation(StellarSkyReferences.MODID, "space"));
        topBlock = Blocks.AIR.getDefaultState();
        fillerBlock = Blocks.AIR.getDefaultState();
        spawnableMonsterList.clear();
        spawnableCreatureList.clear();
        spawnableWaterCreatureList.clear();
        spawnableCaveCreatureList.clear();
        modSpawnableLists.clear();
    }

    public static boolean suppressesNaturalGeneration(Biome biome) {
        return biome instanceof SpaceBiome;
    }

    @Override public List<SpawnListEntry> getSpawnableList(EnumCreatureType type) { return List.of(); }

    @Override public void decorate(World world, Random random, BlockPos pos) {
        // Space intentionally has no natural decoration.
    }

    @Override public void genTerrainBlocks(World world, Random random, ChunkPrimer primer, int x, int z, double noise) {
        // The generator must omit base density too; this hook alone cannot remove base stone.
    }
}
