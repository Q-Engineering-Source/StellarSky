package stellarium.world.ring.generation;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/** Initial opt-in only; the persisted per-world policy remains authoritative afterwards. */
public record RingworldGenerationConfig(boolean enableNewOverworld) {
    public static RingworldGenerationConfig load(File file) {
        var config = new Configuration(file);
        config.load();
        var result = read(config);
        if (config.hasChanged()) config.save();
        return result;
    }

    static RingworldGenerationConfig read(Configuration config) {
        // Configuration.get may repair a malformed boolean to its default: validate the stored value first.
        if (config.hasKey("generation", "EnableNewOverworld")
                && !config.getCategory("generation").get("EnableNewOverworld").isBooleanValue()) {
            throw new IllegalArgumentException("EnableNewOverworld must be true or false");
        }
        var property = config.get("generation", "EnableNewOverworld", false,
                "Enable the finite ring strip in a NEW Overworld. Existing worlds are not migrated. "
                + "Space has no natural terrain or structures; player building remains allowed.");
        property.setRequiresWorldRestart(true);
        if (!property.isBooleanValue()) throw new IllegalArgumentException("EnableNewOverworld must be true or false");
        return new RingworldGenerationConfig(property.getBoolean());
    }

    public boolean requests(int dimension) { return enableNewOverworld && dimension == 0; }
}
