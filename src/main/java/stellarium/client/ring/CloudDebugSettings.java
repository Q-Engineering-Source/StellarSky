package stellarium.client.ring;

import java.util.Locale;

/**
 * Client-local, session-only controls and counters for the cloud mesh overlay.
 * The renderer publishes its most recently submitted near-cloud geometry after a
 * successful frame; no setting is written to the Minecraft configuration.
 */
public final class CloudDebugSettings {
    public enum MaterialMode {
        EXACT, CACHED;

        public static MaterialMode fromCommand(String value) {
            if (value == null) throw new IllegalArgumentException("Cloud material mode is required");
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "exact" -> EXACT;
                case "cached" -> CACHED;
                default -> throw new IllegalArgumentException("Unknown cloud material mode: " + value);
            };
        }
        public String commandName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Mode {
        OFF, VERTICES, TRIANGLES, BOTH;

        public static Mode fromCommand(String value) {
            if (value == null) throw new IllegalArgumentException("Cloud debug mode is required");
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "off" -> OFF;
                case "vertices" -> VERTICES;
                case "triangles" -> TRIANGLES;
                case "both" -> BOTH;
                default -> throw new IllegalArgumentException("Unknown cloud debug mode: " + value);
            };
        }

        public String commandName() { return name().toLowerCase(Locale.ROOT); }
        public boolean drawsVertices() { return this == VERTICES || this == BOTH; }
        public boolean drawsTriangles() { return this == TRIANGLES || this == BOTH; }
    }

    /** Counts submitted debug geometry, not post-rasterization visible pixels. */
    public record Stats(int pages, int batches, long quads) {
        public Stats {
            if (pages < 0 || batches < 0 || quads < 0L) throw new IllegalArgumentException("Cloud debug counters cannot be negative");
        }
        static final Stats EMPTY = new Stats(0, 0, 0L);
        public long submittedCorners() { return Math.multiplyExact(quads, 4L); }
        public long submittedTriangles() { return Math.multiplyExact(quads, 2L); }
    }

    private static Mode mode = Mode.OFF;
    private static MaterialMode materialMode = MaterialMode.CACHED;
    private static Stats stats = Stats.EMPTY;
    private static String curvature = "curvature=not-rendered";

    private CloudDebugSettings() { }

    public static synchronized Mode mode() { return mode; }
    public static synchronized boolean enabled() { return mode != Mode.OFF; }
    public static synchronized Stats stats() { return stats; }
    public static synchronized MaterialMode materialMode() { return materialMode; }

    public static synchronized void setMaterialMode(MaterialMode value) {
        if (value == null) throw new IllegalArgumentException("Cloud material mode is required");
        materialMode = value;
    }

    public static synchronized void setMode(Mode value) {
        if (value == null) throw new IllegalArgumentException("Cloud debug mode is required");
        mode = value;
        stats = Stats.EMPTY;
    }

    public static synchronized void publishStats(int pages, int batches, long quads) {
        stats = new Stats(pages, batches, quads);
    }

    public static synchronized void publishCurvature(double radius, double tolerance, int slabs, long submitted, boolean local) {
        curvature = String.format(Locale.ROOT, "radiusAU=%.9f planarErrorM=%.4f localDrawSlabs=%d nearSubmittedQuads=%d nearMaterial=%s",
                radius / 149597870700.0, tolerance, slabs, submitted, local ? "local" : "embedded");
    }

    public static synchronized String status() {
        return "SS cloud material=" + materialMode.commandName() + " debug=" + mode.commandName() + " lod=0..3 pages=" + stats.pages()
                + " batches=" + stats.batches() + " quads=" + stats.quads()
                + " corners=" + stats.submittedCorners() + " triangles=" + stats.submittedTriangles()
                + " (per-quad corners; positions are not deduplicated; submitted 3D mesh, not visible-pixel count) " + curvature;
    }
}
