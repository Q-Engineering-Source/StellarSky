package stellarium.world.ring.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Gameplay-owner publication fence. Data authority does not prove drawable replacement readiness. */
public final class SeedPreviewAdmission implements AutoCloseable {
    public enum Coverage { UNKNOWN, NO_REAL_DATA, REAL_DATA }
    private final Thread owner = Thread.currentThread();
    private final int capacity;
    private final Map<TerrainTileKey, Entry> entries = new HashMap<>();
    private long epoch;
    private boolean closed;

    public SeedPreviewAdmission(long epoch, int capacity) {
        if (epoch <= 0 || capacity < 1 || capacity > 4096) throw new IllegalArgumentException("Invalid admission bounds");
        this.epoch = epoch; this.capacity = capacity;
    }

    /** NO_REAL_DATA must come from a completed authoritative query over the whole tile, never a cache miss. */
    public Optional<Query> beginCoverage(TerrainTileKey key) {
        check(key);
        var entry = entries.get(key);
        TerrainPreviewTrace.serverAdmissionEntries(entries.size(),capacity);
        if (entry == null) {
            if (entries.size() == capacity) {
                TerrainPreviewTrace.serverSessionRequestEmpty(TerrainPreviewTrace.SessionRefusal.ENTRY_CAPACITY);
                return Optional.empty();
            }
            entry = new Entry(); entries.put(key, entry);
        }
        entry.preview = null; entry.publication = null;
        if (entry.coverage != Coverage.REAL_DATA) entry.coverage = Coverage.UNKNOWN;
        var query = new Query(this, key); entry.query = query;
        return Optional.of(query);
    }
    public boolean coverage(Query query, Coverage coverage) {
        checkOwner(query.owner); Objects.requireNonNull(coverage);
        var entry = entries.get(query.key);
        if (entry == null || entry.query != query) return false;
        entry.query = null;
        if (entry.coverage != Coverage.REAL_DATA) entry.coverage = coverage;
        return true;
    }
    public Optional<Preview> beginPreview(TerrainTileKey key) {
        check(key);
        var entry = entries.get(key);
        if (entry == null || entry.coverage != Coverage.NO_REAL_DATA || entry.query != null) return Optional.empty();
        var preview = new Preview(this, key); entry.preview = preview; entry.publication = null;
        return Optional.of(preview);
    }
    /** Call on gameplay owner after async disk/sampling completion has been marshalled back. */
    public Optional<Publication> publish(Preview preview, SeedTerrainTile tile) {
        checkOwner(preview.owner); Objects.requireNonNull(tile);
        if (!preview.key.equals(tile.key())) throw new IllegalArgumentException("Foreign preview result");
        var entry = entries.get(preview.key);
        if (entry == null || entry.preview != preview || entry.coverage != Coverage.NO_REAL_DATA) return Optional.empty();
        entry.preview = null;
        var publication = new Publication(this, tile); entry.publication = publication;
        return Optional.of(publication);
    }
    public boolean current(Publication publication) {
        checkOwner(publication.owner);
        var entry = entries.get(publication.tile.key());
        return entry != null && entry.publication == publication && entry.coverage == Coverage.NO_REAL_DATA;
    }

    /** Call before enqueueing disk invalidation. Retires intersecting child/parent eligibility immediately. */
    public int realRegion(long minX, long minZ, long maxX, long maxZ) {
        checkOpen();
        if (minX >= maxX || minZ >= maxZ) throw new IllegalArgumentException("Invalid half-open region");
        int affected = 0;
        for (var pair : entries.entrySet()) {
            var key = pair.getKey(); long width = 64L << key.level();
            if (key.minBlockX() < maxX && key.minBlockZ() < maxZ
                    && key.minBlockX() + width > minX && key.minBlockZ() + width > minZ) {
                var entry = pair.getValue(); entry.coverage = Coverage.REAL_DATA;
                entry.query = null; entry.preview = null; entry.publication = null; affected++;
            }
        }
        TerrainPreviewTrace.serverRealRegionPins(affected);
        return affected;
    }
    /** Only after dependent geometry retires. Readmission requires a fresh full coverage query. */
    public void release(TerrainTileKey key) { check(key); entries.remove(key); }
    public void switchWorld(long newEpoch) {
        checkOpen();
        if (newEpoch <= epoch) throw new IllegalArgumentException("Epoch must increase");
        entries.clear(); epoch = newEpoch;
    }
    @Override public void close() { checkThread(); closed = true; entries.clear(); }
    private void check(TerrainTileKey key) {
        checkOpen(); if (key.worldEpoch() != epoch) throw new IllegalArgumentException("Foreign epoch");
    }
    private void checkOwner(SeedPreviewAdmission other) {
        checkOpen(); if (other != this) throw new IllegalArgumentException("Foreign admission token");
    }
    private void checkOpen() { checkThread(); if (closed) throw new IllegalStateException("Admission closed"); }
    private void checkThread() { if (Thread.currentThread() != owner) throw new IllegalStateException("Off gameplay owner"); }
    private static final class Entry {
        Coverage coverage = Coverage.UNKNOWN;
        Query query; Preview preview; Publication publication;
    }
    public static final class Query {
        private final SeedPreviewAdmission owner; private final TerrainTileKey key;
        private Query(SeedPreviewAdmission owner, TerrainTileKey key) { this.owner = owner; this.key = key; }
    }
    public static final class Preview {
        private final SeedPreviewAdmission owner; private final TerrainTileKey key;
        private Preview(SeedPreviewAdmission owner, TerrainTileKey key) { this.owner = owner; this.key = key; }
    }
    public static final class Publication {
        private final SeedPreviewAdmission owner; private final SeedTerrainTile tile;
        private Publication(SeedPreviewAdmission owner, SeedTerrainTile tile) { this.owner = owner; this.tile = tile; }
        public SeedTerrainTile tile() { return tile; }
    }
}
