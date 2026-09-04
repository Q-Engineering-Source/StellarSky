package stellarium.mixin;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.time.StellarSkyTime;
import stellarium.world.StellarScene;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {
	@Redirect(
			method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;setWorldTime(J)V", ordinal = 1),
			require = 1)
	private void stellarium$writeScopedDaylightClock(WorldServer receiver, long vanillaNextTime) {
		World world = (World) (Object) this;
		StellarScene scene = StellarScene.getScene(world);
		if (scene != null) {
			scene.applyKnownWorldTimeUpdate(world, vanillaNextTime - 1L);
		} else {
			receiver.setWorldTime(StellarSkyTime.calculateNextWorldTime(world, vanillaNextTime - 1L).worldTime());
		}
	}
}
