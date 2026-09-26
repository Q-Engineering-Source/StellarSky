package stellarium.client.ring;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import stellarium.StellarSky;

/**
 * One-shot draw diagnostics for {@code /ssmodel diagnose}.
 *
 * <p>One request is consumed by exactly one optical pass. A single frame can submit the
 * key reductions twice: the early pass reduces them, and a settle that observed DH
 * retirement reduces them again before its colour pass. Each submission is therefore
 * recorded separately with its phase, and each gets its own query object, so an early
 * sample can never be reported as part of the late colour selection.</p>
 *
 * <p>Every GPU number is an asynchronous {@code GL_SAMPLES_PASSED} count of fragments that
 * reached the depth test for that submission. High/low counts describe key reductions and
 * are not unique pixels; the colour count is not a final on-screen proof, because later
 * layers and the managed framebuffer composition still apply.</p>
 *
 * <p>No seed is read here, and while idle no query is created, no GL state is read and no
 * tile is traversed.</p>
 */
final class RingworldDrawDiagnostics {
    static final int HIGH = RingworldMeshDistance.HIGH;
    static final int LOW = RingworldMeshDistance.LOW;
    static final int COLOR = RingworldMeshDistance.COLOR;
    static final String PREFIX = "SS DRAW DIAG";
    /** Bounded per-tile report, so one command can never emit an unbounded log. */
    static final int MAX_TILE_LINES = 48;
    /** Bounded per-submission records: early/late high, low and the colour pass. */
    static final int MAX_SUBMISSIONS = 8;
    /** Frames we keep polling for an asynchronous result before reporting it as pending. */
    static final int MAX_POLL_FRAMES = 180;
    /** An armed request that no render pass ever consumes is reported instead of lingering. */
    static final long MAX_ARMED_NANOS = 5_000_000_000L;

    enum State { IDLE, ARMED, SAMPLING, AWAITING_RESULTS, COMPLETED, SKIPPED, CANCELLED }

    enum Phase { EARLY, LATE }

    private static State state = State.IDLE;
    private static long serial;
    private static long armedAt;
    private static int pollFrames;
    private static boolean sampled;
    private static Phase phase = Phase.EARLY;
    private static int count;
    private static int overflow;
    private static final Phase[] recordPhase = new Phase[MAX_SUBMISSIONS];
    private static final int[] recordStage = new int[MAX_SUBMISSIONS];
    private static final long[] recordDraws = new long[MAX_SUBMISSIONS];
    private static final long[] recordVertices = new long[MAX_SUBMISSIONS];
    /** -1 pending, -2 skipped, >= 0 consumed samples. */
    private static final long[] recordSamples = new long[MAX_SUBMISSIONS];
    private static final int[] recordQuery = new int[MAX_SUBMISSIONS];
    private static final boolean[] recordOpen = new boolean[MAX_SUBMISSIONS];
    private static final int[] recordReserved = {-1, -1, -1};
    private static final java.nio.IntBuffer ids = BufferUtils.createIntBuffer(1);
    private static final java.nio.IntBuffer masks = BufferUtils.createIntBuffer(4);
    private static String scopeLine = "";
    private static String terrainLine = "";
    private static String proxyLine = "";
    private static String bindingLine = "";
    private static String outcome = "";
    private static List<String> tileLines = List.of();
    private static int releasesWhileArmed;

    private RingworldDrawDiagnostics() { }

    // ---------------------------------------------------------------- request lifecycle

    /**
     * Arms one diagnostic pass.
     *
     * <p>{@code unavailable} is non-null when the renderer can prove it cannot sample (for
     * example the model is disabled); the command then reports that reason and stays idle
     * instead of leaving a request pending forever.</p>
     */
    static String request(String unavailable) {
        if (unavailable != null) return "unavailable: " + unavailable + " (state=" + state + ")";
        // Simple ownership rule: while any request is active, a new command is refused
        // outright. Nothing is reset and no query id is dropped here, because the record
        // arrays are the only place those ids are known until the render side has released
        // them; the render pass times the old request out and cleans up, after which the
        // next command is accepted normally.
        if (active()) {
            return "busy: request=#" + serial + " state=" + state
                    + " (cleanup happens on the next render pass; retry after it reports)";
        }
        serial++;
        state = State.ARMED;
        armedAt = System.nanoTime();
        pollFrames = 0;
        sampled = false;
        phase = Phase.EARLY;
        releasesWhileArmed = 0;
        clearRecords();
        outcome = "";
        scopeLine = terrainLine = proxyLine = bindingLine = "";
        tileLines = List.of();
        return "queued #" + serial + " (single optical pass; log prefix " + PREFIX + ")";
    }

