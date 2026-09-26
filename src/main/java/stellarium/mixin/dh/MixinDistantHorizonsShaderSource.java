package stellarium.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.client.ring.dh.DistantHorizonsShaderSource;
import stellarium.client.ring.dh.DistantHorizonsTerrainShaderPatch;

/** Patches DH's packaged terrain source before its native GL program compiles. */
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader", remap = false)
public abstract class MixinDistantHorizonsShaderSource {
    @Inject(method = "loadFile(Ljava/lang/String;Z)Ljava/lang/String;", at = @At("RETURN"),
            cancellable = true, require = 1, allow = 1)
    private static void stellarium$patchTerrainSources(String path, boolean filesystem,
                                                       CallbackInfoReturnable<String> callback) {
        if (DistantHorizonsTerrainShaderPatch.VERTEX_PATH.equals(path)) {
            callback.setReturnValue(DistantHorizonsTerrainShaderPatch.patch(path, callback.getReturnValue(),
                    DistantHorizonsShaderSource.curvatureSnippet()));
        } else if (DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH.equals(path)) {
            callback.setReturnValue(DistantHorizonsTerrainShaderPatch.patchFragment(path, callback.getReturnValue(),
                    DistantHorizonsShaderSource.ownMediaOcclusionSnippet()));
        }
    }
}
