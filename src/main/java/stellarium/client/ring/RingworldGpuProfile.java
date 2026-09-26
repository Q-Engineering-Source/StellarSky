package stellarium.client.ring;

import java.util.EnumMap;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.lwjglx.opengl.GL15;
import org.lwjglx.opengl.GL33;
import org.lwjglx.opengl.GLContext;

import stellarium.StellarSky;

/**
 * Bounded, non-blocking timestamp-query diagnostics for the ringworld passes.
 * GPU timestamp intervals are not GPU-utilization or FPS measurements: they can
 * include command-submission gaps and CPU idle time between the two timestamps.
 */
public final class RingworldGpuProfile {
    public enum Stage {
        BOARD, BOARD_NEAREST_HIGH, BOARD_NEAREST_LOW, BOARD_COLOR,
        CLOUD_NEAR, CLOUD_HORIZON, CLOUD_COMPOSITE, AIR_TOTAL, AIR_COPY, AIR_DRAW,
        CLOUD_HIGH, CLOUD_LOW, CLOUD_COLOR,
        CLOUD_HIGH_MODEL, CLOUD_LOW_MODEL, CLOUD_COLOR_MODEL,
        CLOUD_HIGH_VOLUME, CLOUD_LOW_VOLUME, CLOUD_COLOR_VOLUME,
        CLOUD_HIGH_SHEET, CLOUD_LOW_SHEET, CLOUD_COLOR_SHEET, CLOUD_DEBUG,
        OWN_MEDIA_TOTAL, DH_OPAQUE, DH_TRANSLUCENT, MODEL_TOTAL,
        // The late preview settle is its own stage: it runs after DH and near terrain,
        // outside the early OWN_MEDIA_TOTAL/MODEL_TOTAL interval.
        PREVIEW_SETTLE
    }

    private static final Profiler ACTIVE = new Profiler(new OpenGlBackend(), System::nanoTime,
            message -> {
                if (StellarSky.INSTANCE != null && StellarSky.INSTANCE.getLogger() != null) {
                    StellarSky.INSTANCE.getLogger().info(message);
                }
            });

    private RingworldGpuProfile() {
    }

    public static Scope measure(Stage stage) {
        if (stage == null) throw new IllegalArgumentException("GPU profile stage is required");
        return ACTIVE.measure(stage);
    }

    /** Poll from a frame-end point; it never blocks for an unfinished query. */
    public static void poll() {
        ACTIVE.poll();
    }

    /** Deletes only this profiler's query objects and opens a fresh bounded window. */
    public static void dispose() {
        ACTIVE.dispose();
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope(null, null, null);
        private final Profiler owner;
        private final StageState state;
        private final Slot slot;
        private final long generation;
        private boolean closed;

        private Scope(Profiler owner, StageState state, Slot slot) {
            this(owner, state, slot, 0L);
        }

        private Scope(Profiler owner, StageState state, Slot slot, long generation) {
            this.owner = owner;
            this.state = state;
            this.slot = slot;
            this.generation = generation;
        }

        @Override
        public void close() {
            if (!closed && owner != null) {
                closed = true;
                owner.close(state, slot, generation);
            }
        }
    }

    interface Backend {
        boolean supportsTimestampQueries();
        int createQuery();
        void deleteQuery(int query);
        void timestamp(int query);
        boolean resultAvailable(int query);
        long result(int query);
    }

    static final class Profiler {
        private static final int PENDING_PAIRS = 4;
        private static final int WARM_SAMPLES = 8;
        private static final int TOTAL_SAMPLES = WARM_SAMPLES + 1;
        private static final int DELAYED_SAMPLES = 8;
        private static final long DELAYED_WINDOW_NANOS = 60_000_000_000L;
        private final Backend backend;
        private final LongSupplier clock;
        private final Consumer<String> reporter;
        private final EnumMap<Stage, StageState> stages = new EnumMap<>(Stage.class);
        private boolean checked;
        private boolean available;
        private boolean unavailableReported;
        private long sequence;
        private long generation;

        Profiler(Backend backend, LongSupplier clock, Consumer<String> reporter) {
            this.backend = backend;
            this.clock = clock;
            this.reporter = reporter;
            for (Stage stage : Stage.values()) stages.put(stage, new StageState(stage));
        }

        Scope measure(Stage stage) {
            if (!ensureAvailable()) return Scope.NOOP;
            StageState state = stages.get(stage);
            long now = clock.getAsLong();
            Window window = state.nextWindow(now);
            if (window == null) return Scope.NOOP;
            Slot slot = state.freeSlot();
            if (slot == null) {
                state.fullRing(window);
                return Scope.NOOP;
            }
            try {
                slot.ensureQueries(backend);
                slot.cpuStart = now;
                slot.sequence = ++sequence;
                slot.generation = generation;
                slot.window = window;
                backend.timestamp(slot.startQuery);
                slot.pending = true;
                slot.closed = false;
                state.submitted(window);
                return new Scope(this, state, slot, generation);
            } catch (RuntimeException | LinkageError failure) {
                fail(failure);
                return Scope.NOOP;
            }
        }

