package stellarium.world.ring.terrain;

/** Seed-derived approximation only; never a source of REAL chunk/LOD evidence. */
@FunctionalInterface
public interface SeedTerrainSource {
    SeedTerrainChunk sample(int chunkX, int chunkZ);
}
