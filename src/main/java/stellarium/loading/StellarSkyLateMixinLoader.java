package stellarium.loading;

import java.util.List;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.FMLCommonHandler;
import zone.rong.mixinbooter.ILateMixinLoader;

/**
 * Queues renderer Mixins after mod discovery. This ordinary entrypoint must stay
 * outside the reserved Mixin packages so CleanMix can load it at that stage.
 */
public final class StellarSkyLateMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        var dh = Loader.instance().getIndexedModList().get("distanthorizons");
        return configsFor(dh == null ? null : dh.getVersion(), FMLCommonHandler.instance().getSide().isClient());
    }

    static List<String> configsFor(String dhVersion) {
        return configsFor(dhVersion, true);
    }

    static List<String> configsFor(String dhVersion, boolean clientSide) {
        if (!clientSide) return List.of("mixins.stellarsky.actinium.json");
        if (dhVersion == null) return List.of("mixins.stellarsky.actinium.json");
        if (dhVersion.equals("3.3.0")) {
            return List.of("mixins.stellarsky.actinium.json", "mixins.stellarsky.dh.json", "mixins.stellarsky.dh330.json");
        }
        if (!dhVersion.equals("3.2.0-b")) {
            throw new IllegalStateException("StellarSky's DH adapter requires verified Distant Horizons 3.2.0-b or 3.3.0; found " + dhVersion);
        }
        return List.of("mixins.stellarsky.actinium.json", "mixins.stellarsky.dh.json");
    }
}