        void close(StageState state, Slot slot, long scopeGeneration) {
            if (scopeGeneration != generation || !slot.pending || slot.closed || slot.generation != scopeGeneration) return;
            try {
                slot.cpuNanos = clock.getAsLong() - slot.cpuStart;
                if (slot.cpuNanos < 0L) throw new IllegalStateException("GPU profiler CPU clock moved backwards");
                backend.timestamp(slot.endQuery);
                slot.closed = true;
            } catch (RuntimeException | LinkageError failure) {
                slot.pending = false;
                slot.closed = false;
                state.unsubmitted(slot.window);
                fail(failure);
            }
        }

        void poll() {
            if (!available) return;
            for (StageState state : stages.values()) {
                while (true) {
                    Slot slot = state.oldestPending();
                    if (slot == null || !slot.closed) break;
                    try {
                        if (!backend.resultAvailable(slot.startQuery) || !backend.resultAvailable(slot.endQuery)) break;
                        long gpuNanos = backend.result(slot.endQuery) - backend.result(slot.startQuery);
                        if (gpuNanos < 0L) throw new IllegalStateException("GPU timestamp interval was negative");
                        slot.pending = false;
                        slot.closed = false;
                        state.record(slot.window, slot.cpuNanos, gpuNanos, reporter);
                    } catch (RuntimeException | LinkageError failure) {
                        fail(failure);
                        return;
                    }
                }
            }
        }

        void dispose() {
            CleanupFailures cleanupFailures = new CleanupFailures();
            for (StageState state : stages.values()) state.dispose(backend, cleanupFailures);
            if (cleanupFailures.count != 0) reporter.accept("SS GPU profile cleanup incomplete: " + cleanupFailures.count
                    + " query delete failure(s); first=" + describe(cleanupFailures.first));
            stages.clear();
            for (Stage stage : Stage.values()) stages.put(stage, new StageState(stage));
            checked = available = unavailableReported = false;
            sequence = 0L;
            generation++;
        }

        int submitted(Stage stage) {
            return stages.get(stage).initialSubmitted;
        }

        private boolean ensureAvailable() {
            if (checked) return available;
            checked = true;
            try {
                available = backend.supportsTimestampQueries();
                if (!available) unavailable("timer-query capability is absent");
            } catch (RuntimeException | LinkageError failure) {
                available = false;
                unavailable(describe(failure));
            }
            return available;
        }

        private void fail(Throwable failure) {
            available = false;
            unavailable(describe(failure));
        }

        private void unavailable(String reason) {
            if (!unavailableReported) {
                unavailableReported = true;
                reporter.accept("SS GPU profile unavailable: " + reason + "; rendering continues without timer samples");
            }
        }

        private static String describe(Throwable failure) {
            String message = failure.getMessage();
            return failure.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
        }
    }

    private static final class StageState {
        private final Stage stage;
        private final Slot[] slots = { new Slot(), new Slot(), new Slot(), new Slot() };
        private long firstMeasureNanos = Long.MIN_VALUE;
        private int initialSubmitted;
        private int initialCompleted;
        private int delayedSubmitted;
        private int delayedCompleted;
        private int fullRing;
        private int delayedFullRing;
        private boolean coldRecorded;
        private int warmCount;
        private long warmCpuMin = Long.MAX_VALUE, warmCpuMax, warmCpuTotal;
        private long warmGpuMin = Long.MAX_VALUE, warmGpuMax, warmGpuTotal;
        private boolean summaryReported;
        private long delayedCpuMin = Long.MAX_VALUE, delayedCpuMax, delayedCpuTotal;
        private long delayedGpuMin = Long.MAX_VALUE, delayedGpuMax, delayedGpuTotal;
        private boolean delayedSummaryReported;

        private StageState(Stage stage) {
            this.stage = stage;
        }

        private Slot freeSlot() {
            for (Slot slot : slots) if (!slot.pending) return slot;
            return null;
        }

        private Window nextWindow(long now) {
            if (firstMeasureNanos == Long.MIN_VALUE) firstMeasureNanos = now;
            if (initialSubmitted < Profiler.TOTAL_SAMPLES) return Window.INITIAL;
            if (initialCompleted < Profiler.TOTAL_SAMPLES || now - firstMeasureNanos < Profiler.DELAYED_WINDOW_NANOS) {
                return null;
            }
            return delayedSubmitted < Profiler.DELAYED_SAMPLES ? Window.DELAYED : null;
        }

        private void submitted(Window window) {
            if (window == Window.INITIAL) initialSubmitted++;
            else delayedSubmitted++;
        }

        private void unsubmitted(Window window) {
            if (window == Window.INITIAL) initialSubmitted--;
            else delayedSubmitted--;
        }

        private void fullRing(Window window) {
            if (window == Window.INITIAL) fullRing++;
            else delayedFullRing++;
        }

        private Slot oldestPending() {
            Slot oldest = null;
            for (Slot slot : slots) if (slot.pending && (oldest == null || slot.sequence < oldest.sequence)) oldest = slot;
            return oldest;
        }

