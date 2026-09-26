package stellarium.mixin.dh;

import com.seibel.distanthorizons.core.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsCloudPolicy;

/** Registers only DH 3.2.0-b's two cloud toggles for the forced-off policy. */
@Mixin(targets = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$GenericRendering", remap = false)
public abstract class MixinDistantHorizonsCloudConfig {
    @Inject(method = "<clinit>", at = @At("RETURN"), require = 1, allow = 1)
    private static void stellarium$registerCloudEntries(CallbackInfo callback) {
        DistantHorizonsCloudPolicy.registerCloudEntries(
                Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering,
                Config.Client.Advanced.Graphics.GenericRendering.enableMultiLayerClouds);
    }
}
