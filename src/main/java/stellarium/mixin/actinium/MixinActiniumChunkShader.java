package stellarium.mixin.actinium;

import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderOptions;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.client.ring.actinium.ActiniumCurvatureUniforms;
import stellarium.client.ring.actinium.ActiniumLocalLightUniforms;

@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.shader.DefaultChunkShaderInterface", remap = false)
public abstract class MixinActiniumChunkShader {
    @Unique private ActiniumCurvatureUniforms stellarium$curvature;
    @Unique private ActiniumLocalLightUniforms stellarium$light;

    @Inject(method = "<init>(Lorg/embeddedt/embeddium/impl/gl/shader/ShaderBindingContext;Lorg/embeddedt/embeddium/impl/render/chunk/shader/ChunkShaderOptions;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void stellarium$bindCurvature(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo callback) {
        stellarium$curvature = new ActiniumCurvatureUniforms(context);
        stellarium$light = new ActiniumLocalLightUniforms(context);
    }

    @Inject(method = "setupState(Lorg/embeddedt/embeddium/impl/render/chunk/terrain/TerrainRenderPass;)V",
            at = @At("HEAD"), require = 1, allow = 1)
    private void stellarium$resetCurvature(TerrainRenderPass pass, CallbackInfo callback) {
        stellarium$curvature.reset();
        stellarium$light.reset();
    }

    @Inject(method = "setModelViewMatrix(Lorg/embeddedt/embeddium/impl/shadow/joml/Matrix4fc;)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void stellarium$uploadCurvature(Matrix4fc matrix, CallbackInfo callback) {
        var snapshot = RingworldRenderSnapshots.current();
        var frame = snapshot == null ? null
                : RingworldRenderSnapshots.currentCurvatureFrameFor(snapshot.world(), snapshot.scene());
        stellarium$curvature.upload(frame, matrix);
        stellarium$light.upload(ActiniumLocalLightUniforms.currentLightingFrame());
    }
}
