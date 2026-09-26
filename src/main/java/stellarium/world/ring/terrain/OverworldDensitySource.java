package stellarium.world.ring.terrain;

/** Implemented only by the admitted vanilla Overworld generator Mixin; server-thread operation. */
public interface OverworldDensitySource {
    OverworldDensityLattice stellarium$sampleDensity(int chunkX, int chunkZ);
}
