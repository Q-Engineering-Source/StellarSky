package stellarium.mixin.dh;

import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.client.ring.dh.DistantHorizonsCloudPolicy;
import stellarium.client.ring.dh.DistantHorizonsTemporalPolicy;
import stellarium.client.ring.dh.DistantHorizonsCurvatureState;

/** Forces the two registered DH cloud settings off without changing any other DH config entry. */
@Mixin(value = ConfigEntry.class, remap = false)
public abstract class MixinDistantHorizonsCloudConfigEntry {
    @ModifyVariable(method = {"setWithoutFiringEvents", "setWithoutSaving", "set", "uiSetWithoutSaving", "uiSet",
            "setApiValue", "setMcVersionOverrideValue"}, at = @At("HEAD"), argsOnly = true, require = 7, allow = 7)
    private Object stellarium$forceDhCloudsOff(Object requestedValue) {
        return DistantHorizonsCloudPolicy.forceOffIfDhCloudEntry((ConfigEntry<?>) (Object) this,
                requestedValue);
    }

    @Inject(method = {"get", "getTrueValue", "getDefaultValue", "getApiValue"}, at = @At("HEAD"),
            cancellable = true, require = 4, allow = 4)
    private void stellarium$reportDhCloudsOff(CallbackInfoReturnable<Object> callback) {
        if (DistantHorizonsCloudPolicy.isDhCloudEntry((ConfigEntry<?>) (Object) this)) {
            callback.setReturnValue(Boolean.FALSE);
        }
    }

    // Both DH jitter and TAA resolve consume get(). Leave getTrueValue/default/API
    // untouched so config serialization and UI retain the user's saved preference.
    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void stellarium$readTemporalSetting(CallbackInfoReturnable<Object> callback) {
        ConfigEntry<?> entry = (ConfigEntry<?>) (Object) this;
        if (DistantHorizonsTemporalPolicy.isEntry(entry)
                && DistantHorizonsTemporalPolicy.suppress(entry, DistantHorizonsCurvatureState.currentFrame() != null)) {
            callback.setReturnValue(Boolean.FALSE);
        }
    }
}
