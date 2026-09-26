package stellarium.client.ring.dh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import stellarium.world.ring.terrain.TerrainColumnState;

/**
 * Render-thread-owned submission evidence for one main view. This owns no GL objects.
 * Completed entries still require column/parent overlap resolution before placeholder removal.
 * The DH3.3 adapter supplies submission observations; this is not GPU completion or handoff proof.
 */
public final class DistantHorizonsFrameCoverage {
    private final Object world, frame, view;
    private final int capacity;
    private final Thread owner = Thread.currentThread();
    private final List<Selection> selections = new ArrayList<>();
    private boolean opaqueFinished, transparentFinished, composited, aborted;

    public DistantHorizonsFrameCoverage(Object world, Object frame, Object mainView, int capacity) {
        this.world = Objects.requireNonNull(world);
        this.frame = Objects.requireNonNull(frame);
        this.view = Objects.requireNonNull(mainView);
        if (capacity <= 0) throw new IllegalArgumentException("Coverage capacity must be positive");
        this.capacity = capacity;
    }

    /** Expected identities must come from the selected container's complete pass arrays. */
    public Selection select(DistantHorizonsCoverageTransfer.Attachment attachment,
                            List<?> opaque, List<?> transparent) {
        mutable();
        if (opaqueFinished || transparentFinished) throw new IllegalStateException("Selection after pass completion");
        if (selections.size() >= capacity) throw new IllegalStateException("Frame coverage capacity exhausted");
        var input = Objects.requireNonNull(attachment).inputFor(world).orElse(null);
        var selection = new Selection(this, attachment, input, identities(opaque), identities(transparent));
        selections.add(selection);
        return selection;
    }

    /** Record only after the actual draw call returns, never at list selection or VBO bind. */
    public void submitted(Selection selection, boolean opaque, Object vertexBuffer) {
        mutable();
        if (selection == null || selection.ledger != this) throw new IllegalArgumentException("Foreign selection");
        if (opaque ? opaqueFinished : transparentFinished) throw new IllegalStateException("Pass already completed");
        var expected = opaque ? selection.opaque : selection.transparent;
        if (!expected.contains(vertexBuffer)) throw new IllegalArgumentException("Unselected vertex buffer");
        (opaque ? selection.opaqueSubmitted : selection.transparentSubmitted).add(vertexBuffer);
    }

    public void finishPass(boolean opaque) {
        mutable();
        if (opaque) opaqueFinished = true;
        else transparentFinished = true;
    }

    /** Called only after successful final composition of this exact main view. */
    public void composited(Object frame, Object mainView) {
        mutable();
        if (this.frame != frame || view != mainView) {
            abort();
            return;
        }
        composited = true;
    }

    public List<DistantHorizonsCoverageTransfer.Input> completed(Object frame, Object mainView) {
        checkThread();
        if (aborted || !composited || !opaqueFinished || !transparentFinished
                || this.frame != frame || view != mainView) return List.of();
        var result = new ArrayList<DistantHorizonsCoverageTransfer.Input>();
        for (var selection : selections) {
            var input = selection.input;
            if (input == null || selection.attachment.inputFor(world).orElse(null) != input) continue;
            if (!selection.opaqueSubmitted.containsAll(selection.opaque)
                    || !selection.transparentSubmitted.containsAll(selection.transparent)) continue;
            if (input.coverage().sourceSummaryEmpty()) continue;
            boolean noGeometry = selection.opaque.isEmpty() && selection.transparent.isEmpty();
            if (noGeometry) {
                // A missing mesh, UNKNOWN column or skipped conversion is not evidence of empty space.
                if (input.coverage().columns().stream().anyMatch(s -> s != TerrainColumnState.REAL_AIR)) continue;
            } else if (!input.convertedNonEmpty()) continue;
            result.add(input);
        }
        return List.copyOf(result);
    }

    public void abort() { checkThread(); aborted = true; selections.clear(); }

    /** Pure scope payload: no optional DH classes are exposed to the world-pass coordinator. */
    public record Snapshot(Object frame, Object view, DistantHorizonsFrameCoverage ledger) {
        public Snapshot {
            Objects.requireNonNull(frame); Objects.requireNonNull(view); Objects.requireNonNull(ledger);
        }
        public List<DistantHorizonsCoverageTransfer.Input> completed(Object currentFrame) {
            return frame == currentFrame ? ledger.completed(frame, view) : List.of();
        }
        public List<DistantHorizonsCoverageTransfer.Input> completedFor(Object world, Object currentFrame) {
            return ledger.world == world ? completed(currentFrame) : List.of();
        }
    }
    private void mutable() {
        checkThread();
        if (aborted || composited) throw new IllegalStateException("Frame coverage is sealed");
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Frame coverage used off render owner thread");
    }
    private static Set<Object> identities(List<?> values) {
        var result = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object value : values) result.add(Objects.requireNonNull(value, "Null VBO cannot prove coverage"));
        return result;
    }

    public static final class Selection {
        private final DistantHorizonsFrameCoverage ledger;
        private final DistantHorizonsCoverageTransfer.Attachment attachment;
        private final DistantHorizonsCoverageTransfer.Input input;
        private final Set<Object> opaque, transparent;
        private final Set<Object> opaqueSubmitted = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Object> transparentSubmitted = Collections.newSetFromMap(new IdentityHashMap<>());
        private Selection(DistantHorizonsFrameCoverage ledger, DistantHorizonsCoverageTransfer.Attachment attachment,
                          DistantHorizonsCoverageTransfer.Input input, Set<Object> opaque, Set<Object> transparent) {
            this.ledger = ledger; this.attachment = attachment; this.input = input;
            this.opaque = opaque; this.transparent = transparent;
        }
    }
}
