package stellarium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import stellarium.client.ring.RingworldRenderSnapshots;

/** Keep both stereo passes and the hands inside one display-time scope. */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererRingworldSnapshot {
    @ModifyArg(method = "renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;setupCameraTransform(FI)V"),
            index = 0, require = 1, allow = 1)
    private float stellarium$captureRenderObserver(float partialTicks) {
        RingworldRenderSnapshots.captureCurrentWorldOnce(partialTicks);
        return partialTicks;
    }

    // Unimined does not remap WrapMethod selectors. Select the verified MCP or
    // production SRG name explicitly, requiring exactly one whole-method target.
    @WrapMethod(method = {"renderWorld(FJ)V", "func_181560_a(FJ)V"},
            remap = false, require = 1, allow = 1)
    private void stellarium$renderWithRingworldSnapshot(float partialTicks, long finishTimeNano,
                                                       Operation<Void> original) {
        RingworldRenderSnapshots.withSnapshot(() -> original.call(partialTicks, finishTimeNano));
    }
}
