package stellarium.world.ring.terrain;

/** Data evidence only. REAL does not imply an uploaded or selected drawable buffer. */
public enum TerrainColumnState {
    UNKNOWN, BOUNDARY_EMPTY, REAL_AIR, REAL_SOLID;

    public boolean isReal() { return this == REAL_AIR || this == REAL_SOLID; }

    TerrainColumnState retainEvidence(TerrainColumnState incoming) {
        if (incoming.isReal()) return incoming;
        if (isReal() || incoming == UNKNOWN) return this;
        return incoming;
    }
}
