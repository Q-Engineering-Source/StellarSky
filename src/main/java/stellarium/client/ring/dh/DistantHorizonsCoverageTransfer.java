package stellarium.client.ring.dh;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Buffer-owned input identity. None of these types proves draw submission or authorizes handoff. */
public final class DistantHorizonsCoverageTransfer {
    private DistantHorizonsCoverageTransfer() {}

    /** Tree invalidation is immediate even when DH schedules individual section cleanup asynchronously. */
    public static final class Generation {
        private volatile long revision;
        private volatile boolean closed;
        public synchronized void refresh() { if (!closed) revision = Math.incrementExact(revision); }
        public synchronized void close() { closed = true; }
        private boolean valid(long expected) { return !closed && revision == expected; }
    }

    public static final class Lease {
        private volatile long latest;
        private volatile boolean closed;
        public synchronized Build begin(Object world, long sectionPos, CompletableFuture<?> task, Generation tree) {
            latest = Math.incrementExact(latest);
            return new Build(this, latest, Objects.requireNonNull(world), sectionPos,
                    Objects.requireNonNull(task), Objects.requireNonNull(tree), tree.revision);
        }
        public void close() { closed = true; }
    }

    public static final class Build {
        private final Lease lease;
        private final long revision;
        private final Object world;
        private final long sectionPos;
        private final CompletableFuture<?> task;
        private final Generation tree;
        private final long treeRevision;
        private DistantHorizonsColumnCoverage coverage;
        private boolean convertedNonEmpty;

        private Build(Lease lease, long revision, Object world, long sectionPos, CompletableFuture<?> task,
                      Generation tree, long treeRevision) {
            this.lease = lease;
            this.revision = revision;
            this.world = world;
            this.sectionPos = sectionPos;
            this.task = task;
            this.tree = tree;
            this.treeRevision = treeRevision;
        }

        public boolean isCurrent() { return active() && lease.latest == revision; }
        private boolean active() { return !lease.closed && !task.isCompletedExceptionally() && tree.valid(treeRevision); }

        public synchronized void capture(DistantHorizonsColumnCoverage input, boolean convertedNonEmpty) {
            Objects.requireNonNull(input, "input");
            if (input.sectionPos() != sectionPos) throw new IllegalArgumentException("Coverage belongs to another section");
            if (coverage != null) throw new IllegalStateException("Primary source captured twice for one build");
            coverage = input;
            this.convertedNonEmpty = convertedNonEmpty;
        }

        private synchronized Input input() {
            return coverage == null ? null : new Input(this, coverage, convertedNonEmpty);
        }
    }

    public record Input(Build build, DistantHorizonsColumnCoverage coverage, boolean convertedNonEmpty) {}

    /** One instance per DH buffer container; a closed instance never accepts metadata again. */
    public static final class Attachment {
        private boolean closed;
        private Input input;
        public synchronized boolean attach(Build build) {
            Objects.requireNonNull(build, "build");
            if (closed || !build.isCurrent()) return false;
            Input captured = build.input();
            if (captured == null) return false;
            if (input != null) throw new IllegalStateException("Buffer already owns a build record");
            input = captured;
            return true;
        }
        public synchronized Optional<Input> inputFor(Object world) {
            if (closed || input == null || input.build.world != world || !input.build.active()) return Optional.empty();
            return Optional.of(input);
        }
        public synchronized void close() { closed = true; input = null; }
    }
}
