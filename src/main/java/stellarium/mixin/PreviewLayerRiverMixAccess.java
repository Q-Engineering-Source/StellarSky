package stellarium.mixin;

import net.minecraft.world.gen.layer.GenLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.gen.layer.GenLayerRiverMix")
public interface PreviewLayerRiverMixAccess {
    @Accessor("biomePatternGeneratorChain") GenLayer stellarium$biomePatternGeneratorChain();
    @Accessor("riverPatternGeneratorChain") GenLayer stellarium$riverPatternGeneratorChain();
}
