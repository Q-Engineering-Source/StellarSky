package stellarium.mixin;

import java.util.List;
import net.minecraftforge.common.BiomeManager;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.gen.layer.GenLayerBiome")
public interface PreviewLayerBiomeAccess {
    @Accessor("biomes") List<BiomeManager.BiomeEntry>[] stellarium$biomes();
    @Accessor("settings") ChunkGeneratorSettings stellarium$settings();
}
