package stellarium.mixin;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stellarium.world.ring.RingworldLightAccess;
import stellarium.world.ring.RingworldLightFrame;
import stellarium.world.ring.RingworldLighting;

@Mixin(World.class)
public abstract class MixinWorldRingLighting implements RingworldLightAccess {
    @Unique
    private volatile RingworldLightFrame stellarium$ringworldFrame;

    @Override
    public @Nullable RingworldLightFrame stellarium$getRingworldLightFrame() {
        return stellarium$ringworldFrame;
    }

    @Override
    public void stellarium$setRingworldLightFrame(@Nullable RingworldLightFrame frame) {
        stellarium$ringworldFrame = frame;
    }

    // Raw getLightFor and the propagation/storage engine deliberately remain unchanged.
    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;Z)I",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void stellarium$readHighReceiverFromBuildCeiling(BlockPos receiver, boolean checkNeighbors,
                                                              CallbackInfoReturnable<Integer> callback) {
        // Vanilla clamps y >= 256 to 255 after its optional neighbor-brightness branch.
        // AIR above the build ceiling does not take that branch, so this preserves the
        // storage read while retaining the original receiver height for the board plane.
        if (receiver.getY() < 256 || !RingworldLighting.isWithinHorizontalWorldBounds(receiver)) {
            return;
        }
        World world = (World) (Object) this;
        RingworldLightFrame frame = RingworldLighting.frame(world);
        if (frame == null) {
            return;
        }
        BlockPos storagePos = new BlockPos(receiver.getX(), 255, receiver.getZ());
        Chunk storageChunk = world.getChunk(receiver.getX() >> 4, receiver.getZ() >> 4);
        callback.setReturnValue(storageChunk.getLightSubtracted(storagePos,
                frame.skySubtraction(world.getSkylightSubtracted(), receiver.getX() + 0.5,
                        receiver.getY(), receiver.getZ() + 0.5)));
    }

    @Redirect(
            method = "getLight(Lnet/minecraft/util/math/BlockPos;Z)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;getLightSubtracted(Lnet/minecraft/util/math/BlockPos;I)I"),
            require = 1)
    private int stellarium$readLocalSky(Chunk chunk, BlockPos pos, int vanillaSubtraction) {
        return chunk.getLightSubtracted(pos,
                RingworldLighting.skySubtraction((World) (Object) this, pos, vanillaSubtraction));
    }
}
