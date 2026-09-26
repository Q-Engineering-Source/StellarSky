package stellarium.world.ring.terrain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import net.minecraft.world.gen.ChunkGeneratorSettings;

/** Server-private digest of admitted density inputs, not a client protocol payload. */
public final class OverworldPreviewFingerprint {
    private OverworldPreviewFingerprint() {}

    /**
     * biomePolicyRevision must identify the actual biome pipeline, scalar properties and strip policy.
     * A class name or raw generator-options string alone is not sufficient admission evidence.
     */
    public static byte[] capture(long seed, int seaLevel, boolean amplified,
                                 ChunkGeneratorSettings settings, byte[] biomePolicyRevision) {
        Objects.requireNonNull(settings);
        if (seaLevel < 0 || seaLevel > 256 || biomePolicyRevision.length != 32) {
            throw new IllegalArgumentException("Invalid preview fingerprint input");
        }
        var values = new float[]{settings.depthNoiseScaleX, settings.depthNoiseScaleZ,
                settings.depthNoiseScaleExponent, settings.coordinateScale, settings.heightScale,
                settings.mainNoiseScaleX, settings.mainNoiseScaleY, settings.mainNoiseScaleZ,
                settings.biomeDepthOffSet, settings.biomeDepthWeight, settings.biomeScaleOffset,
                settings.biomeScaleWeight, settings.baseSize, settings.stretchY,
                settings.lowerLimitScale, settings.upperLimitScale};
        var bytes = ByteBuffer.allocate(8 + 4 + 1 + values.length * 4 + 32);
        bytes.putLong(seed).putInt(seaLevel).put((byte)(amplified ? 1 : 0));
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite density setting");
            bytes.putInt(Float.floatToIntBits(value));
        }
        bytes.put(biomePolicyRevision);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("stellarsky-overworld-density-inputs-v1".getBytes(StandardCharsets.UTF_8));
            return digest.digest(bytes.array());
        } catch (NoSuchAlgorithmException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
}