    /** True once an in-flight request has outlived its finite sampling deadline. */
    private static boolean stale() {
        return System.nanoTime() - armedAt > MAX_ARMED_NANOS;
    }

    /*
     * The former orphan hand-over branch (preserveOwnedQueriesForReclaim / reclaimOrphans) is
     * gone on purpose: the request lifecycle now refuses every command while a request is
     * active, so no query id is ever handed to another owner and none can be left unreferenced.
     */

    /** Cheap idle gate for the render hot path. */
    static boolean active() {
        return state == State.ARMED || state == State.SAMPLING || state == State.AWAITING_RESULTS;
    }

    static State state() { return state; }

    /** True only while the armed pass is still collecting this frame's submissions. */
    static boolean sampling() { return state == State.SAMPLING; }

    // ---------------------------------------------------------------- frame boundaries

    /** Opens the accounting window on the early pass of the armed frame. */
    static void frameBegun() {
        // Watchdog first: the early pass is the entry that can still run when a settle never
        // does (dimension change, skipped render branch), so a request stuck in ARMED or
        // SAMPLING is reported and released here instead of lingering with an open query.
        if ((state == State.ARMED || state == State.SAMPLING) && stale()) {
            log(cancel(state == State.ARMED ? "neverSampled" : "samplingNeverFinished"));
            chat();
            deleteQueries();
        }
        if (state != State.ARMED) return;
        state = State.SAMPLING;
        sampled = true;
        phase = Phase.EARLY;
        clearRecords();
    }

    /** Switches to the late settle: its submissions are recorded separately from the early ones. */
    static void phaseLate() {
        if (state == State.SAMPLING) phase = Phase.LATE;
    }

    static void scope(String line) { if (sampling()) scopeLine = line; }

    static void terrain(String line) { if (sampling()) terrainLine = line; }

    static void proxy(String line) { if (sampling()) proxyLine = line; }

    /** Bounded per-tile lines; extras are counted, never silently merged. */
    static void tiles(List<String> lines, int total) {
        if (!sampling()) return;
        List<String> bounded = new ArrayList<>(Math.min(lines.size(), MAX_TILE_LINES));
        for (int i = 0; i < lines.size() && i < MAX_TILE_LINES; i++) bounded.add(lines.get(i));
        if (total > MAX_TILE_LINES) bounded.add(PREFIX + " tilesTruncated=" + (total - MAX_TILE_LINES));
        tileLines = List.copyOf(bounded);
    }

    /** Colour-stage GL state, read only after the normal bindings are already in place. */
    static void colorBinding(String line) { if (sampling()) bindingLine = line; }

    /** Closes the accounting window at the end of the settle of the armed frame. */
    static void frameFinished() {
        if (state != State.SAMPLING) return;
        state = State.AWAITING_RESULTS;
        pollFrames = 0;
    }

    // ---------------------------------------------------------------- GL glue (never unit tested)

    private static int reserve() {
        if (count < MAX_SUBMISSIONS) return count;
        overflow++;
        return -1;
    }

    /**
     * Opens one submission's occlusion query when the target is free.
     *
     * <p>{@code GL_CURRENT_QUERY} is reported per target: a non-zero owner means another
     * subsystem already holds it, so this submission is recorded as SKIPPED instead of
     * nesting, replacing or truncating anyone else's query.</p>
     */
    /**
     * Reserves this submission's record slot and marks it pending.
     *
     * <p>This half is pure bookkeeping, so it can be exercised without a GL context; the
     * caller is responsible for opening the query and later recording the result.</p>
     */
    static int reserveSlot(int stage) {
        if (state != State.SAMPLING || stage < HIGH || stage > COLOR) return -1;
        int index = reserve();
        if (index < 0) return -1;
        count = index + 1;
        recordReserved[stage] = index;
        recordPhase[index] = phase;
        recordStage[index] = stage;
        recordDraws[index] = 0L;
        recordVertices[index] = 0L;
        recordQuery[index] = -1;
        recordOpen[index] = false;
        recordSamples[index] = -1L;
        return index;
    }

