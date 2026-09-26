package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import stellarium.client.ring.cloud.CloudClipBounds;
import stellarium.client.ring.cloud.CloudGeometrySettings;
import stellarium.client.ring.cloud.CloudMask;
import stellarium.client.ring.cloud.CloudMeshCacheKey;
import stellarium.client.ring.cloud.CloudMotionFrame;

/** Headless cache-key contracts for the renderer's 16-cell mesh recentering seam.
 * Calling the package seam below initializes {@link SSCloudRenderer} but never enters any GL path. */
public class SSCloudRendererMotionTest {
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12.0D, 4.0D, 1);
    private static final CloudClipBounds CLIP = new CloudClipBounds(0.0D, 256.0D, -8192.0D, 8192.0D);

    @Test
    public void crossingTheFormerMaskWindPeriodDoesNotReturnTheSameMaterialFrame() {
        CloudMask mask = opaqueMask64();
        // 64 cells * 12 blocks / (0.03 blocks/tick) = exactly 25,600 ticks.
        CloudMotionFrame start = CloudMotionFrame.at(0L, 0.0D, 37.5D, -18.25D, mask, GEOMETRY);
        CloudMotionFrame afterPeriod = CloudMotionFrame.at(25_600L, 0.0D, 37.5D, -18.25D, mask, GEOMETRY);

        CloudMotionFrame firstCoarse = SSCloudRenderer.coarseFrame(start, 37.5D, -18.25D, GEOMETRY);
        CloudMotionFrame secondCoarse = SSCloudRenderer.coarseFrame(afterPeriod, 37.5D, -18.25D, GEOMETRY);
        assertNotEquals(firstCoarse, secondCoarse);
        assertNotEquals(firstCoarse.cacheKey(mask, GEOMETRY, 128.0D, CLIP),
                secondCoarse.cacheKey(mask, GEOMETRY, 128.0D, CLIP));
    }

    @Test
    public void movementWithinSixteenCellsRetainsGeometryKeyButUpdatesOnlyCameraOffset() {
        CloudMask mask = opaqueMask64();
        CloudGeometrySettings padded = SSCloudRenderer.paddedGeometry(12.0D, 4.0D, 16);
        CloudMotionFrame first = CloudMotionFrame.at(0L, 0.0D, 1.0D, 2.0D, mask, padded);
        CloudMotionFrame laterInSameCoarseWindow = CloudMotionFrame.at(0L, 0.0D, 181.0D, 2.0D, mask, padded);

        CloudMotionFrame firstCoarse = SSCloudRenderer.coarseFrame(first, 1.0D, 2.0D, padded);
        CloudMotionFrame secondCoarse = SSCloudRenderer.coarseFrame(laterInSameCoarseWindow, 181.0D, 2.0D, padded);
        CloudMeshCacheKey firstKey = firstCoarse.cacheKey(mask, padded, 128.0D, CLIP);
        CloudMeshCacheKey secondKey = secondCoarse.cacheKey(mask, padded, 128.0D, CLIP);

        assertEquals(0L, firstCoarse.anchorCellX());
        assertEquals(0L, secondCoarse.anchorCellX());
        assertEquals(firstKey, secondKey);
        assertEquals(firstCoarse.meshOffsetX() - 180.0D, secondCoarse.meshOffsetX(), 0.0D);
    }

    @Test
    public void paddedGeometryCoversTheRequestedRadiusAtEveryPositiveAndNegativeCoarseEdge() {
        int requestedRadius = 16;
        CloudGeometrySettings padded = SSCloudRenderer.paddedGeometry(12.0D, 4.0D, requestedRadius);
        assertEquals(32, padded.visibleCellRadius());
        CloudMask mask = opaqueMask64();

        for (int cell = 0; cell < 16; cell++) {
            int positive = cell;
            int negative = cell - 16;
            assertCoverage(mask, padded, requestedRadius, positive, positive);
            assertCoverage(mask, padded, requestedRadius, positive, negative);
            assertCoverage(mask, padded, requestedRadius, negative, positive);
            assertCoverage(mask, padded, requestedRadius, negative, negative);
        }
    }

    @Test
    public void paddedGeometryRetainsTheSixtyFourCellHardMeshBudget() {
        assertEquals(64, SSCloudRenderer.paddedGeometry(12.0D, 4.0D, 48).visibleCellRadius());
        assertThrows(IllegalArgumentException.class, () -> SSCloudRenderer.paddedGeometry(12.0D, 4.0D, 15));
        assertThrows(IllegalArgumentException.class, () -> SSCloudRenderer.paddedGeometry(12.0D, 4.0D, 49));
    }

    private static void assertCoverage(CloudMask mask, CloudGeometrySettings padded, int requestedRadius,
                                       int anchorCellX, int anchorCellZ) {
        double cellSize = padded.cellSizeBlocks();
        double observerX = anchorCellX * cellSize + 0.5D;
        double observerZ = anchorCellZ * cellSize + 0.5D;
        CloudMotionFrame motion = CloudMotionFrame.at(0L, 0.0D, observerX, observerZ, mask, padded);
        CloudMotionFrame coarse = SSCloudRenderer.coarseFrame(motion, observerX, observerZ, padded);
        double required = requestedRadius * cellSize;
        double minimumX = coarse.meshOffsetX() - padded.visibleCellRadius() * cellSize;
        double maximumX = coarse.meshOffsetX() + (padded.visibleCellRadius() + 1.0D) * cellSize;
        double minimumZ = coarse.meshOffsetZ() - padded.visibleCellRadius() * cellSize;
        double maximumZ = coarse.meshOffsetZ() + (padded.visibleCellRadius() + 1.0D) * cellSize;
        assertTrue(minimumX <= -required);
        assertTrue(maximumX >= required);
        assertTrue(minimumZ <= -required);
        assertTrue(maximumZ >= required);
    }

    private static CloudMask opaqueMask64() {
        int[] argb = new int[64];
        java.util.Arrays.fill(argb, 0xFFFFFFFF);
        return new CloudMask(1L, 64, 1, argb);
    }
}
