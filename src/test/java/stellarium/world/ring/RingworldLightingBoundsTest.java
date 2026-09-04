package stellarium.world.ring;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.util.math.BlockPos;
import org.junit.Test;

public class RingworldLightingBoundsTest {
    @Test
    public void highReceiverPathRetainsVanillasInclusiveLowerAndExclusiveUpperBounds() {
        assertFalse(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(-30_000_001, 520, 0)));
        assertTrue(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(-30_000_000, 520, 0)));
        assertTrue(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(29_999_999, 520, 0)));
        assertFalse(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(30_000_000, 520, 0)));
        assertFalse(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(0, 520, -30_000_001)));
        assertTrue(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(0, 520, -30_000_000)));
        assertTrue(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(0, 520, 29_999_999)));
        assertFalse(RingworldLighting.isWithinHorizontalWorldBounds(new BlockPos(0, 520, 30_000_000)));
    }
}
