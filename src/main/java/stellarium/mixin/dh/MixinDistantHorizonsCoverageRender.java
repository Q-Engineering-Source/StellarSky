package stellarium.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverageBridge;

@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.LodRenderer", remap = false)
public abstract class MixinDistantHorizonsCoverageRender {
    @Shadow @Final private static IIrisAccessor IRIS_ACCESSOR;

    @WrapMethod(method = "renderTerrain(Lcom/seibel/distanthorizons/core/render/RenderParams;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V",
            remap = false, require = 1, allow = 1)
    private void stellarium$observeMainView(RenderParams params, IProfilerWrapper profiler,
                                           boolean deferred, Operation<Void> original) {
        boolean supported = !deferred && !DhApiRenderProxy.INSTANCE.getDeferTransparentRendering()
                && !Config.Client.Advanced.Debugging.renderWireframe.get()
                && params.renderPass == EDhApiRenderPass.OPAQUE_AND_TRANSPARENT
                && (IRIS_ACCESSOR == null || (!IRIS_ACCESSOR.isShaderPackInUse() && !IRIS_ACCESSOR.isRenderingShadowPass()));
        DistantHorizonsFrameCoverageBridge.render(params, supported, () -> original.call(params, profiler, deferred));
    }
}
