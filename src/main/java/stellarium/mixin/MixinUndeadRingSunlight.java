package stellarium.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.AbstractSkeleton;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.world.ring.RingworldLighting;

@Mixin({EntityZombie.class, AbstractSkeleton.class})
public abstract class MixinUndeadRingSunlight {
    // Keep vanilla roof, helmet, child, randomness and burn-immunity checks.
    // A bright torch under an opaque panel must not count as direct sunlight.
    @Redirect(
            method = "onLivingUpdate()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isDaytime()Z"),
            require = 1)
    private boolean stellarium$readLocalDaytime(World world) {
        return RingworldLighting.isDaytimeAt(world, ((Entity) (Object) this).getPosition());
    }
}
