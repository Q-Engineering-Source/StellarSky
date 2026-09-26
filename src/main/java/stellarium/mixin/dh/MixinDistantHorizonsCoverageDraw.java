package stellarium.mixin.dh;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import stellarium.client.ring.dh.DistantHorizonsFrameCoverageBridge;

@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram", remap = false)
public abstract class MixinDistantHorizonsCoverageDraw {
    @WrapMethod(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            remap = false, require = 1, allow = 1)
    private void stellarium$observePass(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> buffers,
                                       IProfilerWrapper profiler, Operation<Void> original) {
        DistantHorizonsFrameCoverageBridge.beginPass(params, opaque, buffers);
        original.call(params, opaque, buffers, profiler);
        DistantHorizonsFrameCoverageBridge.finishPass(params, opaque);
    }

    @WrapOperation(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33;glDrawElements(IIIJ)V"), require = 1, allow = 1)
    private void stellarium$observeActualDraw(int mode, int count, int type, long indices, Operation<Void> original,
                                            @Local LodBufferContainer buffer, @Local GLVertexBuffer vbo,
                                            @Local(argsOnly = true) RenderParams params,
                                            @Local(argsOnly = true) boolean opaque) {
        original.call(mode, count, type, indices);
        if (count > 0) DistantHorizonsFrameCoverageBridge.submitted(params, opaque, buffer, vbo);
    }
}
