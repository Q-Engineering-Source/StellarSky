package stellarium.mixin.dh;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsTemporalPolicy;

/** Queued only for DH 3.3.0, whose Graphics class owns this new config entry. */
@Mixin(targets = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics", remap = false)
public abstract class MixinDistantHorizonsAntiAliasingConfig {
    @Shadow public static ConfigEntry<Boolean> enableAntiAliasing;

    @Inject(method = "<clinit>", at = @At("RETURN"), require = 1, allow = 1)
    private static void stellarium$registerTemporalEntry(CallbackInfo callback) {
        DistantHorizonsTemporalPolicy.register(enableAntiAliasing);
    }
}
