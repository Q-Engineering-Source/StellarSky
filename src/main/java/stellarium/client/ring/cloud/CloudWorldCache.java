package stellarium.client.ring.cloud;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/** Frozen CPU-authoritative pages for one material-world target. */
public final class CloudWorldCache {
    public static final long SOURCE_PAGE_BYTES = sourcePageBytes();
    /** Includes immutable pages, fine-mask defensive clone, and packed direct upload source. */
    public static final long STEADY_CPU_BYTES = SOURCE_PAGE_BYTES
            + (long) CloudLodLayout.LOD0_FINE_3D.width() * CloudLodLayout.LOD0_FINE_3D.depth()
            * CloudLodLayout.LOD0_FINE_3D.layers() * Integer.BYTES + CloudLodLayout.ATLAS_BYTE_SIZE;
    /** One published and one candidate package; streamer never queues a third candidate. */
    public static final long TRANSITION_CPU_BYTES = STEADY_CPU_BYTES * 2L;
    /** Mesh arrays and render-thread direct staging are separately bounded, but total process peak is intentionally unclaimed. */
    public static final long MAX_MESH_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_UPLOAD_STAGING_BYTES = 64L * 1024L * 1024L;
    private final long generation;
    private final CloudFieldSettings settings;
    private final CloudGeometrySettings geometry;
    private final CloudWorldField.Sampler sampler;
    private final List<Page> pages;
    private final CloudMask fineMask;
    private final ByteBuffer rgba8;

    private CloudWorldCache(long generation, CloudFieldSettings settings, CloudGeometrySettings geometry, CloudWorldField.Sampler sampler, List<Page> pages, CloudMask fineMask, ByteBuffer rgba8) {
        this.generation = generation; this.settings = settings; this.geometry = geometry; this.sampler = sampler; this.pages = pages; this.fineMask = fineMask; this.rgba8 = rgba8;
    }

    public static CloudWorldCache around(long generation, CloudFieldSettings settings, CloudGeometrySettings geometry,
                                         long materialCellX, long materialCellZ) {
        return around(generation, settings, geometry, materialCellX, materialCellZ, null);
    }

