package stellarium.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.world.ring.RingworldLighting;

@Mixin(EntityEnderman.class)
public abstract class MixinEndermanRingSunlight {
    @Redirect(method = "updateAITasks()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isDaytime()Z"), require = 1)
    private boolean stellarium$readLocalDaytime(World world) {
        return RingworldLighting.isDaytimeAt(world, ((Entity) (Object) this).getPosition());
    }
}
