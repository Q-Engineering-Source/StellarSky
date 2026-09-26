package stellarium.mixin.actinium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.client.ring.actinium.ActiniumTerrainShaderPatch;
import stellarium.client.ring.actinium.ActiniumTerrainShaderSource;

/** Patch the official classpath resource before Actinium expands imports and feature defines. */
@Mixin(targets = "org.embeddedt.embeddium.impl.render.shader.ShaderLoader", remap = false)
public abstract class MixinActiniumShaderLoader {
    @Inject(method = "getShaderSource(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"),
            cancellable = true, require = 1, allow = 1)
    private static void stellarium$patchTerrain(String path, CallbackInfoReturnable<String> callback) {
        if (!ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH.equals(path)) return;
        callback.setReturnValue(ActiniumTerrainShaderPatch.patch(path, callback.getReturnValue(),
                ActiniumTerrainShaderSource.snippet()));
    }
}
