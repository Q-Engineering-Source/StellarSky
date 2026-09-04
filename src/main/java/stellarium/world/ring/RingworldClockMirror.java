package stellarium.world.ring;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Scene-owned client mirror. Callers must supply the currently committed
 * scene identity; stale, foreign, and replayed samples are never retained.
 */
public final class RingworldClockMirror {
    private static final long DISPLAY_INTERVAL_NANOS = 50_000_000L;

    private final LongSupplier nanoTime;
    private RingworldClockSample previousSample;
    private RingworldClockSample currentSample;
    private long currentAcceptedAtNanos;
    private Object boundWorld;
    private Object boundScene;
    private RingworldClockClientState.Receipt boundReceipt;

    public RingworldClockMirror() {
        this(System::nanoTime);
    }

    public RingworldClockMirror(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public boolean acceptForCommittedScene(int committedDimension, UUID committedGeneration,
                                           RingworldClockSample candidate, Object currentWorld,
                                           Object committedScene, RingworldClockClientState.Receipt receipt) {
        Objects.requireNonNull(committedGeneration, "committedGeneration");
        Objects.requireNonNull(candidate, "candidate");
        if (!RingworldClockClientState.isCurrent(receipt)
                || currentWorld == null || committedScene == null
                || receipt.context().world() != currentWorld || receipt.context().scene() != committedScene
                || candidate.dimension() != committedDimension
                || !candidate.generation().equals(committedGeneration)
                || (currentSample != null && candidate.sequence() <= currentSample.sequence())) {
            return false;
        }
        long acceptedAtNanos = nanoTime.getAsLong();
        previousSample = currentSample;
        currentSample = candidate;
        currentAcceptedAtNanos = acceptedAtNanos;
        boundWorld = currentWorld;
        boundScene = committedScene;
        boundReceipt = receipt;
        return true;
    }

    public RingworldClockSample currentSampleFor(Object currentWorld, Object committedScene) {
        return currentSample != null && boundWorld == currentWorld && boundScene == committedScene
                && RingworldClockClientState.isCurrent(boundReceipt) ? currentSample : null;
    }

    public DisplayTime displayTimeFor(Object currentWorld, Object committedScene) {
        RingworldClockSample current = currentSampleFor(currentWorld, committedScene);
        if (current == null || previousSample == null || current.discontinuousBefore()
                || !isAdjacent(previousSample, current) || !hasRepresentableSpan(previousSample, current)) {
            return current == null ? null : new DisplayTime(current, current, 0.0);
        }
        return new DisplayTime(previousSample, current, displayFraction(nanoTime.getAsLong()));
    }

    public void clear() {
        previousSample = null;
        currentSample = null;
        boundWorld = null;
        boundScene = null;
        boundReceipt = null;
    }

    private static boolean isAdjacent(RingworldClockSample previous, RingworldClockSample current) {
        try {
            return Math.incrementExact(previous.sequence()) == current.sequence();
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static boolean hasRepresentableSpan(RingworldClockSample previous, RingworldClockSample current) {
        try {
            Math.subtractExact(current.worldTime(), previous.worldTime());
            return true;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private double displayFraction(long nowNanos) {
        // System.nanoTime's signed long intentionally wraps. For our bounded
        // 50ms interval, normal subtraction remains valid across that wrap.
        long elapsedNanos = nowNanos - currentAcceptedAtNanos;
        if (elapsedNanos <= 0L) {
            return 0.0;
        }
        return Math.min(1.0, elapsedNanos / (double) DISPLAY_INTERVAL_NANOS);
    }

    public record DisplayTime(RingworldClockSample previous, RingworldClockSample current, double fraction) {
    }
}
