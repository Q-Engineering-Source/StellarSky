package stellarium.world.ring.terrain;

import java.util.Objects;

/** Immutable per-column evidence, with a cache-owned monotonically increasing publication revision. */
public final class TerrainTileSnapshot {
    private static final TerrainColumnState[] STATES = TerrainColumnState.values();
    private final TerrainTileKey key;
    private final long revision;
    private final byte[] states;

    TerrainTileSnapshot(TerrainTileKey key) {
        this.key = key;
        revision = 0;
        states = new byte[TerrainTileKey.COLUMN_COUNT];
    }

    TerrainTileSnapshot(TerrainTileSnapshot previous, long revision, TerrainColumnState[] incoming) {
        Objects.requireNonNull(incoming, "incoming");
        if (incoming.length != TerrainTileKey.COLUMN_COUNT) throw new IllegalArgumentException("Expected 4096 columns");
        key = previous.key;
        this.revision = revision;
        states = new byte[incoming.length];
        for (int i = 0; i < states.length; i++) {
            states[i] = (byte) previous.column(i).retainEvidence(Objects.requireNonNull(incoming[i], "column")).ordinal();
        }
    }

    public TerrainTileKey key() { return key; }
    public long revision() { return revision; }
    public TerrainColumnState column(int index) { return STATES[states[index]]; }
}
