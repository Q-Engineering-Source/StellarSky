package stellarium.world.ring.generation;

import stellarium.world.ring.RingworldStripBounds;

/** Pure first-release generation admission; deliberately not a general block-placement guard. */
public final class RingworldGenerationPolicy {
    private static final int CHUNK_WIDTH = 16;
    public static final int STRUCTURE_MARGIN_CHUNKS = 5;
    private static final int STRUCTURE_MARGIN_BLOCKS = STRUCTURE_MARGIN_CHUNKS * CHUNK_WIDTH;

    public boolean generatesTerrain(int chunkZ) {
        long minZ = (long) chunkZ * CHUNK_WIDTH;
        return minZ >= RingworldStripBounds.BOARD_MIN_Z
                && minZ + CHUNK_WIDTH <= RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE;
    }

    public boolean allowsStructureStart(int chunkZ) {
        long minZ = (long) chunkZ * CHUNK_WIDTH;
        return minZ >= RingworldStripBounds.BOARD_MIN_Z + STRUCTURE_MARGIN_BLOCKS
                && minZ + CHUNK_WIDTH <= RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE - STRUCTURE_MARGIN_BLOCKS;
    }

    /** Minecraft structure boxes have inclusive maxima; the playable strip does not. */
    public boolean containsStructure(int minZ, int maxZInclusive) {
        if (minZ > maxZInclusive) {
            throw new IllegalArgumentException("Structure minimum Z exceeds its maximum");
        }
        return minZ >= RingworldStripBounds.BOARD_MIN_Z
                && maxZInclusive < RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE;
    }
}
