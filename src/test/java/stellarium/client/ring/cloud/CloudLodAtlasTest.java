package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import org.junit.Test;

/** Pure CPU proof that the uploaded atlas is a bounded absolute world cache. */
public class CloudLodAtlasTest {
    @Test public void pagesFitTheExplicitSixteenMiBUploadAndThirtyTwoMiBCpuBudget() {
        assertEquals(512, CloudLodAtlas.WIDTH);
        assertEquals(8192, CloudLodAtlas.HEIGHT);
        assertEquals(16 * 1024 * 1024, CloudLodAtlas.BYTE_SIZE);
        assertEquals(9_699_328L, CloudWorldCache.SOURCE_PAGE_BYTES);
        assertEquals(28_573_696L, CloudWorldCache.STEADY_CPU_BYTES);
        assertTrue(CloudWorldCache.STEADY_CPU_BYTES <= 32L * 1024L * 1024L);
        assertEquals(57_147_392L, CloudWorldCache.TRANSITION_CPU_BYTES);
        assertEquals(8_388_608.0D, CloudLodLayout.MAX_CACHED_TAIL_DISTANCE, 0.0D);
    }

    @Test public void defaultAndNonDefaultCellScalesUseTheSameMaterialFieldAtEquivalentWorldCells() {
        CloudFieldSettings settings = new CloudFieldSettings(71L, .45D, false, .2D);
        CloudWorldCache twelve = CloudWorldCache.around(1L, settings, new CloudGeometrySettings(12.0D, 4.0D, 64), 100L, -200L);
        CloudWorldCache twentyFour = CloudWorldCache.around(2L, settings, new CloudGeometrySettings(24.0D, 8.0D, 64), 50L, -100L);
        assertEquals(twelve.fineMask().cellArgb(100L, 3, -200L), twentyFour.fineMask().cellArgb(50L, 3, -100L));
    }

    @Test public void finiteFinePageDoesNotWrapAtNegativeOrPositiveBounds() {
        CloudWorldCache cache = CloudWorldCache.around(3L, CloudFieldSettings.DEFAULT,
                new CloudGeometrySettings(12.0D, 4.0D, 64), -17L, 21L);
        CloudMask fine = cache.fineMask();
        assertFalse(fine.repeating());
        assertEquals(0, fine.cellArgb(fine.originX() - 1L, 0, fine.originZ()));
        assertEquals(0, fine.cellArgb(fine.originX() + fine.width(), 0, fine.originZ()));
        ByteBuffer rgba = CloudLodAtlas.toRgba8(cache);
        assertTrue(rgba.isDirect()); assertEquals(CloudLodAtlas.BYTE_SIZE, rgba.remaining());
    }

    @Test public void tailPageCoversItsDeclaredTierInsteadOfPretendingToBeInfinite() {
        CloudLodLayout.AtlasLevel tail = CloudLodLayout.LOD12_2D_TAIL;
        assertEquals(512, tail.width());
        assertEquals(4096, tail.xzScale());
        assertTrue(tail.width() * 12.0D * tail.xzScale() / 2.0D > CloudLodLayout.MAX_CACHED_TAIL_DISTANCE);
    }

    @Test public void underSizedFineCellConfigurationFailsInsteadOfSilentlyShrinkingThe2048Band() {
        assertThrows(IllegalArgumentException.class, () -> CloudWorldCache.around(4L, CloudFieldSettings.DEFAULT,
                new CloudGeometrySettings(4.0D, 4.0D, 64), 0L, 0L));
    }

    @Test public void horizonAwareValidationRejectsDefaultCellsAt65536ButKeepsTheLegal64CellCombination() {
        assertThrows(IllegalArgumentException.class, () -> CloudWorldCache.requireBandCoverage(
                new CloudGeometrySettings(12.0D, 4.0D, 64), 65_536));
        CloudWorldCache.requireBandCoverage(new CloudGeometrySettings(64.0D, 4.0D, 64), 65_536);
    }

