package stellarium.mixin;

import net.minecraft.world.gen.layer.GenLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.gen.layer.GenLayer")
public interface PreviewLayerBaseAccess {
    @Accessor("worldGenSeed") long stellarium$worldGenSeed();
    @Accessor("baseSeed") long stellarium$baseSeed();
    @Accessor("parent") GenLayer stellarium$parent();
}
