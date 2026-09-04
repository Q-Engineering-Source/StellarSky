package stellarium.client.ring;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import stellarium.world.ring.RingworldClockMirror;
import stellarium.world.ring.RingworldClockSample;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;

public class RingworldRenderSnapshotsTest {
    @Test
    public void emptyMaterialDoesNotInitializeMinecraftOrQueryAnOpenGlContext() {
        RingworldDisplaySnapshot sample = snapshot(new Object(), new Object());
        RingworldSunshade empty = new RingworldSunshade(Double.MAX_VALUE, 0.0, 100L, 0.0, 0.0, 0.0);
        RingworldDisplaySnapshot emptySnapshot = new RingworldDisplaySnapshot(sample.world(), sample.scene(),
                sample.displayTime(), empty, empty.phase(0L, 0L, 0.0), Integer.MAX_VALUE - 8, 8,
                sample.observer(), sample.atmosphereFade(), sample.atmosphereGeometryHeight());
        RingworldRenderSnapshots.withSnapshot(() -> emptySnapshot, () -> RingworldBoardRenderer.renderCurrentWorld(0.0f));
        assertNull(RingworldRenderSnapshots.current());
    }

    @Test
    public void displayRejectsBoardHeightsOutsideTheSinglePrecisionBlockGrid() {
        RingworldDisplaySnapshot input = snapshot(new Object(), new Object());
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplaySnapshot(input.world(), input.scene(),
                input.displayTime(), input.sunshade(), input.phase(), 16_777_209, 8,
                input.observer(), input.atmosphereFade(), input.atmosphereGeometryHeight()));
        RingworldDisplaySnapshot limit = new RingworldDisplaySnapshot(input.world(), input.scene(),
                input.displayTime(), input.sunshade(), input.phase(), 16_777_208, 8,
                input.observer(), input.atmosphereFade(), input.atmosphereGeometryHeight());
        assertSame(input.phase(), limit.phase());
    }

    @Test
    public void staticSpaceAppearanceDoesNotInventClockAuthorityOrInitializeGl() {
        RingworldDisplaySnapshot source = snapshot(new Object(), new Object());
        RingworldDisplaySnapshot waiting = new RingworldDisplaySnapshot(source.world(), source.scene(),
                null, source.sunshade(), null, 512, 8,
                new RingworldRenderObserver(0.0, 256.0, 0.0), 0.0, 0.3);
        assertEquals(0.0, waiting.atmosphereFade(), 0.0);
        assertNull(waiting.displayTime());
        assertNull(waiting.phase());
        RingworldRenderSnapshots.withSnapshot(() -> waiting, () -> RingworldBoardRenderer.renderCurrentWorld(0.5f));
        assertNull(RingworldRenderSnapshots.current());
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplaySnapshot(source.world(), source.scene(),
                source.displayTime(), source.sunshade(), null, 512, 8, source.observer(), 1.0, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new RingworldDisplaySnapshot(source.world(), source.scene(),
                null, source.sunshade(), source.phase(), 512, 8, source.observer(), 1.0, 0.2));
    }

    @Test
    public void cameraConsumptionFreezesOneObserverAndPhaseForAllPasses() {
        RingworldDisplaySnapshot frozen = snapshot(new Object(), new Object());
        AtomicInteger captures = new AtomicInteger();
        RingworldRenderSnapshots.withSnapshot(() -> {
            assertNull(RingworldRenderSnapshots.current());
            RingworldRenderSnapshots.captureOnce(() -> { captures.incrementAndGet(); return frozen; });
            RingworldRenderSnapshots.captureOnce(() -> { captures.incrementAndGet(); return null; });
            assertSame(frozen, RingworldRenderSnapshots.current());
            assertSame(frozen.observer(), RingworldRenderSnapshots.current().observer());
        });
        assertEquals(1, captures.get());
        assertNull(RingworldRenderSnapshots.current());
    }

    @Test
    public void absentFirstPassStaysAbsentAndDeferredFailureRestoresOuterScope() {
        RingworldDisplaySnapshot outer = snapshot(new Object(), new Object());
        AtomicInteger captures = new AtomicInteger();
        RingworldRenderSnapshots.withSnapshot(() -> outer, () -> {
            RingworldRenderSnapshots.withSnapshot(() -> {
                assertNull(RingworldRenderSnapshots.current());
                RingworldRenderSnapshots.captureOnce(() -> { captures.incrementAndGet(); return null; });
                RingworldRenderSnapshots.captureOnce(() -> { captures.incrementAndGet(); return outer; });
                assertNull(RingworldRenderSnapshots.current());
            });
            assertEquals(1, captures.get());
            assertSame(outer, RingworldRenderSnapshots.current());
            assertThrows(IllegalArgumentException.class, () -> RingworldRenderSnapshots.withSnapshot(() -> {
                RingworldRenderSnapshots.captureOnce(() -> { throw new IllegalArgumentException("camera input"); });
            }));
            assertSame(outer, RingworldRenderSnapshots.current());
        });
        assertNull(RingworldRenderSnapshots.current());
    }

    @Test
    public void observerUsesSuppliedPartialTickAndRejectsInvalidInputs() {
        RingworldRenderObserver observer = RingworldRenderObserver.interpolate(10.0, 192.0, 8191.0,
                30.0, 256.0, 8193.0, 0.5f);
        assertEquals(20.0, observer.x(), 0.0);
        assertEquals(224.0, observer.y(), 0.0);
        assertEquals(8192.0, observer.z(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> new RingworldRenderObserver(0.0, Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class, () -> RingworldRenderObserver.interpolate(
                0.0, 0.0, 0.0, 1.0, 1.0, 1.0, Float.NaN));
    }

    @Test
    public void failedCapturePreservesPriorScopeAndDoesNotRenderWithUnprovenInput() {
        RingworldDisplaySnapshot outer = snapshot(new Object(), new Object());
        IllegalArgumentException failure = new IllegalArgumentException("invalid capture");
        AtomicBoolean rendered = new AtomicBoolean();
        RingworldRenderSnapshots.withSnapshot(() -> outer, () -> {
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> RingworldRenderSnapshots.withSnapshot(() -> { throw failure; },
                            () -> rendered.set(true))));
            assertFalse(rendered.get());
            assertSame(outer, RingworldRenderSnapshots.current());
        });
        assertNull(RingworldRenderSnapshots.current());
    }

    @Test
    public void nestedWorldRestoresItsCallerAndAnOuterFailureLeavesNoSnapshot() {
        Object worldA = new Object();
        Object sceneA = new Object();
        RingworldDisplaySnapshot outer = snapshot(worldA, sceneA);
        RingworldDisplaySnapshot inner = snapshot(new Object(), new Object());
        assertThrows(IllegalStateException.class, () -> RingworldRenderSnapshots.withSnapshot(() -> outer, () -> {
            RingworldRenderSnapshots.withSnapshot(() -> inner, () -> {
                assertSame(inner, RingworldRenderSnapshots.currentFor(inner.world(), inner.scene()));
                assertNull(RingworldRenderSnapshots.currentFor(worldA, sceneA));
            });
            assertSame(outer, RingworldRenderSnapshots.currentFor(worldA, sceneA));
            assertNull(RingworldRenderSnapshots.currentFor(worldA, new Object()));
            throw new IllegalStateException("outer render failed");
        }));
        assertNull(RingworldRenderSnapshots.current());
    }

    @Test
    public void capturedInputMasksNestedEmptyScopeAndRestoresAfterException() {
        Object world = new Object();
        Object scene = new Object();
        RingworldDisplaySnapshot snapshot = snapshot(world, scene);
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            assertSame(snapshot, RingworldRenderSnapshots.currentFor(world, scene));
            RingworldRenderSnapshots.withSnapshot(() -> null, () ->
                    assertNull(RingworldRenderSnapshots.currentFor(world, scene)));
            assertSame(snapshot, RingworldRenderSnapshots.currentFor(world, scene));
            assertThrows(IllegalStateException.class,
                    () -> RingworldRenderSnapshots.withSnapshot(() -> null,
                            () -> { throw new IllegalStateException("boom"); }));
            assertSame(snapshot, RingworldRenderSnapshots.currentFor(world, scene));
            assertNull(RingworldRenderSnapshots.currentFor(new Object(), scene));
        });
        assertNull(RingworldRenderSnapshots.current());
    }

    private static RingworldDisplaySnapshot snapshot(Object world, Object scene) {
        RingworldClockSample sample = new RingworldClockSample(0, UUID.randomUUID(), 1L, 0L, true);
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        return new RingworldDisplaySnapshot(world, scene,
                new RingworldClockMirror.DisplayTime(sample, sample, 0.0), sunshade,
                sunshade.phase(0L, 0L, 0.0), 512, 8,
                new RingworldRenderObserver(0.0, 64.0, 0.0), 1.0, 0.2);
    }
}
