package stellarium.mixin.actinium;

import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Upstream logs shader failures and caches null; the required SS renderer must fail instead. */
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class MixinActiniumTerrainProgram {
    @Inject(method = "compileProgram(Lorg/embeddedt/embeddium/impl/render/chunk/shader/ChunkShaderOptions;)Lorg/embeddedt/embeddium/impl/gl/shader/GlProgram;",
            at = @At("RETURN"), require = 2, allow = 2)
    private void stellarium$requireLinkedProgram(ChunkShaderOptions options,
                                                CallbackInfoReturnable<GlProgram<?>> callback) {
        if (callback.getReturnValue() == null) {
            throw new IllegalStateException("Actinium terrain program failed for " + options.pass().name()
                    + "; StellarSky requires the native GL4/FP64 program. See the preceding Actinium shader error for the original cause.");
        }
    }
}
