package stellarium.client.ring.dh;

import java.util.Objects;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;

/**
 * Owns StellarSky's narrow incompatibility policy for DH 3.2.0-b's two cloud entries.
 * This deliberately leaves DH's generic renderer, LOD settings, fog, and StellarSky clouds alone.
 */
public final class DistantHorizonsCloudPolicy {
    private static volatile ConfigEntry<Boolean> cloudRendering;
    private static volatile ConfigEntry<Boolean> multiLayerClouds;

    private DistantHorizonsCloudPolicy() {
    }

    public static void registerCloudEntries(ConfigEntry<Boolean> renderingEntry,
                                            ConfigEntry<Boolean> multiLayerEntry) {
        cloudRendering = Objects.requireNonNull(renderingEntry, "DH cloud-rendering config entry");
        multiLayerClouds = Objects.requireNonNull(multiLayerEntry, "DH multilayer-cloud config entry");
    }

    public static Object forceOffIfDhCloudEntry(ConfigEntry<?> entry, Object requestedValue) {
        return isDhCloudEntry(entry) ? Boolean.FALSE : requestedValue;
    }

    public static boolean isDhCloudEntry(ConfigEntry<?> entry) {
        return entry == cloudRendering || entry == multiLayerClouds;
    }
}
