package stellarium.world.ring.generation;

import net.minecraft.world.biome.Biome;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import stellarium.StellarSkyReferences;

/** Registered on both logical sides; numeric IDs are assigned/synchronized by Forge. */
@Mod.EventBusSubscriber(modid = StellarSkyReferences.MODID)
public final class SpaceBiomeRegistry {
    private SpaceBiomeRegistry() {}

    @SubscribeEvent
    public static void register(RegistryEvent.Register<Biome> event) {
        event.getRegistry().register(new SpaceBiome());
    }

    public static SpaceBiome space() {
        var biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(StellarSkyReferences.MODID, "space"));
        if (!(biome instanceof SpaceBiome space)) throw new IllegalStateException("Space biome is not registered");
        return space;
    }
}
