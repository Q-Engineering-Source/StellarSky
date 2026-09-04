package stellarium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import stellarium.client.ring.RingworldBoardRenderer;

/** Kirino owns a distinct world pass and supplies its own camera partial tick. */
@Mixin(targets = "com.cleanroommc.kirino.KirinoClientCore", remap = false)
public abstract class MixinKirinoRingworldBoard {
    @ModifyArg(method = "EntityRenderer$renderWorld(J)V",
            at = @At(value = "INVOKE", target = "Lcom/cleanroommc/kirino/KirinoClientCore$MethodHolder1;setupFog(Lnet/minecraft/client/renderer/EntityRenderer;IF)V", ordinal = 1),
            index = 2, remap = false, require = 1, allow = 1)
    private static float stellarium$drawBoardBeforeWorldFog(float partialTicks) {
        RingworldBoardRenderer.renderCurrentWorld(partialTicks);
        return partialTicks;
    }
}
