package stellarium.mixin;

import net.minecraft.world.gen.layer.GenLayerEdge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.gen.layer.GenLayerEdge")
public interface PreviewLayerEdgeAccess {
    @Accessor("mode") GenLayerEdge.Mode stellarium$mode();
}
