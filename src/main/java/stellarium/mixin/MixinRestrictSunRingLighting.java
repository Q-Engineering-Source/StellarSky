package stellarium.mixin;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.ai.EntityAIRestrictSun;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.world.ring.RingworldLighting;

@Mixin(EntityAIRestrictSun.class)
public abstract class MixinRestrictSunRingLighting {
    @Shadow @Final private EntityCreature entity;

    @Redirect(method = "shouldExecute()Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isDaytime()Z"), require = 1)
    private boolean stellarium$readLocalDaytime(World world) {
        return RingworldLighting.isDaytimeAt(world, entity.getPosition());
    }
}