    static void beginQuery(int stage) {
        int index = reserveSlot(stage);
        if (index < 0) return;
        // Ownership is checked before anything is created: a target that already has an
        // active query gets no query object at all, so this submission has nothing to poll
        // and nothing to leak, and the other subsystem's query is left untouched.
        if (GL15.glGetQueryi(GL15.GL_SAMPLES_PASSED, GL15.GL_CURRENT_QUERY) != 0) {
            skipSlot(index, "queryTargetBusy");
            return;
        }
        // One query object per submission: an early and a late HIGH/LOW must never share an
        // id, or the second begin would overwrite the first submission's pending result.
        ids.clear();
        GL15.glGenQueries(ids);
        int query = ids.get(0);
        if (query <= 0) { skipSlot(index, "queryAllocationFailed"); return; }
        recordQuery[index] = query;
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
        recordOpen[index] = true;
    }

    /**
     * Marks one slot as deliberately not sampled.
     *
     * <p>The pure half of a refused or failed query, paired with {@link #recordResult} so the
     * reporting rules can be exercised without a GL context.</p>
     */
    static void skipSlot(int slot, String reason) {
        if (slot < 0 || slot >= count) return;
        recordSamples[slot] = -2L;
        outcome = reason;
    }

    /** Closes the submission's query and records its real submission counts. */
    static void endStage(int stage, long stageDraws, long stageVertices) {
        int index = stage >= HIGH && stage <= COLOR ? recordReserved[stage] : -1;
        if (index < 0) return;
        recordReserved[stage] = -1;
        recordDraws[index] = Math.max(0L, stageDraws);
        recordVertices[index] = Math.max(0L, stageVertices);
        if (recordOpen[index]) {
            recordOpen[index] = false;
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
        }
    }

    /** Records an already-available result for one slot; the pure half of the poll path. */
    static void recordResult(int slot, long passedSamples) {
        if (slot < 0 || slot >= count) return;
        recordSamples[slot] = Math.max(0L, passedSamples);
    }

