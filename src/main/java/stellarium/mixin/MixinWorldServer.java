package stellarium.mixin;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import stellarium.time.StellarSkyTime;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {
	@ModifyArg(
			method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;setWorldTime(J)V", ordinal = 1),
			index = 0,
			require = 1)
	private long stellarium$scaleDaylightClock(long vanillaNextTime) {
		return StellarSkyTime.nextWorldTime((World) (Object) this, vanillaNextTime - 1L);
	}
}
