package stellarium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderList;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import stellarium.client.ring.RingworldTerrainLightmap;

/** Display-list terrain has a separate concrete render entry and needs the same exception-safe scope. */
@Mixin(RenderList.class)
public abstract class MixinRenderListRingworldTerrainLightmap {
    @WrapMethod(method = {"renderChunkLayer(Lnet/minecraft/util/BlockRenderLayer;)V",
            "func_178001_a(Lnet/minecraft/util/BlockRenderLayer;)V"}, remap = false, require = 1, allow = 1)
    private void stellarium$renderWithRingworldTerrainLightmap(BlockRenderLayer layer, Operation<Void> original) {
        try (RingworldTerrainLightmap.Scope ignored = RingworldTerrainLightmap.openCurrentWorld()) {
            original.call(layer);
        }
    }
}
