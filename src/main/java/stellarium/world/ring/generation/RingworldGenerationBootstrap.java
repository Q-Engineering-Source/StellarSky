package stellarium.world.ring.generation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.world.WorldServer;

/** Generator-construction boundary: no factory invocation until the policy has been confirmed. */
public final class RingworldGenerationBootstrap {
    private RingworldGenerationBootstrap() {}

    /**
     * Intended for WorldServer.createChunkProvider, inherited by WorldServerMulti.
     * No MapStorage, StellarScene, subclass delegate or post-init capability is required.
     */
    public static <T> T create(WorldServer world, boolean requested,
                               Function<RingworldGenerationSettings, T> generatorFactory) throws IOException {
        Objects.requireNonNull(world, "world");
        if (world.isRemote) {
            throw new IllegalArgumentException("Generation bootstrap requires the server world");
        }
        return create(world.getSaveHandler().getWorldDirectory().toPath(), world.provider.getDimension(),
                world.getWorldInfo().isInitialized(), requested, generatorFactory);
    }

    public static <T> T create(Path worldDirectory, int dimension, boolean alreadyInitialized, boolean requested,
                               Function<RingworldGenerationSettings, T> generatorFactory) throws IOException {
        Objects.requireNonNull(generatorFactory, "generatorFactory");
        var settings = RingworldGenerationPolicyStore.loadOrCreate(worldDirectory, dimension, alreadyInitialized, requested);
        return Objects.requireNonNull(generatorFactory.apply(settings), "generatorFactory result");
    }
}
