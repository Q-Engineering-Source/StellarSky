package stellarium.world.ring.terrain;

/** Server-owner snapshot boundary; a worker receives only copied scalar inputs and private noise. */
public interface OverworldColumnSource {
    OverworldColumnSampler.Context stellarium$createColumnContext();
    OverworldColumnSampler.Biomes stellarium$captureColumnBiomes(int chunkX, int chunkZ);
}
