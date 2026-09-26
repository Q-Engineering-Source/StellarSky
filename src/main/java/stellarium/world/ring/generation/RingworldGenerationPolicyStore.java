package stellarium.world.ring.generation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;

/** Owns immutable policy files; deliberately bypasses MapStorage's swallowed IO failures. */
public final class RingworldGenerationPolicyStore {
    private static final int MAX_FILE_BYTES = 65_536;
    private static final int MAX_NBT_BYTES = 16_384;

    private RingworldGenerationPolicyStore() {}

    /** The actual ISaveHandler world directory is the root; dimensions never share a policy file. */
    public static Path pathFor(Path worldDirectory, int dimension) {
        return Objects.requireNonNull(worldDirectory, "worldDirectory").toAbsolutePath().normalize()
                .resolve("data").resolve(RingworldGenerationSavedData.DATA_NAME + "_dim_" + dimension + ".dat");
    }

    /**
     * Called synchronously by the owner before generator construction. Existing records are read-only.
     * Serialization covers same-process initializers; the caller still owns Minecraft's world session lock.
     * This is startup IO, never a per-chunk or render-frame query.
     * A failed first write may leave a partial file: keep it and fail closed on subsequent loads,
     * rather than treating corruption as permission to choose a new generation policy.
     */
    public static synchronized RingworldGenerationSettings loadOrCreate(Path worldDirectory, int dimension,
                                                          boolean alreadyInitialized, boolean requested)
            throws IOException {
        Path file = pathFor(worldDirectory, dimension);
        try {
            return read(file, dimension);
        } catch (NoSuchFileException missing) {
            // Only an absent path admits creation; corrupt, inaccessible and invalid records propagate.
            return createAndConfirm(file, dimension, alreadyInitialized, requested);
        }
    }

    private static RingworldGenerationSettings createAndConfirm(Path file, int dimension,
                                                                boolean alreadyInitialized, boolean requested)
            throws IOException {
        var settings = RingworldGenerationSettings.forUnrecordedWorld(alreadyInitialized, requested);
        byte[] encoded = encode(settings, dimension);
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        var confirmed = read(file, dimension);
        if (!settings.equals(confirmed)) {
            throw new IOException("Ringworld generation policy read-back mismatch: " + file);
        }
        return confirmed;
    }

    private static byte[] encode(RingworldGenerationSettings settings, int dimension) throws IOException {
        var envelope = new NBTTagCompound();
        envelope.setInteger("dimension", dimension);
        envelope.setTag("policy", RingworldGenerationSavedData.create(settings).writeToNBT(new NBTTagCompound()));
        var bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(envelope, bytes);
        return bytes.toByteArray();
    }

    private static RingworldGenerationSettings read(Path file, int dimension) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES) {
            throw new IOException("Invalid ringworld generation policy file: " + file);
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Ringworld generation policy exceeds size limit: " + file);
        }
        try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            var envelope = CompressedStreamTools.read(input, new NBTSizeTracker(MAX_NBT_BYTES));
            if (input.read() != -1) {
                throw new IOException("Trailing ringworld generation policy data: " + file);
            }
            if (!envelope.hasKey("dimension", 3) || envelope.getInteger("dimension") != dimension
                    || !envelope.hasKey("policy", 10)) {
                throw new IOException("Ringworld generation policy identity mismatch: " + file);
            }
            var data = new RingworldGenerationSavedData(RingworldGenerationSavedData.DATA_NAME);
            data.readFromNBT(envelope.getCompoundTag("policy"));
            return data.settings();
        } catch (RuntimeException invalidRecord) {
            throw new IOException("Cannot decode ringworld generation policy: " + file, invalidRecord);
        }
    }
}
