package stellarium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import stellarium.client.ring.RingworldRenderSnapshots;

/** Kirino's delegate replaces, rather than calls, vanilla renderWorld. */
@Mixin(targets = "com.cleanroommc.kirino.KirinoClientCore", remap = false)
public abstract class MixinKirinoRingworldSnapshot {
    @ModifyArg(method = "EntityRenderer$renderWorld(J)V",
            at = @At(value = "INVOKE", target = "Lcom/cleanroommc/kirino/KirinoClientCore$MethodHolder1;setupCameraTransform(Lnet/minecraft/client/renderer/EntityRenderer;FI)V"),
            index = 1, remap = false, require = 1, allow = 1)
    private static float stellarium$captureRenderObserver(float partialTicks) {
        RingworldRenderSnapshots.captureCurrentWorldOnce(partialTicks);
        return partialTicks;
    }

    @WrapMethod(method = "EntityRenderer$renderWorld(J)V", remap = false, require = 1)
    private static void stellarium$renderWithRingworldSnapshot(long finishTimeNano,
                                                              Operation<Void> original) {
        RingworldRenderSnapshots.withSnapshot(() -> original.call(finishTimeNano));
    }
}
