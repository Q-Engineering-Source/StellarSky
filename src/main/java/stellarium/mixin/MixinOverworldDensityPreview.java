package stellarium.mixin;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import stellarium.world.ring.terrain.OverworldDensityLattice;
import stellarium.world.ring.terrain.OverworldDensitySource;
import stellarium.world.ring.terrain.OverworldColumnSampler;
import stellarium.world.ring.terrain.OverworldColumnSource;
import stellarium.world.ring.terrain.OverworldFingerprintSource;
import stellarium.world.ring.terrain.OverworldPreviewFingerprint;
import stellarium.world.ring.terrain.PreviewNoiseState;
import stellarium.world.ring.terrain.VanillaBiomeFingerprint;

/** Reuses actual seed/settings/noise. No secondary generator construction or ChunkPrimer allocation. */
@Mixin(ChunkGeneratorOverworld.class)
public abstract class MixinOverworldDensityPreview implements OverworldDensitySource, OverworldFingerprintSource,
        OverworldColumnSource {
    @Shadow @Final private World world;
    @Shadow @Final private double[] heightMap;
    @Shadow @Final private float[] biomeWeights;
    @Shadow @Final private WorldType terrainType;
    @Shadow private ChunkGeneratorSettings settings;
    @Shadow private Biome[] biomesForGeneration;
    @Shadow private NoiseGeneratorOctaves minLimitPerlinNoise;
    @Shadow private NoiseGeneratorOctaves maxLimitPerlinNoise;
    @Shadow private NoiseGeneratorOctaves mainPerlinNoise;
    @Shadow public NoiseGeneratorOctaves depthNoise;
    @Shadow double[] mainNoiseRegion;
    @Shadow double[] minLimitRegion;
    @Shadow double[] maxLimitRegion;
    @Shadow double[] depthRegion;
    @Shadow protected abstract void generateHeightmap(int x, int y, int z);
    @Unique private boolean stellarium$samplingDensity;

    @Override public byte[] stellarium$capturePreviewFingerprint(byte[] admittedPipelineRevision) {
        stellarium$requireDensityContext();
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(OverworldPreviewFingerprint.capture(world.getSeed(), world.getSeaLevel(),
                    terrainType == WorldType.AMPLIFIED, settings, admittedPipelineRevision));
            digest.update(VanillaBiomeFingerprint.capture(world.getBiomeProvider()));
            for(var noise:new NoiseGeneratorOctaves[]{minLimitPerlinNoise,maxLimitPerlinNoise,mainPerlinNoise,depthNoise})
                ((PreviewNoiseState)noise).stellarium$appendNoiseState(digest);
            if(biomeWeights.length!=25)throw new UnsupportedOperationException("Unadmitted biome weights");
            var weights=ByteBuffer.allocate(25*4);
            for(float weight:biomeWeights) {
                if(!Float.isFinite(weight))throw new IllegalStateException("Non-finite biome weight");
                weights.putFloat(weight);
            }
            digest.update(weights.array()); return digest.digest();
        } catch(NoSuchAlgorithmException impossible) {throw new ExceptionInInitializerError(impossible);}
    }

    @Unique private void stellarium$requireDensityContext() {
        if (!(world instanceof WorldServer) || world.getMinecraftServer() == null
                || !world.getMinecraftServer().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Seed preview must run on the authoritative server thread");
        }
        if (((Object)this).getClass() != ChunkGeneratorOverworld.class || world.getHeight() != 256
                || minLimitPerlinNoise.getClass() != NoiseGeneratorOctaves.class
                || maxLimitPerlinNoise.getClass() != NoiseGeneratorOctaves.class
                || mainPerlinNoise.getClass() != NoiseGeneratorOctaves.class
                || depthNoise.getClass() != NoiseGeneratorOctaves.class) {
            throw new UnsupportedOperationException("Unverified Overworld generator/noise/height for seed preview");
        }
        if (stellarium$samplingDensity) throw new IllegalStateException("Reentrant seed preview");
    }

    @Override public OverworldColumnSampler.Context stellarium$createColumnContext() {
        stellarium$requireDensityContext();
        var privateNoise = OverworldColumnSampler.Noises.fromSeed(world.getSeed());
        var actual = new NoiseGeneratorOctaves[]{minLimitPerlinNoise,maxLimitPerlinNoise,mainPerlinNoise,depthNoise};
        var copy = new NoiseGeneratorOctaves[]{privateNoise.minimum(),privateNoise.maximum(),privateNoise.main(),privateNoise.depth()};
        try {
            for (int i = 0; i < actual.length; i++) {
                var original = MessageDigest.getInstance("SHA-256");
                var recreated = MessageDigest.getInstance("SHA-256");
                ((PreviewNoiseState)actual[i]).stellarium$appendNoiseState(original);
                ((PreviewNoiseState)copy[i]).stellarium$appendNoiseState(recreated);
                if (!MessageDigest.isEqual(original.digest(),recreated.digest()))
                    throw new UnsupportedOperationException("Overworld noise state differs from seed-reconstructed worker state");
            }
        } catch (NoSuchAlgorithmException impossible) { throw new ExceptionInInitializerError(impossible); }
        return new OverworldColumnSampler.Context(privateNoise,OverworldColumnSampler.Settings.capture(settings),
                biomeWeights,world.getSeaLevel(),terrainType==WorldType.AMPLIFIED);
    }

    @Override public OverworldColumnSampler.Biomes stellarium$captureColumnBiomes(int chunkX,int chunkZ) {
        stellarium$requireDensityContext();
        int x=Math.multiplyExact(chunkX,4),z=Math.multiplyExact(chunkZ,4);
        int biomeX=Math.subtractExact(x,2),biomeZ=Math.subtractExact(z,2);
        Math.addExact(biomeX,10);Math.addExact(biomeZ,10);
        return OverworldColumnSampler.Biomes.fromVanillaWindow(
                world.getBiomeProvider().getBiomesForGeneration(null,biomeX,biomeZ,10,10));
    }

    @Override public OverworldDensityLattice stellarium$sampleDensity(int chunkX, int chunkZ) {
        stellarium$requireDensityContext();
        int x = Math.multiplyExact(chunkX,4), z = Math.multiplyExact(chunkZ,4);
        int biomeX = Math.subtractExact(x,2), biomeZ = Math.subtractExact(z,2);
        Math.addExact(biomeX,10); Math.addExact(biomeZ,10);
        var oldBiomes = biomesForGeneration;
        var oldHeightMap = heightMap.clone();
        var oldMain = mainNoiseRegion; var oldMin = minLimitRegion;
        var oldMax = maxLimitRegion; var oldDepth = depthRegion;
        stellarium$samplingDensity = true;
        try {
            // Null reuse prevents the biome provider from overwriting the generator's previous input.
            biomesForGeneration = world.getBiomeProvider().getBiomesForGeneration(null,biomeX,biomeZ,10,10);
            mainNoiseRegion = null; minLimitRegion = null; maxLimitRegion = null; depthRegion = null;
            generateHeightmap(x,0,z);
            return new OverworldDensityLattice(chunkX,chunkZ,world.getSeaLevel(),heightMap);
        } finally {
            System.arraycopy(oldHeightMap,0,heightMap,0,heightMap.length);
            biomesForGeneration = oldBiomes;
            mainNoiseRegion = oldMain; minLimitRegion = oldMin; maxLimitRegion = oldMax; depthRegion = oldDepth;
            stellarium$samplingDensity = false;
        }
    }
}
