package stellarium.client.ring;

/**
 * Stable, CPU-testable identity and visibility policy for ringworld board
 * maintenance dots. All IDs are deliberately periodic, bounded integers so
 * the GLSL 1.20 implementation can perform the same hash exactly enough
 * without bit operations or world-scale float coordinates.
 */
final class RingworldBoardDotOutage {
    static final int CELL_PERIOD = 4093;
    static final double MAX_CYCLE_BLOCKS = 4_194_304.0;
    static final int FACE_UNDERSIDE = 2;
    static final int FACE_NEGATIVE_STRIP_SIDE = 3;
    static final int FACE_PANEL_LEADING_SIDE = 4;
    static final int FACE_POSITIVE_STRIP_SIDE = 5;
    static final int FACE_PANEL_TRAILING_SIDE = 6;
    // Compatibility aliases for existing callers/tests that did not yet need
    // to distinguish the two physical wall faces.
    static final int FACE_STRIP_SIDE = FACE_NEGATIVE_STRIP_SIDE;
    static final int FACE_PANEL_SIDE = FACE_PANEL_LEADING_SIDE;

    private RingworldBoardDotOutage() {
    }

    static DotId dotId(double materialXBlocks, double materialZBlocks, double pitchBlocks,
                       long panelIndex, int face) {
        requirePitch(pitchBlocks);
        return new DotId(nearestCellResidue(materialXBlocks, pitchBlocks),
                nearestCellResidue(materialZBlocks, pitchBlocks),
                (int) Math.floorMod(panelIndex, (long) CELL_PERIOD), requireFace(face));
    }

    /** The strip-side row has one material axis; its fixed Z is intentionally not part of the ID. */
    static DotId stripSideDotId(double materialAlongBlocks, double pitchBlocks, long panelIndex) {
        return stripSideDotId(materialAlongBlocks, pitchBlocks, panelIndex, false);
    }

    static DotId stripSideDotId(double materialAlongBlocks, double pitchBlocks, long panelIndex,
                                boolean positiveZFace) {
        return new DotId(nearestCellResidue(materialAlongBlocks, pitchBlocks), 0,
                (int) Math.floorMod(panelIndex, (long) CELL_PERIOD),
                positiveZFace ? FACE_POSITIVE_STRIP_SIDE : FACE_NEGATIVE_STRIP_SIDE);
    }

    /** The panel-side row likewise hashes only its actual horizontal face axis. */
    static DotId panelSideDotId(double materialAlongBlocks, double pitchBlocks, long panelIndex) {
        return panelSideDotId(materialAlongBlocks, pitchBlocks, panelIndex, false);
    }

    static DotId panelSideDotId(double materialAlongBlocks, double pitchBlocks, long panelIndex,
                                boolean trailingFace) {
        return new DotId(nearestCellResidue(materialAlongBlocks, pitchBlocks), 0,
                (int) Math.floorMod(panelIndex, (long) CELL_PERIOD),
                trailingFace ? FACE_PANEL_TRAILING_SIDE : FACE_PANEL_LEADING_SIDE);
    }

    static boolean isLit(DotId dotId, float offProbability, int seed) {
        if (dotId == null) {
            throw new NullPointerException("dotId");
        }
        requireProbability(offProbability);
        return offProbability == 0.0f || (offProbability < 1.0f && sample(dotId, seed) >= offProbability);
    }

    /** Returns the deterministic pseudo-random value used by both CPU tests and board.frag. */
    static float sample(DotId dotId, int seed) {
        if (dotId == null) {
            throw new NullPointerException("dotId");
        }
        int state = Math.floorMod(dotId.cellX() + seedResidue(seed) * 17 + 19, CELL_PERIOD);
        int fold = Math.floorMod(state + dotId.cellZ() * 7, 97);
        state = Math.floorMod(state * 83 + fold * fold * 17 + dotId.cellZ() * 31
                + dotId.panelResidue() * 13 + dotId.face() * 61, CELL_PERIOD);
        fold = Math.floorMod(state + dotId.panelResidue() * 11, 89);
        state = Math.floorMod(state * 71 + fold * fold * 19 + dotId.cellX() * 29 + dotId.face() * 131,
                CELL_PERIOD);
        fold = Math.floorMod(state + seedResidue(seed), 83);
        state = Math.floorMod(state * 59 + fold * fold * 23 + dotId.cellZ() * 47 + 19, CELL_PERIOD);
        return state / (float) CELL_PERIOD;
    }

