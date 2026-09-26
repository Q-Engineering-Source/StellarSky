package stellarium.world.ring.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exclusive, owner-thread local preview store. Atomic index is the sole authority; orphan payloads
 * never count as cache hits. No live world/DH database access or automatic eviction of real evidence.
 * Caller must establish this server-issued namespace and query real coverage before using a preview.
 */
public final class SeedPreviewDiskCache implements AutoCloseable {
    private static final int MAGIC = 0x53534958, FORMAT = 1, MAX_INDEX_BYTES = 200_000;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern OWNED = Pattern.compile("(?:tile-" + UUID_PATTERN + "\\.bin|stage-" + UUID_PATTERN + "\\.tmp)");
    private final Thread owner = Thread.currentThread();
    private final Path root;
    private final PreviewCacheIdentity identity;
    private final long epoch;
    private final int capacity;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<Address, Ticket> pending = new HashMap<>();
    private Map<Address, UUID> entries;
    private IOException failed;
    private boolean closed;

    public SeedPreviewDiskCache(Path directory, PreviewCacheIdentity identity, long epoch, int capacity) throws IOException {
        if (epoch <= 0 || capacity <= 0 || capacity > 4096) throw new IllegalArgumentException("Invalid preview cache bounds");
        this.identity = Objects.requireNonNull(identity); this.epoch = epoch; this.capacity = capacity;
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Preview directory must not be a symbolic link");
        root = directory.toRealPath();
        if (!Files.exists(root.resolve("index.bin"), LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(root)) {
                if (files.anyMatch(p -> !p.getFileName().toString().equals("owner.lock"))) {
                    throw new IOException("Refusing to adopt an unowned preview directory");
                }
            }
        }
        lockChannel = FileChannel.open(root.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Preview cache already owned");
        } catch (IOException | OverlappingFileLockException e) {
            try { lockChannel.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw new IOException("Cannot own preview cache", e);
        }
        lock = acquired;
        try {
            Path index = root.resolve("index.bin");
            if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)) entries = readIndex(index);
            else {
                try (var files = Files.list(root)) {
                    if (files.anyMatch(p -> !p.getFileName().toString().equals("owner.lock"))) {
                        throw new IOException("Refusing to initialize a nonempty unowned preview directory");
                    }
                }
                entries = copyIndex(Map.of()); writeIndex(entries);
            }
            cleanOrphans();
        } catch (IOException | RuntimeException | Error e) {
            try { lock.release(); } catch (IOException close) { e.addSuppressed(close); }
            try { lockChannel.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    public Optional<SeedTerrainTile> read(TerrainTileKey key) throws IOException {
        check(key);
        var file = entries.get(Address.of(key));
        if (file == null) return Optional.empty();
        try {
            var tile = SeedPreviewCodec.decode(identity, epoch, readBounded(payload(file), SeedPreviewCodec.ENCODED_BYTES));
            if (!tile.key().equals(key)) throw new IOException("Indexed preview tile coordinate mismatch");
            return Optional.of(tile);
        } catch (IOException e) { failed = e; throw e; }
    }

    public Optional<Ticket> beginWrite(TerrainTileKey key) throws IOException {
        check(key);
        var address = Address.of(key);
        long reserved = pending.keySet().stream().filter(k -> !entries.containsKey(k)).count();
        if (!entries.containsKey(address) && !pending.containsKey(address) && entries.size() + reserved >= capacity) {
            // Completed disk entries may rotate, but an in-flight writer owns its reservation.
            var victim=entries.keySet().stream().filter(k->!pending.containsKey(k)).findFirst();
            if(victim.isEmpty())return Optional.empty();
            var updated=copyIndex(entries);var retired=updated.remove(victim.get());
            try {
                writeIndex(updated);entries=updated;
                Files.delete(payload(retired));
            } catch(IOException failure) {failed=failure;throw failure;}
        }
        var ticket = new Ticket(this, key); pending.put(address, ticket);
        return Optional.of(ticket);
    }
    public boolean cancelWrite(Ticket ticket) {
        checkTicket(ticket);
        return pending.remove(Address.of(ticket.key), ticket);
    }
    /** Ordered cancellation also covers a reservation whose async reply has not reached its caller. */
    public boolean cancelPending(TerrainTileKey key) {
        check(key); return pending.remove(Address.of(key)) != null;
    }
    public boolean write(Ticket ticket, SeedTerrainTile tile) throws IOException {
        checkTicket(ticket);
        if (!ticket.key.equals(tile.key())) throw new IllegalArgumentException("Preview result does not match write ticket");
        var address = Address.of(ticket.key);
        if (pending.get(address) != ticket) return false;
        UUID file = UUID.randomUUID();
        try {
            writeAtomic(payload(file), SeedPreviewCodec.encode(identity, tile));
            var updated = copyIndex(entries); UUID old = updated.put(address, file);
            writeIndex(updated);
            entries = updated; pending.remove(address, ticket);
            if (old != null) Files.delete(payload(old));
            return true;
        } catch (IOException e) { failed = e; throw e; }
    }

    /** Invalidates stored preview tiles and intersecting parent summaries, not current visible geometry. */
    public int invalidateRegion(long minX, long minZ, long maxX, long maxZ) throws IOException {
        checkOpen();
        if (minX >= maxX || minZ >= maxZ) throw new IllegalArgumentException("Invalid half-open invalidation region");
        pending.keySet().removeIf(k -> k.intersects(minX, minZ, maxX, maxZ));
        var updated = copyIndex(entries); var retired = new ArrayList<UUID>();
        updated.entrySet().removeIf(e -> {
            if (!e.getKey().intersects(minX, minZ, maxX, maxZ)) return false;
            retired.add(e.getValue()); return true;
        });
        if (retired.isEmpty()) return 0;
        try {
            // Commit loss of authority before deleting payloads. A crash cannot revive deleted entries.
            writeIndex(updated); entries = updated;
            for (var file : retired) Files.delete(payload(file));
            return retired.size();
        } catch (IOException e) { failed = e; throw e; }
    }

    private Map<Address, UUID> readIndex(Path file) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(SeedPreviewCodec.verified(readBounded(file, MAX_INDEX_BYTES))))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Unsupported preview index");
            if (!identity.equals(SeedPreviewCodec.readIdentity(in))) throw new IOException("Foreign preview index identity");
            int size = in.readInt(); if (size < 0 || size > capacity) throw new IOException("Preview index exceeds capacity");
            var result = copyIndex(Map.of()); var files = new HashSet<UUID>();
            for (int i = 0; i < size; i++) {
                var address = new Address(in.readInt(), in.readLong(), in.readLong());
                var id = SeedPreviewCodec.readUuid(in);
                if (result.put(address, id) != null || !files.add(id)) throw new IOException("Duplicate preview index entry");
            }
            if (in.available() != 0) throw new IOException("Trailing preview index bytes");
            return result;
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException("Invalid preview index", e); }
    }
    /** Read-only ownership header; full index/payload validation still occurs when the cache opens. */
    static PreviewCacheIdentity inspectIdentity(Path directory) throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(SeedPreviewCodec.verified(
                readBounded(directory.resolve("index.bin"),MAX_INDEX_BYTES))))) {
            if(in.readInt()!=MAGIC||in.readInt()!=FORMAT)throw new IOException("Unsupported preview index");
            return SeedPreviewCodec.readIdentity(in);
        } catch(IllegalArgumentException failure) {throw new IOException("Invalid preview identity",failure);}
    }
    private void writeIndex(Map<Address, UUID> index) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(FORMAT); SeedPreviewCodec.writeIdentity(out, identity); out.writeInt(index.size());
            for (var entry : index.entrySet()) {
                out.writeInt(entry.getKey().level); out.writeLong(entry.getKey().x); out.writeLong(entry.getKey().z);
                SeedPreviewCodec.writeUuid(out, entry.getValue());
            }
        }
        writeAtomic(root.resolve("index.bin"), SeedPreviewCodec.checked(bytes.toByteArray()));
    }
    private static Map<Address,UUID> copyIndex(Map<Address,UUID> source) {
        // Reads update recency in memory; the next index mutation persists that ordering.
        var result=new LinkedHashMap<Address,UUID>(Math.max(16,source.size()),0.75f,true);
        result.putAll(source);return result;
    }
    private void cleanOrphans() throws IOException {
        var retained = new HashSet<Path>(); for (var id : entries.values()) retained.add(payload(id));
        for (var file : retained) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != SeedPreviewCodec.ENCODED_BYTES) {
                throw new IOException("Missing or incorrectly sized indexed preview payload: " + file);
            }
        }
        try (var files = Files.list(root)) {
            var paths = files.limit(2L*capacity+5).toList();
            if(paths.size()>2L*capacity+4)throw new IOException("Preview directory exceeds file budget");
            // Validate the entire directory before deleting any derived orphan.
            for (var file : paths) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !(name.equals("index.bin") || name.equals("owner.lock") || OWNED.matcher(name).matches())) {
                    throw new IOException("Unexpected file in preview-owned directory: " + file);
                }
            }
            for (var file : paths) if (OWNED.matcher(file.getFileName().toString()).matches() && !retained.contains(file)) Files.delete(file);
        }
    }
    private Path payload(UUID id) { return root.resolve("tile-" + id + ".bin"); }
    private static byte[] readBounded(Path file, int max) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size(); if (size < 0 || size > max) throw new IOException("Preview file exceeds bound");
            var buffer = ByteBuffer.allocate((int)size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Truncated preview file");
            if (channel.size() != size) throw new IOException("Preview file changed while reading");
            return buffer.array();
        }
    }
    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temporary = root.resolve("stage-" + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    private void check(TerrainTileKey key) { checkOpen(); if (key.worldEpoch() != epoch) throw new IllegalArgumentException("Foreign session epoch"); }
    private void checkTicket(Ticket ticket) { checkOpen(); if (ticket.owner != this) throw new IllegalArgumentException("Foreign write ticket"); }
    private void checkOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Preview cache used off IO owner thread");
        if (closed) throw new IllegalStateException("Preview cache closed");
        if (failed != null) throw new IllegalStateException("Preview cache requires reopen after IO failure", failed);
    }
    @Override public void close() throws IOException {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Preview cache closed off owner thread");
        if (closed) return;
        closed = true; pending.clear();
        try (lockChannel) { lock.release(); }
    }
    private record Address(int level, long x, long z) {
        private Address { new TerrainTileKey(1, level, x, z); }
        static Address of(TerrainTileKey key) { return new Address(key.level(), key.x(), key.z()); }
        boolean intersects(long minX, long minZ, long maxX, long maxZ) {
            long width = 64L << level, startX = x * width, startZ = z * width;
            return startX < maxX && startZ < maxZ && startX + width > minX && startZ + width > minZ;
        }
    }
    public static final class Ticket {
        private final SeedPreviewDiskCache owner;
        private final TerrainTileKey key;
        private Ticket(SeedPreviewDiskCache owner, TerrainTileKey key) { this.owner = owner; this.key = key; }
    }
}