    @Test public void everyFinite2dPageCoversItsEntireDeclaredDistanceTier() {
        assertCoverage(CloudLodLayout.LOD4_2D_HIGH, CloudLodLayout.HIGH_2D_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD5_2D_MID, CloudLodLayout.MID_2D_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD6_2D_LOW, CloudLodLayout.LOW_OBSERVATION_DISTANCE);
        assertCoverage(CloudLodLayout.LOD7_2D_LOWER, CloudLodLayout.LOW_2D_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD8_2D_TAIL, CloudLodLayout.TAIL8_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD9_2D_TAIL, CloudLodLayout.TAIL9_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD10_2D_TAIL, CloudLodLayout.TAIL10_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD11_2D_TAIL, CloudLodLayout.TAIL11_END_DISTANCE);
        assertCoverage(CloudLodLayout.LOD12_2D_TAIL, CloudLodLayout.MAX_CACHED_TAIL_DISTANCE);
    }

    private static void assertCoverage(CloudLodLayout.AtlasLevel level, double requiredDistance) {
        assertTrue(level.width() * 12.0D * level.xzScale() / 2.0D >= requiredDistance);
        double cellWidth = 12.0D * level.xzScale();
        assertTrue(-level.depth() / 2.0D * cellWidth <= -8_192.0D - cellWidth);
        assertTrue((level.depth() - level.depth() / 2.0D) * cellWidth >= 8_192.0D + cellWidth);
    }

    @Test public void twoDPagesKeepTheRingStripIndexedAtNegativeZRegardlessOfOuterObserverPosition() {
        CloudWorldCache cache = CloudWorldCache.around(7L, CloudFieldSettings.DEFAULT,
                new CloudGeometrySettings(12.0D, 4.0D, 64), -31L, 4_000_000L);
        for (CloudLodLayout.AtlasLevel level : CloudLodLayout.TWO_DIMENSIONAL_LEVELS) {
            CloudWorldCache.Page page = cache.page(level);
            assertEquals(-level.depth() / 2L, page.originZ());
            assertTrue(page.containsCell(-1L, 0L));
            assertTrue(page.containsCell(0L, -1L));
            assertTrue(page.containsCell(0L, 0L));
        }
    }

    @Test public void movedAnchorReusesExactOverlappingAbsoluteFineCells() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(12.0D, 4.0D, 64);
        CloudWorldCache first = CloudWorldCache.around(5L, CloudFieldSettings.DEFAULT, geometry, 0L, 0L);
        CloudWorldCache shifted = CloudWorldCache.around(6L, CloudFieldSettings.DEFAULT, geometry, 16L, 0L, first);
        CloudMask a = first.fineMask(), b = shifted.fineMask();
        for (long z = Math.max(a.originZ(), b.originZ()); z < Math.min(a.originZ() + a.depth(), b.originZ() + b.depth()); z += 31) {
            for (long x = Math.max(a.originX(), b.originX()); x < Math.min(a.originX() + a.width(), b.originX() + b.width()); x += 29) {
                for (int layer = 0; layer < 8; layer++) assertEquals(a.cellArgb(x, layer, z), b.cellArgb(x, layer, z));
            }
        }
    }

    @Test public void reportsOneColdAndOneOverlappingReuseGeneration() {
        CloudGeometrySettings geometry = new CloudGeometrySettings(12.0D, 4.0D, 64);
        long start = System.nanoTime();
        CloudWorldCache cold = CloudWorldCache.around(8L, CloudFieldSettings.DEFAULT, geometry, 0L, 0L);
        long coldNanos = System.nanoTime() - start;
        start = System.nanoTime();
        CloudWorldCache.around(9L, CloudFieldSettings.DEFAULT, geometry, 16L, 0L, cold);
        long reuseNanos = System.nanoTime() - start;
        System.out.println("cloud_cache_cold_ms=" + coldNanos / 1_000_000.0D + " reuse_ms=" + reuseNanos / 1_000_000.0D);
        assertTrue("overlap reuse should avoid a full field resample", reuseNanos < coldNanos);
    }
}
