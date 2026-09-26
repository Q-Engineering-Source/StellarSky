package stellarium.world.ring.generation;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.IChunkGenerator;
import stellarium.StellarSky;
import stellarium.mixin.WorldProviderBiomeAccess;

public final class RingworldGenerationRuntime {
    private RingworldGenerationRuntime() {}

    public static IChunkGenerator create(WorldServer world, WorldProvider provider) {
        var config = StellarSky.INSTANCE.getGenerationConfig();
        try {
            return RingworldGenerationBootstrap.create(world, config.requests(provider.getDimension()), settings -> {
                if (!settings.enabled()) return provider.createChunkGenerator();
                BiomeProvider original = provider.getBiomeProvider();
                if (provider.getDimension() != 0 || provider.getClass() != WorldProviderSurface.class
                        || world.getWorldInfo().getTerrainType() != WorldType.DEFAULT
                        || original.getClass() != BiomeProvider.class) {
                    throw new IllegalStateException("Ring generation currently supports only the vanilla DEFAULT Overworld provider");
                }
                var biomes = new RingworldBiomeProvider(original, SpaceBiomeRegistry.space());
                var access = (WorldProviderBiomeAccess) provider;
                access.stellarium$setGenerationBiomes(biomes);
                try {
                    IChunkGenerator delegate = provider.createChunkGenerator();
                    if (delegate.getClass() != ChunkGeneratorOverworld.class) {
                        throw new IllegalStateException("Unsupported ring terrain generator: " + delegate.getClass().getName());
                    }
                    StellarSky.INSTANCE.getLogger().info("Ring generation activated: dimension={}, provider={}, generator={}, biomeProvider={}",
                            provider.getDimension(), provider.getClass().getName(), delegate.getClass().getName(), original.getClass().getName());
                    return RingworldChunkGenerator.wrap(world, delegate, biomes, true);
                } catch (RuntimeException | Error failure) {
                    access.stellarium$setGenerationBiomes(original);
                    throw failure;
                }
            });
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot confirm ring generation policy before generator creation", failure);
        }
    }
}
