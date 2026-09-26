package stellarium.mixin.dh;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disables every cached DH cloud box at creation and before its sole 3.2.0-b render callback. */
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler", remap = false)
public abstract class MixinDistantHorizonsCloudRenderer {
    @Shadow private IDhApiRenderableBoxGroup[][][] boxGroupByOffset;

    @Inject(method = "<init>(Lcom/seibel/distanthorizons/core/level/IDhClientLevel;"
                    + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhGenericRenderer;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void stellarium$disableCreatedClouds(CallbackInfo callback) {
        stellarium$deactivateCloudBoxes();
    }

    @Inject(method = "preRender(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;"
                    + "Lcom/seibel/distanthorizons/core/render/renderer/CloudRenderHandler$CloudParams;)V",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void stellarium$disableCloudRender(CallbackInfo callback) {
        stellarium$deactivateCloudBoxes();
        callback.cancel();
    }

    private void stellarium$deactivateCloudBoxes() {
        for (IDhApiRenderableBoxGroup[][] layer : boxGroupByOffset) {
            for (IDhApiRenderableBoxGroup[] row : layer) {
                for (IDhApiRenderableBoxGroup group : row) {
                    if (group != null) {
                        group.setActive(false);
                    }
                }
            }
        }
    }
}
