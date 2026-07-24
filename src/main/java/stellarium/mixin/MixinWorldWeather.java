package stellarium.mixin;

import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import stellarium.time.StellarSkyTime;

@Mixin(World.class)
public abstract class MixinWorldWeather {
	@ModifyArg(
			method = "updateWeatherBody",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/storage/WorldInfo;setRainTime(I)V"),
			index = 0)
	private int stellarium$scaleRainTimer(int vanillaNextTime) {
		World world = (World) (Object) this;
		WorldInfo info = world.getWorldInfo();
		return vanillaNextTime == info.getRainTime() - 1
				? StellarSkyTime.nextWeatherTime(world, info.getRainTime()) : vanillaNextTime;
	}

	@ModifyArg(
			method = "updateWeatherBody",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/storage/WorldInfo;setThunderTime(I)V"),
			index = 0)
	private int stellarium$scaleThunderTimer(int vanillaNextTime) {
		World world = (World) (Object) this;
		WorldInfo info = world.getWorldInfo();
		return vanillaNextTime == info.getThunderTime() - 1
				? StellarSkyTime.nextWeatherTime(world, info.getThunderTime()) : vanillaNextTime;
	}
}
