package stellarium.world.ring.generation;

import java.util.Objects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.WorldSavedData;
import stellarium.world.ring.RingworldStripBounds;

/**
 * Strict policy payload. Production IO is owned exclusively by RingworldGenerationPolicyStore;
 * do not register this carrier with MapStorage and introduce a second, unchecked writer.
 */
public final class RingworldGenerationSavedData extends WorldSavedData {
    public static final String DATA_NAME = "stellarsky_ring_generation";
    private static final int SCHEMA = 1;
    private RingworldGenerationSettings settings;

    /** Compatible with the WorldSavedData codec contract; unusable until successfully decoded. */
    public RingworldGenerationSavedData(String name) {
        super(name);
    }

    public static RingworldGenerationSavedData create(RingworldGenerationSettings settings) {
        var data = new RingworldGenerationSavedData(DATA_NAME);
        data.settings = Objects.requireNonNull(settings, "settings");
        data.markDirty();
        return data;
    }

    public RingworldGenerationSettings settings() {
        if (settings == null) {
            throw new IllegalStateException("Ringworld generation policy has not been loaded");
        }
        return settings;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        requireInt(tag, "schema", SCHEMA);
        requireInt(tag, "minZ", RingworldStripBounds.BOARD_MIN_Z);
        requireInt(tag, "maxZExclusive", RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        requireInt(tag, "structureMarginChunks", RingworldGenerationPolicy.STRUCTURE_MARGIN_CHUNKS);
        if (!tag.hasKey("enabled", 1) || (tag.getByte("enabled") != 0 && tag.getByte("enabled") != 1)) {
            throw new IllegalArgumentException("Invalid ringworld generation enabled flag");
        }
        // Commit only after all fields validate; malformed data must not partially replace policy.
        settings = new RingworldGenerationSettings(tag.getBoolean("enabled"));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        boolean enabled = settings().enabled();
        tag.setInteger("schema", SCHEMA);
        tag.setInteger("minZ", RingworldStripBounds.BOARD_MIN_Z);
        tag.setInteger("maxZExclusive", RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        tag.setInteger("structureMarginChunks", RingworldGenerationPolicy.STRUCTURE_MARGIN_CHUNKS);
        tag.setBoolean("enabled", enabled);
        return tag;
    }

    private static void requireInt(NBTTagCompound tag, String key, int expected) {
        if (!tag.hasKey(key, 3) || tag.getInteger(key) != expected) {
            throw new IllegalArgumentException("Unsupported ringworld generation field: " + key);
        }
    }
}
