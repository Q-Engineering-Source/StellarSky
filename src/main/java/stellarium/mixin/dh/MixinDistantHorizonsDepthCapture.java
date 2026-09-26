package stellarium.mixin.dh;

import java.nio.IntBuffer;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsCurvatureState;
import stellarium.client.ring.dh.DistantHorizonsDepthBridge;

/** Captures metadata only after DH has applied its LOD color; the texture remains DH-owned. */
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer", remap = false)
public abstract class MixinDistantHorizonsDepthCapture {
    @Unique private static final IntBuffer stellarium$viewport = BufferUtils.createIntBuffer(4);

    @Shadow public abstract int getActiveDepthTextureId();

    @Inject(method = {"applyToMcTexture(Lcom/seibel/distanthorizons/core/render/RenderParams;)V",
            "copyToMcTexture(Lcom/seibel/distanthorizons/core/render/RenderParams;)V"}, at = @At("RETURN"),
            require = 1, allow = 1)
    private void stellarium$captureDepth(RenderParams params, CallbackInfo callback) {
        var frame = DistantHorizonsCurvatureState.currentFrame();
        if (frame == null) {
            DistantHorizonsDepthBridge.clear();
            return;
        }
        if (params.exactCameraPosition == null || params.dhInverseMvmProjectionMatrix == null) {
            throw new IllegalStateException("Distant Horizons omitted curved-depth camera or inverse matrix");
        }
        stellarium$viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, stellarium$viewport);
        double offsetX = params.exactCameraPosition.x - frame.renderOrigin().x();
        double offsetY = params.exactCameraPosition.y - frame.renderOrigin().y();
        double offsetZ = params.exactCameraPosition.z - frame.renderOrigin().z();
        DistantHorizonsDepthBridge.capture(frame, getActiveDepthTextureId(),
                stellarium$columnMajor(params.dhInverseMvmProjectionMatrix.getValuesAsArray()),
                stellarium$viewport.get(0), stellarium$viewport.get(1),
                stellarium$viewport.get(2), stellarium$viewport.get(3), offsetX, offsetY, offsetZ);
    }

    /** DH API exposes rows; DH's own GL uploader stores m[row][column] at column*4+row. */
    @Unique private static float[] stellarium$columnMajor(float[] rowMajor) {
        if (rowMajor == null || rowMajor.length != 16) {
            throw new IllegalArgumentException("Distant Horizons matrix must contain 16 values");
        }
        float[] result = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                result[column * 4 + row] = rowMajor[row * 4 + column];
            }
        }
        return result;
    }
}
