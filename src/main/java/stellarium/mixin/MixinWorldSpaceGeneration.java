package stellarium.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.world.ring.generation.RingworldGenerationScope;

@Mixin(World.class)
public abstract class MixinWorldSpaceGeneration {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void stellarium$rejectNaturalSpaceWrite(BlockPos pos, IBlockState state, int flags,
                                                  CallbackInfoReturnable<Boolean> result) {
        if (RingworldGenerationScope.suppresses((World) (Object) this, pos)) result.setReturnValue(false);
    }
}
