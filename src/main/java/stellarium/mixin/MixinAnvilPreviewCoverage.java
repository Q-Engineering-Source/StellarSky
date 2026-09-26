package stellarium.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import stellarium.world.ring.terrain.AnvilPendingCoverage;

@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilPreviewCoverage implements AnvilPendingCoverage {
    @Shadow @Final private Map<ChunkPos, NBTTagCompound> chunksToSave;
    @Shadow @Final private Set<ChunkPos> chunksBeingSaved;
    @Override public boolean stellarium$hasPendingPreviewCoverage() {
        return !chunksToSave.isEmpty() || !chunksBeingSaved.isEmpty();
    }
}