    /** Reads only already available results; never waits. */
    static void pollQueries() {
        for (int i = 0; i < count; i++) {
            if (recordQuery[i] <= 0 || recordOpen[i] || recordSamples[i] != -1L) continue;
            if (GL15.glGetQueryObjecti(recordQuery[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            recordResult(i, GL15.glGetQueryObjecti(recordQuery[i], GL15.GL_QUERY_RESULT) & 0xFFFFFFFFL);
        }
    }

    /** Releases every query object owned here; safe to call repeatedly. */
    static void deleteQueries() {
        for (int i = 0; i < count; i++) {
            if (recordOpen[i]) { recordOpen[i] = false; GL15.glEndQuery(GL15.GL_SAMPLES_PASSED); }
            if (recordQuery[i] > 0) {
                ids.clear();
                ids.put(recordQuery[i]).flip();
                GL15.glDeleteQueries(ids);
            }
            recordQuery[i] = -1;
        }
    }

    // ---------------------------------------------------------------- results and lifetime

    /** Advances the poll window; true once every recorded submission is terminal. */
    static boolean readyToReport() {
        if (state != State.AWAITING_RESULTS) return false;
        for (int i = 0; i < count; i++) if (recordSamples[i] == -1L) return false;
        return true;
    }

    static boolean pollTimedOut() {
        if (state != State.AWAITING_RESULTS) return false;
        pollFrames++;
        return pollFrames > MAX_POLL_FRAMES;
    }

    /**
     * One render pass's worth of bookkeeping.
     *
     * <p>Every state that can hold query objects has a finite deadline: an armed request no
     * pass ever consumed, a sampling window no settle ever finished, and a result window
     * whose driver never reports availability are all reported and released here. Results are
     * only consumed when already available; nothing waits and nothing is finished early.</p>
     */
    static void tick() {
        if (state == State.ARMED || state == State.SAMPLING) {
            if (stale()) {
                log(cancel(state == State.ARMED ? "neverSampled" : "samplingNeverFinished"));
                chat();
                deleteQueries();
            }
            return;
        }
        if (state != State.AWAITING_RESULTS) return;
        pollQueries();
        if (readyToReport()) {
            log(complete(System.nanoTime() - armedAt));
            chat();
            finishRun();
        } else if (pollTimedOut()) {
            log(cancel("timeoutPendingResults"));
            chat();
            finishRun();
        }
    }

    /**
     * Renderer teardown (world change, resource reload, disable).
     *
     * <p>An armed request that has not sampled yet survives, because the pass that would
     * sample it may simply be one initialisation away; only a sampled or pending request is
     * cancelled here, so an early command can never be cleared away silently.</p>
     */
    static void onRendererRelease() {
        if (state == State.ARMED) { releasesWhileArmed++; return; }
        if (state == State.SAMPLING || state == State.AWAITING_RESULTS) {
            log(cancel("released_" + state));
            chat();
        }
        deleteQueries();
    }

    private static void finishRun() { deleteQueries(); }

    private static void chat() {
        var player = Minecraft.getMinecraft().player;
        if (player == null) return;
        player.sendMessage(new TextComponentString(PREFIX + " request=#" + serial + " state=" + state
                + " " + summary()));
    }

    /** Compact stage summary: the late colour submission is named separately from the keys. */
    static String summary() {
        if (count == 0) return "noSubmission";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) text.append(',');
            text.append(recordPhase[i]).append('_').append(stageName(recordStage[i]))
                    .append('=').append(sampleText(recordSamples[i]));
        }
        if (overflow > 0) text.append(" overflow=").append(overflow);
        return text.toString();
    }

    static String statusLine() {
        if (state == State.IDLE) return "diagnostics=idle";
        return "diagnostics=" + state + " request=#" + serial + " pollFrames=" + pollFrames
                + (sampled ? " sampled=1" : " sampled=0") + " releasesWhileArmed=" + releasesWhileArmed;
    }

    static List<String> reportLines() {
        if (state == State.IDLE) return List.of();
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + " request=#" + serial + " state=" + state + " outcome="
                + (outcome.isEmpty() ? "ok" : outcome) + " submissions=" + count
                + (overflow > 0 ? "+" + overflow + "dropped" : ""));
        if (!scopeLine.isEmpty()) lines.add(PREFIX + " scope " + scopeLine);
        if (!terrainLine.isEmpty()) lines.add(PREFIX + " terrain " + terrainLine);
        if (!proxyLine.isEmpty()) lines.add(PREFIX + " proxy " + proxyLine);
        lines.addAll(tileLines);
        for (int i = 0; i < count; i++) {
            lines.add(PREFIX + " submit[" + i + "] phase=" + recordPhase[i]
                    + " stage=" + stageName(recordStage[i]) + " draws=" + recordDraws[i]
                    + " verts=" + recordVertices[i] + " samplesPassed=" + sampleText(recordSamples[i]));
        }
        lines.add(PREFIX + " stages " + summary());
        if (!bindingLine.isEmpty()) lines.add(PREFIX + " glcolor " + bindingLine);
        lines.add(PREFIX + " note samplesPassedAreFragmentsNotUniquePixels=1 colourIsNotScreenProof=1"
                + " earlyAndLateKeysAreSeparateSubmissions=1 seedExcluded=1"
                + " sampledAnchorRangeIsPerColumnAnchorNotNearestGeometry=1"
                + " submittedMeshesCountsMeshAndTileSubmissionsNotGlDrawArraysCalls=1");
        return List.copyOf(lines);
    }

    static List<String> complete(long elapsedNanos) {
        state = State.COMPLETED;
        List<String> lines = new ArrayList<>(reportLines());
        lines.add(PREFIX + " done elapsedMs=" + (elapsedNanos / 1_000_000L));
        return List.copyOf(lines);
    }

