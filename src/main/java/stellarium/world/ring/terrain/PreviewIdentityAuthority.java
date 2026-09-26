package stellarium.world.ring.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Server-private identity records. Callers supply dedicated metadata directories, never client paths. */
public final class PreviewIdentityAuthority {
    private static final int MAGIC = 0x53534941, FORMAT = 1;
    private PreviewIdentityAuthority() {}

    /** Persist one installation/world UUID. Corruption is never interpreted as a missing identity. */
    public static UUID persistentId(Path directory) throws IOException {
        return locked(directory, root -> {
            var path = root.resolve("identity.bin");
            var bytes = read(path, 56);
            if (bytes != null) {
                try (var in = input(bytes)) { return SeedPreviewCodec.readUuid(in); }
            }
            // A lost record or interrupted first write must not silently mint a replacement world ID.
            try (var entries = Files.list(root)) {
                if (entries.anyMatch(entry -> !entry.getFileName().toString().equals("authority.lock"))) {
                    throw new IOException("Missing identity in nonempty authority directory");
                }
            }
            UUID id = UUID.randomUUID();
            var bytesOut = new ByteArrayOutputStream();
            try (var out = output(bytesOut)) { SeedPreviewCodec.writeUuid(out, id); }
            replace(path, SeedPreviewCodec.checked(bytesOut.toByteArray()));
            return id;
        });
    }

    /** Fingerprint is private SHA-256 of ALL admitted effective sampling inputs, supplied by the caller. */
    public static PreviewCacheIdentity issue(Path worldMetadata, UUID serverId, int dimension,
                                             int samplerVersion, byte[] fingerprint) throws IOException {
        Objects.requireNonNull(serverId);
        if (samplerVersion <= 0 || fingerprint.length != 32) throw new IllegalArgumentException("Invalid sampling identity");
        byte[] privateFingerprint = fingerprint.clone();
        UUID worldId = persistentId(worldMetadata);
        return locked(worldMetadata, root -> {
            var path = root.resolve("generation-" + dimension + ".bin");
            var bytes = read(path, 112);
            if (bytes != null) {
                try (var in = input(bytes)) {
                    UUID storedWorld = SeedPreviewCodec.readUuid(in);
                    int storedDimension = in.readInt(), version = in.readInt();
                    UUID generation = SeedPreviewCodec.readUuid(in);
                    byte[] previous = in.readNBytes(32);
                    if (!worldId.equals(storedWorld) || storedDimension != dimension || version <= 0) {
                        throw new IOException("Foreign or invalid preview authority record");
                    }
                    if (version == samplerVersion && Arrays.equals(previous, privateFingerprint)) {
                        return new PreviewCacheIdentity(serverId, worldId, dimension, generation, samplerVersion);
                    }
                }
            }
            UUID generation = UUID.randomUUID();
            var bytesOut = new ByteArrayOutputStream();
            try (var out = output(bytesOut)) {
                SeedPreviewCodec.writeUuid(out, worldId); out.writeInt(dimension); out.writeInt(samplerVersion);
                SeedPreviewCodec.writeUuid(out, generation); out.write(privateFingerprint);
            }
            replace(path, SeedPreviewCodec.checked(bytesOut.toByteArray()));
            return new PreviewCacheIdentity(serverId, worldId, dimension, generation, samplerVersion);
        });
    }

    private static byte[] read(Path path, int length) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != length) throw new IOException("Invalid authority record length: " + path);
            var buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("Truncated authority record");
            return buffer.array();
        } catch (NoSuchFileException missing) { return null; }
    }
    private static DataInputStream input(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(SeedPreviewCodec.verified(bytes)));
        if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Unsupported authority record");
        return in;
    }
    private static DataOutputStream output(ByteArrayOutputStream bytes) throws IOException {
        var out = new DataOutputStream(bytes); out.writeInt(MAGIC); out.writeInt(FORMAT); return out;
    }
    private static void replace(Path target, byte[] bytes) throws IOException {
        Path stage = target.resolveSibling("authority-stage-" + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            try { Files.deleteIfExists(stage); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static <T> T locked(Path directory, Operation<T> operation) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Authority directory must not be a symlink");
        Path root = directory.toRealPath();
        try (var channel = FileChannel.open(root.resolve("authority.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Preview authority is already in use");
            return operation.run(root);
        }
    }
    @FunctionalInterface private interface Operation<T> { T run(Path root) throws IOException; }
}
