package stellarium.client.ring.dh;

import java.util.Objects;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;

/** DH 3.3's jitter and resolve must agree with the unjittered own-media depth frame. */
public final class DistantHorizonsTemporalPolicy {
    private static volatile ConfigEntry<Boolean> antiAliasing;
    private DistantHorizonsTemporalPolicy() {}

    public static void register(ConfigEntry<Boolean> entry) {
        antiAliasing = Objects.requireNonNull(entry, "DH anti-aliasing entry");
    }

    public static boolean isEntry(ConfigEntry<?> entry) {
        return entry != null && entry == antiAliasing;
    }

    /** A read override only. The user's stored preference is never changed. */
    public static boolean suppress(ConfigEntry<?> entry, boolean curvedFrame) {
        return curvedFrame && isEntry(entry);
    }
}
