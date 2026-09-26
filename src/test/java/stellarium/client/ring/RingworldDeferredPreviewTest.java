package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import stellarium.client.ring.dh.DistantHorizonsColumnCoverage;
import stellarium.client.ring.dh.DistantHorizonsCoverageTransfer;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverage;
import stellarium.world.ring.RingworldClockMirror;
import stellarium.world.ring.RingworldClockSample;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;
import stellarium.world.ring.terrain.TerrainColumnState;
import stellarium.world.ring.terrain.TerrainTileKey;

/**
 * The deferred-preview seam: an early pass may only leave a payload behind, and the
 * current frame's admitted coverage may only retire preview columns afterwards.
 *
 * <p>These are state and mask rules. They do not run OpenGL, and they assert nothing
 * about whether a real frame reaches an identically shaped seam - that ordering is
 * pinned separately against the compiled world pass.</p>
 */
public class RingworldDeferredPreviewTest {
    private static final long EPOCH = 1L;
    /** Level of the preview tile the synthetic coverage patch projects onto. */
    private static final int TILE_LEVEL = 6;
    private static final TerrainTileKey DRAWN = new TerrainTileKey(EPOCH, TILE_LEVEL, -1, 2);

    @Test
    public void payloadIsInvisibleBeforeTheFrameIsPublishedAndDiesWithThePass() {
        Object world = new Object(), scene = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            // Before this pass publishes its optical frame there is nothing to settle into.
            var early = new Object();
            assertNull(RingworldRenderSnapshots.currentDeferredPreview());
            assertThrows(IllegalStateException.class,
                    () -> RingworldRenderSnapshots.captureDeferredPreview(early));
            RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
            RingworldRenderSnapshots.captureDeferredPreview(early);
            assertSame(early, RingworldRenderSnapshots.currentDeferredPreview());
            // The next eye/viewport pass must not inherit it.
            RingworldRenderSnapshots.clearCurvatureFrame();
            assertNull(RingworldRenderSnapshots.currentDeferredPreview());
        });
        assertNull(RingworldRenderSnapshots.currentDeferredPreview());
    }

    @Test
    public void nestedScopeCannotReadOrOverwriteTheOuterPayloadThroughItsOwnFrame() {
        Object world = new Object(), scene = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        var payload = new Object();
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
            RingworldRenderSnapshots.captureDeferredPreview(payload);
            RingworldRenderSnapshots.withSnapshot(() -> null, () -> {
                assertNull(RingworldRenderSnapshots.currentDeferredPreview());
                assertThrows(IllegalStateException.class,
                        () -> RingworldRenderSnapshots.captureDeferredPreview(new Object()));
            });
            assertSame(payload, RingworldRenderSnapshots.currentDeferredPreview());
        });
    }

    @Test
    public void earlyPassSeesNoCoverageAndTheSettleSeesTheSameFrameCoverage() {
        Object world = new Object(), scene = new Object(), view = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        var coverage = completedCoverage(world, frame, view, TILE_LEVEL,
                new int[] {(2 * 64 + 3), (3 * 64 + 2)},
                new TerrainColumnState[] {TerrainColumnState.REAL_SOLID, TerrainColumnState.REAL_AIR});
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            RingworldRenderSnapshots.publishDistantCurvatureFrame(frame);
            // The early pass runs at setupFog ordinal 1, before DH submits anything.
            assertNull(RingworldRenderSnapshots.currentDistantCoverage());
            assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(Set.of(DRAWN),
                    RingworldRenderSnapshots.currentDistantCoverage(), frame).isEmpty());
            // DH publishes the frame's record later in the same passing.
            RingworldRenderSnapshots.captureDistantCoverage(coverage);
            var masks = ProceduralRingModelRenderer.dhRetirementMasks(Set.of(DRAWN),
                    RingworldRenderSnapshots.currentDistantCoverage(), frame);
            assertEquals(1, masks.size());
            var mask = masks.get(DRAWN);
            assertEquals(2, mask.coveredColumns());
            assertTrue("REAL_SOLID must retire the preview column", mask.covers(2, 3));
            assertTrue("REAL_AIR must retire the preview column too", mask.covers(3, 2));
            assertFalse(mask.covers(2, 2));
        });
    }

    @Test
    public void retirementNeverUsesAnotherFrameAnotherWorldOrAnUnfinishedRecord() {
        Object world = new Object(), scene = new Object(), view = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        var drawn = Set.of(DRAWN);
        var coverage = completedCoverage(world, frame, view, TILE_LEVEL,
                new int[] {(2 * 64 + 3)}, new TerrainColumnState[] {TerrainColumnState.REAL_SOLID});

        // The exact frame identity is required; an older or unrelated frame yields no retirement.
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(drawn, coverage, new Object()).isEmpty());
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(drawn, null, frame).isEmpty());
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(Set.of(), coverage, frame).isEmpty());
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(drawn, coverage, null).isEmpty());

        // An unfinished record is not evidence, and neither is a record whose buffer was retired.
        var unfinished = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var unfinishedSnapshot = new DistantHorizonsFrameCoverage.Snapshot(frame, view, unfinished);
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(drawn, unfinishedSnapshot, frame).isEmpty());
        unfinished.abort();
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(drawn, unfinishedSnapshot, frame).isEmpty());

        var retired = ProceduralRingModelRenderer.dhRetirementMasks(drawn, coverage, frame);
        assertEquals(1, retired.size());
        assertEquals(1, retired.get(DRAWN).coveredColumns());
        assertTrue(retired.get(DRAWN).covers(2, 3));
        assertThrows(IllegalArgumentException.class, () -> ProceduralRingModelRenderer.dhRetirementMasks(
                Set.of(DRAWN, new TerrainTileKey(EPOCH + 1, TILE_LEVEL, -1, 2)),
                coverage, frame));
    }

    @Test
    public void unknownColumnsDoNotRetirePreview() {
        Object world = new Object(), scene = new Object(), view = new Object();
        var snapshot = snapshot(world, scene);
        var frame = frame(world, scene, snapshot);
        var coverage = completedCoverage(world, frame, view, TILE_LEVEL,
                new int[] {(2 * 64 + 3)}, new TerrainColumnState[] {TerrainColumnState.UNKNOWN});
        assertTrue(ProceduralRingModelRenderer.dhRetirementMasks(Set.of(DRAWN), coverage, frame).isEmpty());
    }

    private static DistantHorizonsFrameCoverage.Snapshot completedCoverage(Object world, Object frame, Object view,
                                                                         int level, int[] indexes,
                                                                         TerrainColumnState[] states) {
        var vbo = new Object();
        var columns = new ArrayList<>(Collections.nCopies(4096, TerrainColumnState.UNKNOWN));
        for (int i = 0; i < indexes.length; i++) columns.set(indexes[i], states[i]);
        long pos = DhSectionPos.encode((byte) (6 + level), -1, 2);
        var build = new DistantHorizonsCoverageTransfer.Lease().begin(world, pos, new CompletableFuture<>(),
                new DistantHorizonsCoverageTransfer.Generation());
        build.capture(new DistantHorizonsColumnCoverage(pos, 0, 256, false, columns), true);
        var attachment = new DistantHorizonsCoverageTransfer.Attachment();
        attachment.attach(build);
        var ledger = new DistantHorizonsFrameCoverage(world, frame, view, 8);
        var selected = ledger.select(attachment, List.of(vbo), List.of());
        ledger.submitted(selected, true, vbo);
        ledger.finishPass(true);
        ledger.finishPass(false);
        ledger.composited(frame, view);
        return new DistantHorizonsFrameCoverage.Snapshot(frame, view, ledger);
    }

    private static RingworldDisplaySnapshot snapshot(Object world, Object scene) {
        RingworldClockSample sample = new RingworldClockSample(0, UUID.randomUUID(), 1L, 0L, true);
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        return new RingworldDisplaySnapshot(world, scene,
                new RingworldClockMirror.DisplayTime(sample, sample, 0.0), sunshade,
                sunshade.phase(0L, 0L, 0.0), 512, 8,
                new RingworldRenderObserver(0.0, 64.0, 0.0), 1.0, 0.2);
    }

    private static RingworldCurvatureFrame frame(Object world, Object scene, RingworldDisplaySnapshot snapshot) {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        return new RingworldCurvatureFrame(world, scene, snapshot.observer(),
                149597870700.0, identity, identity, 0, 0, 800, 600);
    }
}