    static List<String> cancel(String reason) {
        state = State.CANCELLED;
        outcome = reason;
        List<String> lines = new ArrayList<>(reportLines());
        lines.add(PREFIX + " cancelled reason=" + reason);
        return List.copyOf(lines);
    }

    /**
     * Full production reset: releases every owned query first, then forgets the records.
     *
     * <p>The deletion must happen before the records are cleared, because the record array is
     * the only place those ids are known; clearing first would drop the last reference and
     * leak the query objects.</p>
     */
    static void clear() {
        deleteQueries();
        clearRecords();
        sampled = false;
        releasesWhileArmed = 0;
        outcome = "";
        scopeLine = terrainLine = proxyLine = bindingLine = "";
        tileLines = List.of();
        state = State.IDLE;
    }

    /**
     * GL-free reset for tests and for callers that know they own no query.
     *
     * <p>Deliberately does not call any GL entry point; it is only valid while no query was
     * ever opened for the current records, which the state machine guarantees before the
     * first {@code beginQuery}.</p>
     */
    static void resetWithoutQueries() {
        // This is the only reset that does not delete queries, so it must prove it owns none:
        // dropping a live id here would leak the object exactly like clearing records first.
        for (int i = 0; i < count; i++) {
            if (recordQuery[i] > 0 || recordOpen[i]) {
                throw new IllegalStateException("resetWithoutQueries with an owned query in slot " + i);
            }
        }
        clearRecords();
        sampled = false;
        releasesWhileArmed = 0;
        outcome = "";
        scopeLine = terrainLine = proxyLine = bindingLine = "";
        tileLines = List.of();
        state = State.IDLE;
    }

    static void log(List<String> lines) {
        for (String line : lines) StellarSky.INSTANCE.getLogger().info(line);
    }

    private static void clearRecords() {
        count = 0;
        overflow = 0;
        phase = Phase.EARLY;
        for (int i = 0; i < MAX_SUBMISSIONS; i++) {
            recordPhase[i] = null;
            recordStage[i] = -1;
            recordDraws[i] = 0L;
            recordVertices[i] = 0L;
            recordSamples[i] = -1L;
            recordQuery[i] = -1;
            recordOpen[i] = false;
        }
        recordReserved[HIGH] = recordReserved[LOW] = recordReserved[COLOR] = -1;
    }

    private static String stageName(int stage) {
        return stage == HIGH ? "HIGH" : stage == LOW ? "LOW" : "COLOR";
    }

    private static String sampleText(long value) {
        return value == -1L ? "PENDING" : value == -2L ? "SKIPPED" : Long.toString(value);
    }

    /** Reads the colour stage's real GL state; callers invoke this after normal binding. */
    static String colorGlState(int unit3Expected, int unit4Expected, int unit5Expected, int unit6Expected) {
        int fbo = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int drawBuffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        masks.clear();
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, masks);
        StringBuilder text = new StringBuilder("fbo=").append(fbo).append(" drawBuffer=").append(drawBuffer)
                .append(" depthFunc=").append(depthFunc).append(" depthMask=").append(depthMask ? 1 : 0)
                .append(" colorMask=").append(masks.get(0) != 0 ? 1 : 0).append(masks.get(1) != 0 ? 1 : 0)
                .append(masks.get(2) != 0 ? 1 : 0).append(masks.get(3) != 0 ? 1 : 0);
        int previousUnit = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        int[] units = {3, 4, 5, 6};
        int[] expected = {unit3Expected, unit4Expected, unit5Expected, unit6Expected};
        try {
            for (int i = 0; i < units.length; i++) {
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + units[i]);
                int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                text.append(" unit").append(units[i]).append('=').append(bound)
                        .append('/').append(expected[i]).append(bound == expected[i] ? "" : "(MISMATCH)");
            }
        } finally {
            // The active unit belongs to the caller; restore it even if a read failed.
            org.lwjgl.opengl.GL13.glActiveTexture(previousUnit);
        }
        return text.toString();
    }
}
