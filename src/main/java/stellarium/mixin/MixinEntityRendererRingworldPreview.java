package stellarium.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.RingworldDistantCurvature;

/**
 * Settles the ringworld preview ground after the opaque world has drawn.
 *
 * <p>Cleanroom 0.6.8 {@code renderWorldPass} calls {@code RenderGlobal.renderBlockLayer}
 * four times: SOLID, CUTOUT_MIPPED, CUTOUT, then TRANSLUCENT. Distant Horizons attaches
 * to the SOLID entry and only there invokes its LOD submission, so this frame's admitted
 * coverage record is published inside that first call. Ordinal one is therefore the first
 * point at which the preview consumer can read it, and it is still ahead of the cutout and
 * translucent layers that must composite over the preview.</p>
 *
 * <p>The ordinal is asserted exactly: {@code require = 1, allow = 1} fails the mixin if a
 * different build exposes more or fewer {@code renderBlockLayer} call sites than the
 * verified Cleanroom 0.6.8 bytecode, rather than silently injecting at the wrong stage.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererRingworldPreview {
    @Inject(method = "renderWorldPass(IFJ)V",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer"
                            + "(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
                    ordinal = 1),
            require = 1, allow = 1)
    private void stellarium$settleDeferredPreview(int pass, float partialTicks, long finishTimeNano,
                                                 CallbackInfo callback) {
        RingworldDistantCurvature.renderDeferredPreview(partialTicks);
    }
}
