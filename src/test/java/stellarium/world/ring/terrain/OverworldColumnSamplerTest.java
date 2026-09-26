package stellarium.world.ring.terrain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class OverworldColumnSamplerTest {
    @Test public void singleNoiseColumnMatchesTheFullVanillaLatticeAtBothSignsOfOrigin() {
        int checks = 0;
        for (long seed : new long[]{0L, 1L, -1L, 123456789L}) {
            var noises = OverworldColumnSampler.Noises.fromSeed(seed);
            for (int x : new int[]{-512, -4, 0, 4, 511}) for (int z : new int[]{-512, -4, 0, 4, 511}) {
                var fullMain = noises.main().generateNoiseOctaves(null, x, 0, z, 5, 33, 5,
                        684.412D, 684.412D, 684.412D);
                var columnMain = noises.main().generateNoiseOctaves(null, x, 0, z, 1, 33, 1,
                        684.412D, 684.412D, 684.412D);
                assertTrue(Arrays.equals(Arrays.copyOf(fullMain, 33), columnMain));
                var fullDepth = noises.depth().generateNoiseOctaves(null, x, z, 5, 5, 200D, 200D, 0.5D);
                var columnDepth = noises.depth().generateNoiseOctaves(null, x, z, 1, 1, 200D, 200D, 0.5D);
                assertEquals(fullDepth[0], columnDepth[0], 0D);
                checks += 2;
            }
        }
        assertEquals(200, checks);
    }

    @Test public void verticalColumnSurfaceMatchesFullLatticeAtLocalOrigin() {
        for (int seaLevel : new int[]{0, 63, 256}) for (int boundary = 0; boundary <= 32; boundary++) {
            var density = new double[33];
            for (int y = 0; y < density.length; y++) density[y] = boundary - y + 0.125D;
            var full = new double[825];
            System.arraycopy(density, 0, full, 0, density.length);
            var expected = new OverworldDensityLattice(5, -9, seaLevel, full).surface(0, 0);
            assertEquals(expected, OverworldColumnSampler.surface(density, seaLevel));
        }
    }
}
