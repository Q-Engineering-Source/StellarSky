package stellarium.mixin.dh;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import stellarium.render.util.SamplerBindings;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL33;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import stellarium.client.ring.dh.DistantHorizonsCurvatureState;
import stellarium.client.ring.dh.DistantHorizonsCurvatureUniforms;
import stellarium.client.ring.RingworldOwnMediaOcclusion;
import stellarium.client.ring.RingworldGpuProfile;

/** Owns the per-linked-program bindings and suppresses only DH's competing Earth curve per SS draw. */
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram", remap = false)
public abstract class MixinDistantHorizonsTerrainProgram {
    @Unique private DistantHorizonsCurvatureUniforms stellarium$curvature;

    @Shadow public int uEarthRadius;
    @Shadow public abstract int getId();

    // 3.2.0-b has one early return when already initialized and one successful-init return.
    // Both must bind (or preserve) the same program-local object.
    @Inject(method = "tryInit()V", at = @At("RETURN"), require = 2, allow = 2)
    private void stellarium$bindCurvature(CallbackInfo callback) {
        if (stellarium$curvature == null) {
            stellarium$curvature = new DistantHorizonsCurvatureUniforms(getId());
        }
    }

    @Inject(method = "fillUniformData(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void stellarium$uploadCurvature(DhApiRenderParam parameters, CallbackInfo callback) {
        var frame = DistantHorizonsCurvatureState.currentFrame();
        double cameraOffsetX = 0.0;
        double cameraOffsetY = 0.0;
        double cameraOffsetZ = 0.0;
        if (frame != null) {
            if (!(parameters instanceof RenderParams renderParams) || renderParams.exactCameraPosition == null) {
                throw new IllegalStateException("Distant Horizons did not expose its exact camera for a curved LOD draw");
            }
            cameraOffsetX = renderParams.exactCameraPosition.x - frame.renderOrigin().x();
            cameraOffsetY = renderParams.exactCameraPosition.y - frame.renderOrigin().y();
            cameraOffsetZ = renderParams.exactCameraPosition.z - frame.renderOrigin().z();
            // DH has just uploaded the user's normal Earth-curvature setting.  Do not persist or
            // mutate it: zero the active program only because the native SS curve replaces it.
            GL20.glUniform1f(uEarthRadius, 0.0F);
        }
        stellarium$curvature.upload(frame, cameraOffsetX, cameraOffsetY, cameraOffsetZ);
    }

    /**
     * Keeps the own-media distance texture bound only across DH terrain draws, restoring both the
     * previous unit-seven binding and caller-active unit even if DH throws while drawing a VBO.
     */
    @WrapMethod(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;Z"
            + "Lcom/seibel/distanthorizons/core/util/objects/SortedArraySet;"
            + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            remap = false, require = 1, allow = 1)
    private void stellarium$withOwnMediaOcclusion(RenderParams params, boolean opaquePass,
                                                   SortedArraySet<LodBufferContainer> buffers, IProfilerWrapper profiler,
                                                   Operation<Void> original) {
        if (stellarium$curvature == null) {
            throw new IllegalStateException("Distant Horizons terrain program drew before its curvature uniforms initialized");
        }
        int priorActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 7);
        int priorUnitSevenTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int priorUnitSevenSampler = SamplerBindings.get(7);
        try {
            var frame = DistantHorizonsCurvatureState.currentFrame();
            RingworldOwnMediaOcclusion.Snapshot media = frame == null ? null : RingworldOwnMediaOcclusion.current();
            if (frame != null) {
                if (media == null || media.frame() != frame) {
                    throw new IllegalStateException("Curved Distant Horizons draw has no matching captured own-media depth");
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, media.textureId());
            }
            // texelFetch is unfiltered, but a caller-owned comparison sampler can still alter
            // completeness/type behavior. Unit zero is the explicit plain-texture sampler state.
            GL33.glBindSampler(7, 0);
            stellarium$curvature.uploadOwnMedia(frame, media);
            try (var timing = RingworldGpuProfile.measure(opaquePass
                    ? RingworldGpuProfile.Stage.DH_OPAQUE : RingworldGpuProfile.Stage.DH_TRANSLUCENT)) {
                original.call(params, opaquePass, buffers, profiler);
            }
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, priorUnitSevenTexture);
            GL33.glBindSampler(7, priorUnitSevenSampler);
            GL13.glActiveTexture(priorActiveTexture);
        }
    }
}
