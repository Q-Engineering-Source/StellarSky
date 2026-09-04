package stellarium.mixin;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.RingworldTerrainLightmap;

/** Applies the frozen display field after vanilla has prepared the concrete chunk draw. */
@Mixin(ChunkRenderContainer.class)
public abstract class MixinChunkRenderContainerRingworldTerrainLightmap {
    @Inject(method = "preRenderChunk(Lnet/minecraft/client/renderer/chunk/RenderChunk;)V",
            at = @At("RETURN"), require = 1)
    private void stellarium$applyRingworldTerrainLightmap(RenderChunk renderChunk, CallbackInfo callback) {
        RingworldTerrainLightmap.applyChunk(renderChunk);
    }
}
