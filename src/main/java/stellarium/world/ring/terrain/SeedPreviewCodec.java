package stellarium.world.ring.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/** Fixed-size, checksummed approximate tile payload. No seed, session epoch or REAL state. */
public final class SeedPreviewCodec {
    public static final int ENCODED_BYTES = 20596;
    private static final int MAGIC = 0x53535056, FORMAT = 1;
    private SeedPreviewCodec() {}

    public static byte[] encode(PreviewCacheIdentity identity, SeedTerrainTile tile) throws IOException {
        var bytes = new ByteArrayOutputStream(ENCODED_BYTES);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(FORMAT); writeIdentity(out, identity);
            out.writeInt(tile.key().level()); out.writeLong(tile.key().x()); out.writeLong(tile.key().z());
            for (var column : tile.columns()) {
                out.writeByte(switch (column.kind()) { case EMPTY -> 0; case LAND -> 1; case OCEAN -> 2; });
                out.writeShort(column.groundTop()); out.writeShort(column.visibleTop());
            }
        }
        return checked(bytes.toByteArray());
    }
    public static SeedTerrainTile decode(PreviewCacheIdentity identity, long epoch, byte[] bytes) throws IOException {
        if (bytes.length != ENCODED_BYTES) throw new IOException("Wrong preview payload length");
        try (var in = new DataInputStream(new ByteArrayInputStream(verified(bytes)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Unsupported preview payload");
            if (!identity.equals(readIdentity(in))) throw new IOException("Foreign preview namespace");
            var key = new TerrainTileKey(epoch, in.readInt(), in.readLong(), in.readLong());
            var columns = new ArrayList<SeedTerrainTile.Column>(4096);
            for (int i = 0; i < 4096; i++) {
                var kind = switch (in.readUnsignedByte()) {
                    case 0 -> SeedTerrainTile.Kind.EMPTY; case 1 -> SeedTerrainTile.Kind.LAND;
                    case 2 -> SeedTerrainTile.Kind.OCEAN; default -> throw new IOException("Unknown preview column kind");
                };
                columns.add(new SeedTerrainTile.Column(kind, in.readUnsignedShort(), in.readUnsignedShort()));
            }
            if (in.available() != 0) throw new IOException("Trailing preview payload");
            return new SeedTerrainTile(key, columns);
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException("Invalid preview payload", e); }
    }
    static void writeIdentity(DataOutputStream out, PreviewCacheIdentity identity) throws IOException {
        writeUuid(out, identity.serverId()); writeUuid(out, identity.worldId()); out.writeInt(identity.dimension());
        writeUuid(out, identity.generationId()); out.writeInt(identity.samplerVersion());
    }
    static PreviewCacheIdentity readIdentity(DataInputStream in) throws IOException {
        return new PreviewCacheIdentity(readUuid(in), readUuid(in), in.readInt(), readUuid(in), in.readInt());
    }
    static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    static byte[] checked(byte[] payload) {
        var result = Arrays.copyOf(payload, payload.length + 32);
        System.arraycopy(digest(payload), 0, result, payload.length, 32);
        return result;
    }
    static byte[] verified(byte[] bytes) throws IOException {
        if (bytes.length < 32) throw new IOException("Truncated preview checksum");
        var payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, payload.length, bytes.length))) {
            throw new IOException("Preview checksum mismatch");
        }
        return payload;
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new ExceptionInInitializerError(e); }
    }
}