    /**
     * Slow local visual pulse. It intentionally derives from total world time,
     * not daylight phase: pausing freezes it and a later world/reconnect resumes
     * at that world's current total-time phase. It never changes server state.
     */
    static float pulseBrightness(boolean enabled, long totalWorldTicks, float partialTicks, float periodSeconds) {
        if (!Float.isFinite(partialTicks) || partialTicks < 0.0f || partialTicks > 1.0f) {
            throw new IllegalArgumentException("partialTicks must be finite in [0, 1]");
        }
        if (!enabled) {
            return 1.0f;
        }
        if (!Float.isFinite(periodSeconds) || periodSeconds <= 0.0f) {
            throw new IllegalArgumentException("periodSeconds must be finite and positive when pulse is enabled");
        }
        // The visual clock has tick resolution. Rounding keeps the modulo on a
        // long and never widens a potentially old world time into a lossy float.
        long periodTicks = Math.max(1L, Math.round(periodSeconds * 20.0f));
        long tickWithinPeriod = Math.floorMod(totalWorldTicks, periodTicks);
        double phase = (tickWithinPeriod + partialTicks) / periodTicks;
        // A gentle 0.65..1.00 breathing effect: it cannot revive an off dot.
        return (float) (0.825 + 0.175 * StrictMath.sin(phase * StrictMath.PI * 2.0));
    }

    static int seedResidue(int seed) {
        return Math.floorMod(seed, CELL_PERIOD);
    }

    /** Nearest-center cell identity, matching board.frag at a lattice boundary. */
    static int nearestCellResidue(double materialCoordinateBlocks, double pitchBlocks) {
        requirePitch(pitchBlocks);
        if (!Double.isFinite(materialCoordinateBlocks)) {
            throw new IllegalArgumentException("materialCoordinateBlocks must be finite");
        }
        double cycleBlocks = pitchBlocks * CELL_PERIOD;
        double coordinate = positiveModulo(materialCoordinateBlocks, cycleBlocks);
        int cell = (int) StrictMath.floor((coordinate + pitchBlocks * 0.5) / pitchBlocks);
        return Math.floorMod(cell, CELL_PERIOD);
    }

    static double cycleBlocks(double pitchBlocks) {
        requirePitch(pitchBlocks);
        return pitchBlocks * CELL_PERIOD;
    }

    static double positiveModulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }

    private static void requirePitch(double pitchBlocks) {
        if (!Double.isFinite(pitchBlocks) || pitchBlocks <= 0.0
                || pitchBlocks * CELL_PERIOD >= MAX_CYCLE_BLOCKS) {
            throw new IllegalArgumentException("pitchBlocks must keep the GLSL cell cycle finite and safely exact");
        }
    }

    private static void requireProbability(float offProbability) {
        if (!Float.isFinite(offProbability) || offProbability < 0.0f || offProbability > 1.0f) {
            throw new IllegalArgumentException("offProbability must be finite in [0, 1]");
        }
    }

    private static int requireFace(int face) {
        if (face < FACE_UNDERSIDE || face > FACE_PANEL_TRAILING_SIDE) {
            throw new IllegalArgumentException("Unknown board dot face " + face);
        }
        return face;
    }

    record DotId(int cellX, int cellZ, int panelResidue, int face) {
        DotId {
            if (cellX < 0 || cellX >= CELL_PERIOD || cellZ < 0 || cellZ >= CELL_PERIOD
                    || panelResidue < 0 || panelResidue >= CELL_PERIOD) {
                throw new IllegalArgumentException("Dot ID components must be bounded cell residues");
            }
            requireFace(face);
        }
    }
}
