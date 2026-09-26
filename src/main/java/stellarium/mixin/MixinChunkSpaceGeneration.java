package stellarium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.world.ring.generation.RingworldChunkGenerator;
import stellarium.world.ring.generation.RingworldGenerationScope;

@Mixin(Chunk.class)
public abstract class MixinChunkSpaceGeneration {
    @Shadow @Final private World world;

    // Entire method includes GameRegistry.generateWorld after generator.populate.
    // Both actual MCP/SRG selectors are needed by the project's WrapMethod remap contract.
    @WrapMethod(method = {"populate(Lnet/minecraft/world/gen/IChunkGenerator;)V",
            "func_186034_a(Lnet/minecraft/world/gen/IChunkGenerator;)V"}, remap = false, require = 1, allow = 1)
    private void stellarium$scopedNaturalPopulation(IChunkGenerator generator, Operation<Void> original) {
        if (generator instanceof RingworldChunkGenerator ring) {
            try (var scope = ring.openScope()) { original.call(generator); }
        } else {
            original.call(generator);
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void stellarium$rejectDirectNaturalSpaceWrite(BlockPos pos, IBlockState state,
                                                        CallbackInfoReturnable<IBlockState> result) {
        if (RingworldGenerationScope.suppresses(world, pos)) result.setReturnValue(null);
    }
}
