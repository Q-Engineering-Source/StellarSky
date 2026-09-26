package stellarium.client.ring.dh;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import java.util.Arrays;
import java.util.IdentityHashMap;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldRenderSnapshots;

/** Observational DH3.3 adapter. Unsupported render paths retain their placeholders. */
public final class DistantHorizonsFrameCoverageBridge {
    private static final int MAX_SELECTED = 16384;
    private static final ThreadLocal<Invocation> ACTIVE = new ThreadLocal<>();
    private DistantHorizonsFrameCoverageBridge() {}

    public static void render(RenderParams params, boolean supported, Runnable original) {
        Invocation previous = ACTIVE.get();
        var frame = DistantHorizonsCurvatureState.currentFrame();
        Invocation current = supported && frame != null && params.dhClientLevel != null
                ? new Invocation(frame, params) : null;
        ACTIVE.set(current);
        if (previous == null || current != null) RingworldRenderSnapshots.captureDistantCoverage(null);
        try {
            original.run();
            if (current != null && !current.aborted && current.composited) {
                RingworldRenderSnapshots.captureDistantCoverage(
                        new DistantHorizonsFrameCoverage.Snapshot(current.frame, current, current.ledger));
            }
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    public static void beginPass(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> buffers) {
        var current = active(params);
        if (current == null) return;
        if (current.composited || (opaque ? current.opaqueStarted : current.transparentStarted)) {
            current.abort();
            return;
        }
        if (opaque) current.opaqueStarted = true; else current.transparentStarted = true;
        if (!opaque) return;
        if (buffers == null || buffers.size() > MAX_SELECTED) { current.abort(); return; }
        for (int i = 0; i < buffers.size(); i++) {
            var buffer = buffers.get(i);
            if (!buffer.buffersUploaded || !(buffer instanceof DistantHorizonsCoverageBuffer carrier)) continue;
            var attachment = carrier.stellarium$coverageAttachment();
            if (attachment.inputFor(params.dhClientLevel).isEmpty()) continue;
            var opaqueBuffers = buffer.vboOpaqueWrappers;
            var transparentBuffers = buffer.vboTransparentWrappers;
            // A null/retired wrapper is unresolved, never a zero-geometry air proof.
            if (opaqueBuffers == null || transparentBuffers == null
                    || Arrays.stream(opaqueBuffers).anyMatch(v -> v == null)
                    || Arrays.stream(transparentBuffers).anyMatch(v -> v == null)) continue;
            current.selections.put(buffer, current.ledger.select(attachment,
                    Arrays.asList(opaqueBuffers), Arrays.asList(transparentBuffers)));
        }
    }

    public static void submitted(RenderParams params, boolean opaque, LodBufferContainer buffer, Object vbo) {
        var current = active(params);
        if (current == null) return;
        var selection = current.selections.get(buffer);
        if (selection != null) current.ledger.submitted(selection, opaque, vbo);
    }

    public static void finishPass(RenderParams params, boolean opaque) {
        var current = active(params);
        if (current != null) current.ledger.finishPass(opaque);
    }

    public static void composited(RenderParams params) {
        var current = active(params);
        if (current == null) return;
        if (current.composited) { current.abort(); return; }
        current.ledger.composited(current.frame, current);
        current.composited = true;
    }

    public static void copyToMain(RenderParams params, Runnable original) {
        var current = active(params);
        if (current == null) { original.run(); return; }
        if (current.copying) { current.abort(); original.run(); return; }
        current.copying = true;
        current.copyDrawn = false;
        try {
            original.run();
            if (current.copyDrawn) composited(params);
        } finally { current.copying = false; }
    }

    /** Called only by the copy shader after its screen-quad draw returns. */
    public static void copiedQuad() {
        var current = ACTIVE.get();
        if (current != null && !current.aborted && current.copying) current.copyDrawn = true;
    }

    private static Invocation active(RenderParams params) {
        var current = ACTIVE.get();
        if (current == null || current.aborted) return null;
        if (current.params != params || current.frame != DistantHorizonsCurvatureState.currentFrame()
                || current.world != params.dhClientLevel) {
            current.abort();
            return null;
        }
        return current;
    }

    private static final class Invocation {
        private final RingworldCurvatureFrame frame;
        private final RenderParams params;
        private final Object world;
        private final DistantHorizonsFrameCoverage ledger;
        private final IdentityHashMap<LodBufferContainer, DistantHorizonsFrameCoverage.Selection> selections = new IdentityHashMap<>();
        private boolean opaqueStarted, transparentStarted, composited, aborted, copying, copyDrawn;
        private Invocation(RingworldCurvatureFrame frame, RenderParams params) {
            this.frame = frame; this.params = params; this.world = params.dhClientLevel;
            ledger = new DistantHorizonsFrameCoverage(world, frame, this, MAX_SELECTED);
        }
        private void abort() { aborted = true; ledger.abort(); selections.clear(); }
    }

}
