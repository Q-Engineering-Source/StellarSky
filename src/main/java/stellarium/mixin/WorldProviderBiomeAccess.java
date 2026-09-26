package stellarium.mixin;

import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Narrow initialization bridge for the provider's protected biome field. */
@Mixin(WorldProvider.class)
public interface WorldProviderBiomeAccess {
    @Accessor("biomeProvider") void stellarium$setGenerationBiomes(BiomeProvider provider);
}
