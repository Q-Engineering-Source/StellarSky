package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class RingworldGpuProfileTest {
    @Test
    public void nestedCloudStageAndComponentsHaveIndependentDeferredIntervalsAndResetTogether() {
        FakeBackend backend = new FakeBackend(true);
        backend.timestampStep = 1_000_000L;
        List<String> reports = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, clock::get, reports::add);
        var stage = profiler.measure(RingworldGpuProfile.Stage.CLOUD_HIGH);
        for (var part : List.of(RingworldGpuProfile.Stage.CLOUD_HIGH_MODEL,
                RingworldGpuProfile.Stage.CLOUD_HIGH_VOLUME, RingworldGpuProfile.Stage.CLOUD_HIGH_SHEET)) {
            var component = profiler.measure(part);
            clock.addAndGet(1_000_000L);
            component.close();
        }
        stage.close();
        profiler.poll();
        assertEquals(0, backend.resultReads);
        assertTrue(reports.isEmpty());
        backend.completeAll();
        profiler.poll();
        assertEquals(4, reports.size());
        assertTrue(reports.stream().anyMatch(line -> line.contains("CLOUD_HIGH cold:")
                && line.contains("gpuTimestampIntervalMs=7.000")));
        assertEquals(3L, reports.stream().filter(line -> line.contains("gpuTimestampIntervalMs=1.000")).count());
        profiler.dispose();
        assertEquals(8, backend.deleteAttempts);
        assertEquals(0, profiler.submitted(RingworldGpuProfile.Stage.CLOUD_HIGH));
        assertEquals(0, profiler.submitted(RingworldGpuProfile.Stage.CLOUD_HIGH_VOLUME));
    }

    @Test
    public void keepsFourPendingPairsNonBlockingThenReportsColdAndEightWarmSamples() {
        FakeBackend backend = new FakeBackend(true);
        List<String> reports = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000L);
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend,
                () -> clock.getAndAdd(1_000L), reports::add);

        for (int i = 0; i < 4; i++) {
            RingworldGpuProfile.Scope scope = profiler.measure(RingworldGpuProfile.Stage.BOARD);
            scope.close();
        }
        profiler.measure(RingworldGpuProfile.Stage.BOARD).close();
        assertEquals(8, backend.created);
        profiler.poll();
        assertEquals(0, backend.resultReads);
        assertTrue(reports.isEmpty());

        backend.completeAll();
        profiler.poll();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("cold"));
        for (int i = 0; i < 5; i++) {
            RingworldGpuProfile.Scope scope = profiler.measure(RingworldGpuProfile.Stage.BOARD);
            scope.close();
            backend.completeAll();
            profiler.poll();
        }
        assertEquals(9, profiler.submitted(RingworldGpuProfile.Stage.BOARD));
        assertEquals(2, reports.size());
        assertTrue(reports.get(1).contains("warm samples=8"));
        assertTrue(reports.get(1).contains("fullRing=1"));
        int createdAtWindowEnd = backend.created;
        profiler.measure(RingworldGpuProfile.Stage.BOARD).close();
        assertEquals(createdAtWindowEnd, backend.created);
    }

    @Test
    public void unavailableBackendReportsOnceAndNeverProducesAZeroGpuSample() {
        FakeBackend backend = new FakeBackend(false);
        List<String> reports = new ArrayList<>();
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, () -> 0L, reports::add);
        profiler.measure(RingworldGpuProfile.Stage.AIR_TOTAL).close();
        profiler.poll();
        profiler.measure(RingworldGpuProfile.Stage.AIR_TOTAL).close();
        assertEquals(0, backend.created);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("unavailable"));
        assertFalse(reports.get(0).contains("gpuTimestampIntervalMs=0"));
    }

    @Test
    public void cpuWallTimeIsFrozenAtCloseInsteadOfGrowingUntilDeferredPoll() {
        FakeBackend backend = new FakeBackend(true);
        List<String> reports = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000_000L);
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, clock::get, reports::add);
        RingworldGpuProfile.Scope scope = profiler.measure(RingworldGpuProfile.Stage.CLOUD_NEAR);
        clock.set(6_000_000L);
        scope.close();
        backend.completeAll();
        clock.set(500_000_000L);
        profiler.poll();
        assertTrue(reports.get(0).contains("cpuWallMs=5.000"));
    }

    @Test
    public void pollIgnoresOpenScopeAndCannotReadAReusedEndQueryBeforeClose() {
        FakeBackend backend = new FakeBackend(true);
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, () -> 1_000L, ignored -> { });
        RingworldGpuProfile.Scope first = profiler.measure(RingworldGpuProfile.Stage.CLOUD_HORIZON);
        first.close();
        backend.completeAll();
        profiler.poll();
        int readsAfterFirst = backend.resultReads;

        RingworldGpuProfile.Scope second = profiler.measure(RingworldGpuProfile.Stage.CLOUD_HORIZON);
        profiler.poll();
        assertEquals(readsAfterFirst, backend.resultReads);
        second.close();
        profiler.poll();
        assertEquals(readsAfterFirst, backend.resultReads);
        backend.completeAll();
        profiler.poll();
        assertEquals(readsAfterFirst + 2, backend.resultReads);
    }

    @Test
    public void disposeAttemptsEveryOwnedDeleteAndReportsTheFirstCleanupFailure() {
        FakeBackend backend = new FakeBackend(true);
        List<String> reports = new ArrayList<>();
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, () -> 1_000L, reports::add);
        profiler.measure(RingworldGpuProfile.Stage.OWN_MEDIA_TOTAL).close();
        backend.failDeletes = true;
        profiler.dispose();
        assertEquals(2, backend.deleteAttempts);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("cleanup incomplete"));
        assertTrue(reports.get(0).contains("IllegalStateException: test delete failure"));
    }

    @Test
    public void staleScopeCannotCloseAQueryFromTheNextProfileGeneration() {
        FakeBackend backend = new FakeBackend(true);
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, () -> 1_000L, ignored -> { });
        RingworldGpuProfile.Scope stale = profiler.measure(RingworldGpuProfile.Stage.DH_OPAQUE);
        profiler.dispose();
        RingworldGpuProfile.Scope fresh = profiler.measure(RingworldGpuProfile.Stage.DH_OPAQUE);
        int timestampCallsBeforeStaleClose = backend.timestampCalls;
        stale.close();
        assertEquals(timestampCallsBeforeStaleClose, backend.timestampCalls);
        fresh.close();
        assertEquals(timestampCallsBeforeStaleClose + 1, backend.timestampCalls);
    }

    @Test
    public void delayedWindowStartsAtSixtySecondsReusesQueriesAndReportsExactlyOnce() {
        FakeBackend backend = new FakeBackend(true);
        List<String> reports = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, clock::get, reports::add);
        completeSamples(profiler, backend, RingworldGpuProfile.Stage.AIR_TOTAL, 9);
        assertEquals(2, reports.size());
        int queriesAfterInitialWindow = backend.created;

        clock.set(59_999_999_999L);
        profiler.measure(RingworldGpuProfile.Stage.AIR_TOTAL).close();
        assertEquals(queriesAfterInitialWindow, backend.created);
        assertEquals(2, reports.size());

        clock.set(60_000_000_000L);
        completeSamples(profiler, backend, RingworldGpuProfile.Stage.AIR_TOTAL, 8);
        assertEquals(queriesAfterInitialWindow, backend.created);
        assertEquals(3, reports.size());
        assertTrue(reports.get(2).contains("later/60s samples=8"));
        profiler.measure(RingworldGpuProfile.Stage.AIR_TOTAL).close();
        assertEquals(3, reports.size());
    }

    @Test
    public void latePollAndMissingStageDoNotOpenTheDelayedWindowEarly() {
        FakeBackend backend = new FakeBackend(true);
        List<String> reports = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        RingworldGpuProfile.Profiler profiler = new RingworldGpuProfile.Profiler(backend, clock::get, reports::add);
        for (int index = 0; index < 4; index++) {
            RingworldGpuProfile.Scope scope = profiler.measure(RingworldGpuProfile.Stage.BOARD);
            scope.close();
        }
        backend.completeAll();
        clock.set(70_000_000_000L);
        profiler.poll();
        completeSamples(profiler, backend, RingworldGpuProfile.Stage.BOARD, 5);
        assertEquals(2, reports.size());
        completeSamples(profiler, backend, RingworldGpuProfile.Stage.BOARD, 1);
        assertEquals(2, reports.size());

        completeSamples(profiler, backend, RingworldGpuProfile.Stage.CLOUD_NEAR, 9);
        profiler.measure(RingworldGpuProfile.Stage.CLOUD_NEAR).close();
        assertEquals(4, reports.size());
        clock.set(130_000_000_000L);
        completeSamples(profiler, backend, RingworldGpuProfile.Stage.CLOUD_NEAR, 8);
        assertEquals(5, reports.size());
        assertTrue(reports.get(4).contains("CLOUD_NEAR later/60s samples=8"));
    }

    private static void completeSamples(RingworldGpuProfile.Profiler profiler, FakeBackend backend,
                                        RingworldGpuProfile.Stage stage, int samples) {
        for (int index = 0; index < samples; index++) {
            RingworldGpuProfile.Scope scope = profiler.measure(stage);
            scope.close();
            backend.completeAll();
            profiler.poll();
        }
    }

    private static final class FakeBackend implements RingworldGpuProfile.Backend {
        private final boolean supported;
        private final Map<Integer, Long> results = new HashMap<>();
        private final List<Integer> timestampOrder = new ArrayList<>();
        private int created;
        private int resultReads;
        private int timestampCalls;
        private int deleteAttempts;
        private boolean failDeletes;
        private long timestampStep = 100L;

        private FakeBackend(boolean supported) { this.supported = supported; }
        @Override public boolean supportsTimestampQueries() { return supported; }
        @Override public int createQuery() { return ++created; }
        @Override public void deleteQuery(int query) {
            deleteAttempts++;
            if (failDeletes) throw new IllegalStateException("test delete failure");
            results.remove(query);
        }
        @Override public void timestamp(int query) { timestampCalls++; timestampOrder.add(query); results.put(query, null); }
        @Override public boolean resultAvailable(int query) { return results.get(query) != null; }
        @Override public long result(int query) { resultReads++; return results.get(query); }
        private void completeAll() {
            long timestamp = 10_000L;
            for (Integer query : timestampOrder) if (results.get(query) == null) results.put(query, timestamp += timestampStep);
        }
    }
}
