package stellarium.world.ring;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Connects the position-aware sky field to vanilla's effective-light consumers. */
public final class RingworldLighting {
    private static final int WORLD_BORDER = 30_000_000;

    private RingworldLighting() {
    }

    public static @Nullable RingworldLightFrame frame(World world) {
        return world instanceof RingworldLightAccess access
                ? access.stellarium$getRingworldLightFrame() : null;
    }

    public static void publish(World world, @Nullable RingworldLightFrame frame) {
        if (world instanceof RingworldLightAccess access) {
            access.stellarium$setRingworldLightFrame(frame);
        } else if (frame != null) {
            throw new IllegalStateException("Ringworld lighting requires the World lighting Mixin");
        }
    }

    public static int skySubtraction(World world, BlockPos pos, int vanillaSubtraction) {
        RingworldLightFrame frame = frame(world);
        return frame == null ? vanillaSubtraction
                : frame.skySubtraction(vanillaSubtraction, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** Matches World#getLight's horizontal boundary before a ring-specific high-Y read. */
    public static boolean isWithinHorizontalWorldBounds(BlockPos pos) {
        return pos.getX() >= -WORLD_BORDER && pos.getZ() >= -WORLD_BORDER
                && pos.getX() < WORLD_BORDER && pos.getZ() < WORLD_BORDER;
    }

    public static boolean isDaytimeAt(World world, BlockPos pos) {
        RingworldLightFrame frame = frame(world);
        return frame == null ? world.isDaytime()
                : frame.skySubtraction(world.getSkylightSubtracted(),
                        pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) < 4;
    }
}
