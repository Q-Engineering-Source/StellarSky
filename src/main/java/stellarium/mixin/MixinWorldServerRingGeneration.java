package stellarium.mixin;

import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import stellarium.world.ring.generation.RingworldGenerationRuntime;

@Mixin(WorldServer.class)
public abstract class MixinWorldServerRingGeneration {
    @Redirect(method = "createChunkProvider", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/WorldProvider;createChunkGenerator()Lnet/minecraft/world/gen/IChunkGenerator;"),
            require = 1, allow = 1)
    private IChunkGenerator stellarium$createPolicyBoundGenerator(WorldProvider provider) {
        return RingworldGenerationRuntime.create((WorldServer) (Object) this, provider);
    }
}
