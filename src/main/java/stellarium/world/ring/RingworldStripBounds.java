package stellarium.world.ring;

/**
 * Fixed first-release Z intervals for the finite ringworld strip.
 *
 * <p>The board is half-open so adjacent terrain, boundary construction, and
 * void generators can share exact block and chunk boundaries without overlap.
 * The two construction bands are deliberately classified here, but this class
 * does not create their blocks or a DUT black wall.</p>
 */
public final class RingworldStripBounds {
    public static final int BOARD_MIN_Z = -8_192;
    public static final int BOARD_MAX_Z_EXCLUSIVE = 8_192;
    public static final int BOUNDARY_BAND_CHUNKS = 2;
    public static final int BOUNDARY_BAND_WIDTH_BLOCKS = BOUNDARY_BAND_CHUNKS * 16;
    public static final int NEGATIVE_BOUNDARY_MIN_Z = BOARD_MIN_Z - BOUNDARY_BAND_WIDTH_BLOCKS;
    public static final int POSITIVE_BOUNDARY_MAX_Z_EXCLUSIVE = BOARD_MAX_Z_EXCLUSIVE + BOUNDARY_BAND_WIDTH_BLOCKS;

    private RingworldStripBounds() {
    }

    /** Returns whether an exact receiver coordinate lies on the playable board. */
    public static boolean insideBoard(double z) {
        return z >= BOARD_MIN_Z && z < BOARD_MAX_Z_EXCLUSIVE;
    }

    /** Classifies a block Z coordinate for the future board/wall/void generator. */
    public static StripRegion classifyBlockZ(int blockZ) {
        return classifyZ(blockZ);
    }

    /** Classifies an aligned 16-block Minecraft chunk by its Z coordinate. */
    public static StripRegion classifyChunkZ(int chunkZ) {
        return classifyZ((long) chunkZ * 16L);
    }

    private static StripRegion classifyZ(long z) {
        if (z >= BOARD_MIN_Z && z < BOARD_MAX_Z_EXCLUSIVE) {
            return StripRegion.BOARD;
        }
        if (z >= NEGATIVE_BOUNDARY_MIN_Z && z < BOARD_MIN_Z) {
            return StripRegion.NEGATIVE_BOUNDARY_BAND;
        }
        if (z >= BOARD_MAX_Z_EXCLUSIVE && z < POSITIVE_BOUNDARY_MAX_Z_EXCLUSIVE) {
            return StripRegion.POSITIVE_BOUNDARY_BAND;
        }
        return StripRegion.VOID;
    }

    public enum StripRegion {
        BOARD,
        NEGATIVE_BOUNDARY_BAND,
        POSITIVE_BOUNDARY_BAND,
        VOID
    }
}