        private void record(Window window, long cpuNanos, long gpuNanos, Consumer<String> reporter) {
            if (window == Window.DELAYED) {
                recordDelayed(cpuNanos, gpuNanos, reporter);
                return;
            }
            initialCompleted++;
            if (!coldRecorded) {
                coldRecorded = true;
                reporter.accept("SS GPU profile " + stage + " cold: cpuWallMs=" + ms(cpuNanos)
                        + " gpuTimestampIntervalMs=" + ms(gpuNanos));
                return;
            }
            warmCount++;
            warmCpuMin = Math.min(warmCpuMin, cpuNanos); warmCpuMax = Math.max(warmCpuMax, cpuNanos); warmCpuTotal += cpuNanos;
            warmGpuMin = Math.min(warmGpuMin, gpuNanos); warmGpuMax = Math.max(warmGpuMax, gpuNanos); warmGpuTotal += gpuNanos;
            if (warmCount == Profiler.WARM_SAMPLES && !summaryReported) {
                summaryReported = true;
                reporter.accept("SS GPU profile " + stage + " warm samples=" + warmCount + " cpuWallMs[min/mean/max]="
                        + ms(warmCpuMin) + "/" + ms(warmCpuTotal / warmCount) + "/" + ms(warmCpuMax)
                        + " gpuTimestampIntervalMs[min/mean/max]=" + ms(warmGpuMin) + "/"
                        + ms(warmGpuTotal / warmCount) + "/" + ms(warmGpuMax) + " fullRing=" + fullRing
                        + " (inclusive timestamp interval; it is not GPU busy time, utilization, or FPS)");
            }
        }

        private void recordDelayed(long cpuNanos, long gpuNanos, Consumer<String> reporter) {
            delayedCompleted++;
            delayedCpuMin = Math.min(delayedCpuMin, cpuNanos); delayedCpuMax = Math.max(delayedCpuMax, cpuNanos); delayedCpuTotal += cpuNanos;
            delayedGpuMin = Math.min(delayedGpuMin, gpuNanos); delayedGpuMax = Math.max(delayedGpuMax, gpuNanos); delayedGpuTotal += gpuNanos;
            if (delayedCompleted == Profiler.DELAYED_SAMPLES && !delayedSummaryReported) {
                delayedSummaryReported = true;
                reporter.accept("SS GPU profile " + stage + " later/60s samples=" + delayedCompleted
                        + " cpuWallMs[min/mean/max]=" + ms(delayedCpuMin) + "/"
                        + ms(delayedCpuTotal / delayedCompleted) + "/" + ms(delayedCpuMax)
                        + " gpuTimestampIntervalMs[min/mean/max]=" + ms(delayedGpuMin) + "/"
                        + ms(delayedGpuTotal / delayedCompleted) + "/" + ms(delayedGpuMax)
                        + " fullRing=" + delayedFullRing
                        + " (later window starts at least 60s after first measure; inclusive timestamp interval is not GPU busy time, utilization, or FPS)");
            }
        }

        private void dispose(Backend backend, CleanupFailures cleanupFailures) {
            for (Slot slot : slots) slot.dispose(backend, cleanupFailures);
        }

        private static String ms(long nanos) {
            return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
        }
    }

    private static final class Slot {
        private int startQuery;
        private int endQuery;
        private long sequence;
        private long cpuStart;
        private long cpuNanos;
        private long generation;
        private Window window;
        private boolean pending;
        private boolean closed;

        private void ensureQueries(Backend backend) {
            if (startQuery == 0) startQuery = validQueryId(backend.createQuery());
            if (endQuery == 0) endQuery = validQueryId(backend.createQuery());
        }

        private void dispose(Backend backend, CleanupFailures cleanupFailures) {
            delete(backend, cleanupFailures, startQuery);
            delete(backend, cleanupFailures, endQuery);
            startQuery = endQuery = 0;
            pending = closed = false;
            window = null;
        }

        private static int validQueryId(int query) {
            if (query <= 0) throw new IllegalStateException("GPU profiler allocated invalid query id " + query);
            return query;
        }

        private static void delete(Backend backend, CleanupFailures cleanupFailures, int query) {
            if (query == 0) return;
            try {
                backend.deleteQuery(query);
            } catch (RuntimeException | LinkageError failure) {
                cleanupFailures.record(failure);
            }
        }
    }

    private static final class CleanupFailures {
        private int count;
        private Throwable first;
        private void record(Throwable failure) {
            count++;
            if (first == null) first = failure;
        }
    }

    private enum Window {
        INITIAL, DELAYED
    }

    private static final class OpenGlBackend implements Backend {
        @Override public boolean supportsTimestampQueries() {
            return GLContext.getCapabilities().OpenGL33 || GLContext.getCapabilities().GL_ARB_timer_query;
        }
        @Override public int createQuery() { return GL15.glGenQueries(); }
        @Override public void deleteQuery(int query) { GL15.glDeleteQueries(query); }
        @Override public void timestamp(int query) { GL33.glQueryCounter(query, GL33.GL_TIMESTAMP); }
        @Override public boolean resultAvailable(int query) { return GL15.glGetQueryObjectui(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
        @Override public long result(int query) { return GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT); }
    }
}
