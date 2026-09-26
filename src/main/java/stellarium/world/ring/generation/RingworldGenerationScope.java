package stellarium.world.ring.generation;

import java.util.Objects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Thread-confined natural-generation scope; ordinary block placement has no active scope. */
public final class RingworldGenerationScope implements AutoCloseable {
    private static final ThreadLocal<RingworldGenerationScope> CURRENT = new ThreadLocal<>();
    private final World world;
    private final RingworldBiomeProvider biomes;
    private final Thread owner;
    private final RingworldGenerationScope parent;

    private RingworldGenerationScope(World world, RingworldBiomeProvider biomes) {
        this.world = Objects.requireNonNull(world, "world");
        this.biomes = Objects.requireNonNull(biomes, "biomes");
        owner = Thread.currentThread();
        parent = CURRENT.get();
        CURRENT.set(this);
    }

    public static RingworldGenerationScope open(World world, RingworldBiomeProvider biomes) {
        return new RingworldGenerationScope(world, biomes);
    }

    public static boolean suppresses(World world, BlockPos pos) {
        for (var scope = CURRENT.get(); scope != null; scope = scope.parent) {
            if (scope.world == world) {
                return SpaceBiome.suppressesNaturalGeneration(scope.biomes.getBiome(pos));
            }
        }
        return false;
    }

    @Override public void close() {
        if (Thread.currentThread() != owner || CURRENT.get() != this) {
            throw new IllegalStateException("Natural generation scopes must close once, in order, on their owner thread");
        }
        if (parent == null) CURRENT.remove();
        else CURRENT.set(parent);
    }
}
