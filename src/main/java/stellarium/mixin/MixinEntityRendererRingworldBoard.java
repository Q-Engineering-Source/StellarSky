package stellarium.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import stellarium.client.ring.RingworldBoardRenderer;

/** Draw after sky depth cleanup and projection restoration, before clouds/terrain. */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererRingworldBoard {
    @ModifyArg(method = "renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;setupFog(IF)V", ordinal = 1),
            index = 1, require = 1, allow = 1)
    private float stellarium$drawBoardBeforeWorldFog(float partialTicks) {
        RingworldBoardRenderer.renderCurrentWorld(partialTicks);
        return partialTicks;
    }
}