    /** Reuses exact immutable overlap cells; only newly exposed strips sample noise. */
    public static CloudWorldCache around(long generation, CloudFieldSettings settings, CloudGeometrySettings geometry,
                                         long materialCellX, long materialCellZ, CloudWorldCache previous) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(geometry, "geometry");
        requireBandCoverage(geometry);
        List<CloudLodLayout.AtlasLevel> levels = CloudLodLayout.ATLAS_LEVELS;
        CloudWorldField.Sampler sampler = previous != null && settings.equals(previous.settings) ? previous.sampler : CloudWorldField.sampler(settings);
        Page[] pages = new Page[levels.size()];
        CloudMask fineMask = null;
        ByteBuffer atlas = ByteBuffer.allocateDirect(CloudLodLayout.ATLAS_BYTE_SIZE).order(ByteOrder.nativeOrder());
        for (int i = 0; i < levels.size(); i++) {
            CloudLodLayout.AtlasLevel level = levels.get(i);
            long centerX = Math.floorDiv(materialCellX, level.xzScale());
            long centerZ = Math.floorDiv(materialCellZ, level.xzScale());
            long originZ = level.isThreeDimensional() ? Math.subtractExact(centerZ, level.depth() / 2L) : -level.depth() / 2L;
            Page page = new Page(level, Math.subtractExact(centerX, level.width() / 2L), originZ);
            pages[i] = page;
            Page old = previous == null || !settings.equals(previous.settings) || !geometry.equals(previous.geometry) ? null : previous.pages.get(i);
            int[] cells = materialize(page, sampler, geometry.cellSizeBlocks(), old);
            pages[i] = page = new Page(level, page.originX(), page.originZ(), cells);
            write(atlas, page);
            if (level == CloudLodLayout.LOD0_FINE_3D) fineMask = CloudMask.window(generation, page.originX(), page.originZ(), level.width(), level.depth(), level.layers(), cells);
        }
        atlas.position(0).limit(CloudLodLayout.ATLAS_BYTE_SIZE);
        return new CloudWorldCache(generation, settings, geometry, sampler, List.of(pages), Objects.requireNonNull(fineMask, "fineMask"), atlas);
    }

    /** Reject a joint setting rather than silently exposing an under-sized page. */
    /** Public default config-load validation, retaining the original 16,384m last-3D requirement. */
    public static void requireBandCoverage(CloudGeometrySettings geometry) {
        requireBandCoverage(geometry, (int) CloudLodLayout.VERY_LOW_3D_END_DISTANCE);
    }

    /**
     * Public config-load validation for the actual requested detail horizon.
     * It validates the DDA-reduced horizon rather than pretending an accepted
     * 65,536m request still stops at the old 16,384m cache page.
     */
    public static void requireBandCoverage(CloudGeometrySettings geometry, int requestedHorizonBlocks) {
        Objects.requireNonNull(geometry, "geometry");
        CloudRayBudget.PreparedHorizon horizon = CloudRayBudget.prepare(requestedHorizonBlocks, geometry);
        double cell = geometry.cellSizeBlocks();
        requireHalfWidth(CloudLodLayout.LOD0_FINE_3D, cell, CloudLodLayout.FINE_3D_END_DISTANCE + 1_024.0D);
        requireHalfWidth(CloudLodLayout.LOD1_MID_3D, cell, CloudLodLayout.MID_3D_END_DISTANCE + 1_024.0D);
        requireHalfWidth(CloudLodLayout.LOD2_LOW_3D, cell, CloudLodLayout.LOW_3D_END_DISTANCE);
        double finalThreeD = Math.max(CloudLodLayout.VERY_LOW_3D_END_DISTANCE, horizon.effectiveHorizon());
        requireHalfWidth(CloudLodLayout.LOD3_VERY_LOW_3D, cell, finalThreeD + 1_024.0D);
        requireTwoDPage(CloudLodLayout.LOD4_2D_HIGH, cell, CloudLodLayout.HIGH_2D_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD5_2D_MID, cell, CloudLodLayout.MID_2D_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD6_2D_LOW, cell, CloudLodLayout.LOW_OBSERVATION_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD7_2D_LOWER, cell, CloudLodLayout.LOW_2D_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD8_2D_TAIL, cell, CloudLodLayout.TAIL8_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD9_2D_TAIL, cell, CloudLodLayout.TAIL9_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD10_2D_TAIL, cell, CloudLodLayout.TAIL10_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD11_2D_TAIL, cell, CloudLodLayout.TAIL11_END_DISTANCE);
        requireTwoDPage(CloudLodLayout.LOD12_2D_TAIL, cell, CloudLodLayout.MAX_CACHED_TAIL_DISTANCE);
    }
    private static void requireHalfWidth(CloudLodLayout.AtlasLevel level, double cell, double required) {
        if (level.width() * cell * level.xzScale() / 2.0D < required) {
            throw new IllegalArgumentException("SS_Cloud_Cell_Size >= 12 is required; actual " + cell
                    + " cannot cover the requested cloud LOD horizon without silent rescaling");
        }
    }
    private static void requireTwoDPage(CloudLodLayout.AtlasLevel level, double cell, double requiredX) {
        requireHalfWidth(level, cell, requiredX);
        double cellWidth = cell * level.xzScale();
        double negativeZ = -level.depth() / 2.0D * cellWidth;
        double positiveZ = (level.depth() - level.depth() / 2.0D) * cellWidth;
        if (negativeZ > -8_192.0D - cellWidth || positiveZ < 8_192.0D + cellWidth) {
            throw new IllegalArgumentException("SS cloud 2D cache does not cover the finite ring strip with a Z halo");
        }
    }
    private static long sourcePageBytes() {
        long texels = 0L;
        for (CloudLodLayout.AtlasLevel level : CloudLodLayout.ATLAS_LEVELS) {
            texels += (long) level.width() * level.depth() * level.layers();
        }
        return texels * Integer.BYTES;
    }

    private static int[] materialize(Page page, CloudWorldField.Sampler sampler, double finestCellSize, Page old) {
        CloudLodLayout.AtlasLevel level = page.level();
        int[] cells = new int[level.width() * level.depth() * level.layers()];
        if (old != null) copyOverlap(old, page, cells);
        CloudColumn column = new CloudColumn();
        int[] reduced = new int[CloudColumn.LAYERS];
        for (int z = 0; z < level.depth(); z++) for (int x = 0; x < level.width(); x++) {
            long wx = page.originX() + x, wz = page.originZ() + z;
            if (old != null && old.contains(wx, wz)) continue;
            double physicalCell = finestCellSize * level.xzScale();
            if (level.layers() == 1) sampler.fillFilteredColumn(wx * physicalCell, wz * physicalCell, physicalCell, column);
            else sampler.fillColumn(wx * physicalCell, wz * physicalCell, column);
            CloudWorldField.reduceInto(level.layers(), column, reduced);
            for (int layer = 0; layer < level.layers(); layer++) cells[(layer * level.depth() + z) * level.width() + x] = reduced[layer];
        }
        return cells;
    }

    private static void copyOverlap(Page old, Page page, int[] target) {
        CloudLodLayout.AtlasLevel level = page.level();
        long lowerX = Math.max(old.originX(), page.originX());
        long upperX = Math.min(old.originX() + level.width(), page.originX() + level.width());
        long lowerZ = Math.max(old.originZ(), page.originZ());
        long upperZ = Math.min(old.originZ() + level.depth(), page.originZ() + level.depth());
        if (lowerX >= upperX || lowerZ >= upperZ) return;
        int copiedWidth = (int) (upperX - lowerX);
        for (int layer = 0; layer < level.layers(); layer++) for (long z = lowerZ; z < upperZ; z++) {
            int source = (layer * level.depth() + (int) (z - old.originZ())) * level.width() + (int) (lowerX - old.originX());
            int destination = (layer * level.depth() + (int) (z - page.originZ())) * level.width() + (int) (lowerX - page.originX());
            System.arraycopy(old.cells, source, target, destination, copiedWidth);
        }
    }

    private static void write(ByteBuffer atlas, Page page) {
        CloudLodLayout.AtlasLevel level = page.level();
        for (int layer = 0; layer < level.layers(); layer++) for (int z = 0; z < level.depth(); z++) for (int x = 0; x < level.width(); x++) {
            int argb = page.argb(layer, page.originX() + x, page.originZ() + z);
            int offset = ((level.offsetY() + layer * level.depth() + z) * CloudLodLayout.ATLAS_WIDTH + x) * 4;
            atlas.put(offset, (byte) (argb >>> 16)); atlas.put(offset + 1, (byte) (argb >>> 8));
            atlas.put(offset + 2, (byte) argb); atlas.put(offset + 3, (byte) (((argb >>> 24) & 0xFF) >= 128 ? 128 : 0));
        }
    }

    public long generation() { return generation; }
    public CloudFieldSettings settings() { return settings; }
    public Page page(CloudLodLayout.AtlasLevel level) { return pages.get(CloudLodLayout.ATLAS_LEVELS.indexOf(level)); }
    public CloudMask fineMask() { return fineMask; }
    public ByteBuffer rgba8() { return rgba8.asReadOnlyBuffer().position(0); }
    public int byteSize() { return CloudLodLayout.ATLAS_BYTE_SIZE; }

    public static final class Page {
        private final CloudLodLayout.AtlasLevel level; private final long originX, originZ; private final int[] cells;
        private Page(CloudLodLayout.AtlasLevel level, long originX, long originZ) { this(level, originX, originZ, null); }
        private Page(CloudLodLayout.AtlasLevel level, long originX, long originZ, int[] cells) { this.level = Objects.requireNonNull(level, "level"); this.originX = originX; this.originZ = originZ; this.cells = cells; }
        public CloudLodLayout.AtlasLevel level() { return level; } public long originX() { return originX; } public long originZ() { return originZ; }
        public boolean containsCell(long x, long z) { return x >= originX && x - originX < level.width() && z >= originZ && z - originZ < level.depth(); }
        /** Immutable cached material value for one absolute cell; callers must validate page ownership explicitly. */
        public int argbAt(int layer, long x, long z) {
            if (layer < 0 || layer >= level.layers() || !containsCell(x, z)) {
                throw new IllegalArgumentException("Cloud page coordinate is outside its immutable cached extent");
            }
            return cells[(layer * level.depth() + (int) (z - originZ)) * level.width() + (int) (x - originX)];
        }
        private boolean contains(long x, long z) { return containsCell(x, z); }
        private int argb(int layer, long x, long z) { return cells[(layer * level.depth() + (int) (z - originZ)) * level.width() + (int) (x - originX)]; }
    }
}
