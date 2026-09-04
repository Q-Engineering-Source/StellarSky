package stellarium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import stellarium.client.ring.RingworldTerrainLightmap;

/** VBO terrain must retain raw SKY baked UVs until the render-time lightmap scope. */
@Mixin(VboRenderList.class)
public abstract class MixinVboRenderListRingworldTerrainLightmap {
    @WrapMethod(method = {"renderChunkLayer(Lnet/minecraft/util/BlockRenderLayer;)V",
            "func_178001_a(Lnet/minecraft/util/BlockRenderLayer;)V"}, remap = false, require = 1, allow = 1)
    private void stellarium$renderWithRingworldTerrainLightmap(BlockRenderLayer layer, Operation<Void> original) {
        try (RingworldTerrainLightmap.Scope ignored = RingworldTerrainLightmap.openCurrentWorld()) {
            original.call(layer);
        }
    }
}
