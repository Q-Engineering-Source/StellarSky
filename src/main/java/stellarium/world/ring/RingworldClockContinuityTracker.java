package stellarium.world.ring;

/**
 * Scene-owned provenance for published clock endpoints. It deliberately
 * prefers a discontinuity whenever a write cannot be proved to be one normal
 * world-time mutation.
 */
public final class RingworldClockContinuityTracker {
    private Observation committed;
    private Observation knownAdvance;
    private boolean stickyBreak;
    private int writeDepth;
    private long stateVersion;
    private KnownWrite activeWrite;
    private Publication activePublication;

    public KnownWrite beginKnownWrite(Observation before) {
        if (activeWrite != null || writeDepth != 0 || !matchesKnownOrCommitted(before)) {
            stickyBreak = true;
            stateVersion++;
            return new KnownWrite(before, writeDepth);
        }
        stateVersion++;
        writeDepth++;
        activeWrite = new KnownWrite(this, before, writeDepth);
        return activeWrite;
    }

    public void finishKnownWrite(KnownWrite write, long targetTime, Observation after,
                                 boolean arithmeticWrapped) {
        try {
            if (!isActive(write) || writeDepth != 1
                    || arithmeticWrapped || after.saturated()
                    || after.worldTime() != targetTime
                    || !advancedExactlyOnce(write.before(), after)) {
                stickyBreak = true;
                return;
            }
            knownAdvance = after;
        } finally {
            if (!isActive(write) || writeDepth == 0) {
                stickyBreak = true;
            } else {
                writeDepth--;
                activeWrite = null;
                write.consume();
            }
            stateVersion++;
        }
    }

    public void abortKnownWrite(KnownWrite write) {
        stickyBreak = true;
        if (!isActive(write) || writeDepth == 0) {
            stickyBreak = true;
        } else {
            writeDepth--;
            activeWrite = null;
            write.consume();
        }
        stateVersion++;
    }

    /** A frame and observed endpoint disagreed; do not consume this window. */
    public void markUnproven() {
        stickyBreak = true;
        stateVersion++;
    }

    public Publication previewPublication(Observation current) {
        if (writeDepth != 0 || current.saturated()) {
            stickyBreak = true;
        }
        if (committed == null) {
            return issuePublication(current, true);
        }
        boolean unchanged = sameEndpoint(committed, current);
        boolean verifiedAdvance = knownAdvance != null && sameEndpoint(knownAdvance, current);
        if (!unchanged && !verifiedAdvance) {
            stickyBreak = true;
        }
        return issuePublication(current, stickyBreak);
    }

    /** Only call after the publisher has successfully constructed its sample. */
    public void commitPublication(Publication publication) {
        if (publication == null || publication != activePublication || publication.owner != this
                || publication.consumed || publication.stateVersion() != stateVersion || writeDepth != 0) {
            stickyBreak = true;
            activePublication = null;
            stateVersion++;
            return;
        }
        committed = publication.observation();
        knownAdvance = null;
        stickyBreak = false;
        activePublication = null;
        publication.consume();
    }

    private boolean matchesKnownOrCommitted(Observation current) {
        return committed == null || sameEndpoint(committed, current)
                || knownAdvance != null && sameEndpoint(knownAdvance, current);
    }

    private boolean isActive(KnownWrite write) {
        return write != null && write == activeWrite && write.owner == this && !write.consumed;
    }

    private Publication issuePublication(Observation observation, boolean discontinuousBefore) {
        if (activePublication != null) {
            stickyBreak = true;
        }
        Publication issued = new Publication(this, observation, discontinuousBefore || stickyBreak, stateVersion);
        activePublication = issued;
        return issued;
    }

    private static boolean advancedExactlyOnce(Observation before, Observation after) {
        return !before.saturated() && !after.saturated()
                && before.revision() != Long.MAX_VALUE
                && after.revision() == before.revision() + 1L;
    }

    private static boolean sameEndpoint(Observation left, Observation right) {
        return left.worldTime() == right.worldTime() && left.revision() == right.revision()
                && left.saturated() == right.saturated();
    }

    public record Observation(long worldTime, long revision, boolean saturated) {
    }

    public static final class KnownWrite {
        private final RingworldClockContinuityTracker owner;
        private final Observation before;
        private final int depth;
        private boolean consumed;

        private KnownWrite(Observation before, int depth) { this(null, before, depth); }
        private KnownWrite(RingworldClockContinuityTracker owner, Observation before, int depth) {
            this.owner = owner; this.before = before; this.depth = depth;
        }
        public Observation before() { return before; }
        public int depth() { return depth; }
        private void consume() { consumed = true; }
    }

    public static final class Publication {
        private final RingworldClockContinuityTracker owner;
        private final Observation observation;
        private final boolean discontinuousBefore;
        private final long stateVersion;
        private boolean consumed;

        private Publication(RingworldClockContinuityTracker owner, Observation observation,
                            boolean discontinuousBefore, long stateVersion) {
            this.owner = owner; this.observation = observation;
            this.discontinuousBefore = discontinuousBefore; this.stateVersion = stateVersion;
        }
        public Observation observation() { return observation; }
        public boolean discontinuousBefore() { return discontinuousBefore; }
        public long stateVersion() { return stateVersion; }
        private void consume() { consumed = true; }
    }
}
