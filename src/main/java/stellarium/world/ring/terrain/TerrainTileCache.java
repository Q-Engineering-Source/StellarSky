package stellarium.world.ring.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded residency and asynchronous evidence publication; performs no IO or rendering. */
public final class TerrainTileCache {
    private final int capacity;
    private final Map<TerrainTileKey, Entry> entries = new HashMap<>();
    private long epoch;
    private long sequence;

    public TerrainTileCache(long worldEpoch, int capacity) {
        if (worldEpoch <= 0 || capacity <= 0) throw new IllegalArgumentException("Positive epoch/capacity required");
        epoch = worldEpoch;
        this.capacity = capacity;
    }

    /** Backpressure rather than implicit eviction: losing real evidence must not revive a placeholder. */
    public synchronized Optional<QueryTicket> begin(TerrainTileKey key) {
        Objects.requireNonNull(key, "key");
        if (key.worldEpoch() != epoch) throw new IllegalArgumentException("Tile belongs to a different world epoch");
        Entry entry = entries.get(key);
        if (entry == null) {
            if (entries.size() == capacity) return Optional.empty();
            entry = new Entry(new TerrainTileSnapshot(key));
            entries.put(key, entry);
        }
        sequence = Math.incrementExact(sequence);
        entry.pending = new QueryTicket(key, sequence);
        return Optional.of(entry.pending);
    }

    public synchronized boolean publish(QueryTicket ticket, TerrainColumnState[] incoming) {
        Entry entry = current(ticket);
        if (entry == null) return false;
        // Validate/copy before consuming the ticket. A failed read never masquerades as empty terrain.
        var replacement = new TerrainTileSnapshot(entry.snapshot, ticket.revision, incoming);
        entry.snapshot = replacement;
        entry.pending = null;
        return true;
    }

    /** Caller retains/logs the original IO error; this merely ends its job without erasing valid evidence. */
    public synchronized boolean fail(QueryTicket ticket) {
        Entry entry = current(ticket);
        if (entry == null) return false;
        entry.pending = null;
        return true;
    }

    public synchronized Optional<TerrainTileSnapshot> get(TerrainTileKey key) {
        Entry entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot);
    }

    /** Only after this tile leaves the consumer's residency window and its dependent geometry is retired.
     * A later admission starts at revision zero: it must be re-queried before permitting any preview.
     * This does not delete persisted placeholder data or constitute a real-data invalidation event.
     */
    public synchronized void release(TerrainTileKey key) { entries.remove(key); }

    public synchronized void switchWorld(long newEpoch) {
        if (newEpoch <= epoch) throw new IllegalArgumentException("World epochs must increase and never be reused");
        entries.clear();
        epoch = newEpoch;
    }

    public synchronized int size() { return entries.size(); }

    private Entry current(QueryTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        Entry entry = entries.get(ticket.key);
        return entry != null && entry.pending == ticket ? entry : null;
    }

    public static final class QueryTicket {
        private final TerrainTileKey key;
        private final long revision;
        private QueryTicket(TerrainTileKey key, long revision) { this.key = key; this.revision = revision; }
        public TerrainTileKey key() { return key; }
        public long revision() { return revision; }
    }

    private static final class Entry {
        private TerrainTileSnapshot snapshot;
        private QueryTicket pending;
        private Entry(TerrainTileSnapshot snapshot) { this.snapshot = snapshot; }
    }
}
