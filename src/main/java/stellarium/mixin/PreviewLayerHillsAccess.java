package stellarium.mixin;

import net.minecraft.world.gen.layer.GenLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.gen.layer.GenLayerHills")
public interface PreviewLayerHillsAccess {
    @Accessor("riverLayer") GenLayer stellarium$riverLayer();
}
