package stellarium.mixin;

import net.minecraft.world.gen.layer.GenLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.biome.BiomeProvider")
public interface PreviewBiomeLayersAccess {
    @Accessor("genBiomes") GenLayer stellarium$genBiomes();
    @Accessor("biomeIndexLayer") GenLayer stellarium$biomeIndexLayer();
}
