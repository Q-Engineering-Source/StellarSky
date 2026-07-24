package stellarium.mixin;

import net.minecraft.world.World;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.time.StellarSkyTime;

@Mixin(WorldClient.class)
public abstract class MixinWorldClient {
	/**
	 * WorldClient treats a negative setWorldTime argument as the vanilla
	 * "disable daylight cycle" sentinel. Reverse time needs the actual negative
	 * value, so bypass that client-only sentinel while reverse mode is active.
	 */
	@Inject(method = "setWorldTime", at = @At("HEAD"), cancellable = true)
	private void stellarium$allowReverseTime(long time, CallbackInfo callback) {
		World world = (World) (Object) this;
		if(StellarSkyTime.getMultiplier(world) < 0.0) {
			world.getWorldInfo().setWorldTime(time);
			callback.cancel();
		}
	}

	@ModifyArg(
			method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;setWorldTime(J)V"),
			index = 0,
			require = 1)
	private long stellarium$scaleClientDaylightClock(long vanillaNextTime) {
		return StellarSkyTime.nextWorldTime((World) (Object) this, vanillaNextTime - 1L);
	}
}
