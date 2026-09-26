package stellarium.client.ring.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** Headless contracts for the custom-cloud CPU geometry and stable movement seam. */
public class CloudGeometryTest {
    private static final CloudGeometrySettings ONE_CELL = new CloudGeometrySettings(12.0D, 4.0D, 0);
    private static final CloudClipBounds OPEN_CLIP = new CloudClipBounds(-10.0D, 10.0D, -100.0D, 100.0D);

    @Test
    public void maskIsImmutableAndWrapsNegativeTextureCells() {
        int[] source = {0xFFFF0000, 0x7F00FF00, 0xFF0000FF, 0xFFFFFFFF};
        CloudMask mask = new CloudMask(7L, 2, 2, source);
        source[0] = 0;
        assertEquals(0xFFFF0000, mask.cellArgb(0L, 0L));
        assertEquals(0xFFFFFFFF, mask.cellArgb(-1L, -1L));
        assertTrue(mask.occupiedAt(-2L, 0L));
        assertFalse(mask.occupiedAt(1L, 0L));
        CloudMask volume = new CloudMask(8L, 1, 1, 2, new int[]{0xFFFFFFFF, 0xFF010203});
        assertTrue(volume.occupiedAt(-17L, 0, 24L));
        assertTrue(volume.occupiedAt(-17L, 1, 24L));
        assertFalse(volume.occupiedAt(-17L, -1, 24L));
        assertFalse(volume.occupiedAt(-17L, 2, 24L));
        int[] copy = mask.copyArgb();
        copy[0] = 0;
        assertEquals(0xFFFF0000, mask.cellArgb(0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new CloudMask(0L, 2, 2, new int[3]));
    }

    @Test
    public void settingsAndMotionRejectNonFiniteOrUnboundedInput() {
        assertThrows(IllegalArgumentException.class, () -> new CloudGeometrySettings(Double.NaN, 4.0D, 1));
        assertThrows(IllegalArgumentException.class, () -> new CloudGeometrySettings(12.0D, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new CloudGeometrySettings(12.0D, 4.0D, 65));
        CloudMask mask = opaqueMask(1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> CloudMotionFrame.at(0L, 1.1D, 0.0D, 0.0D, mask, ONE_CELL));
        assertThrows(IllegalArgumentException.class,
                () -> CloudMotionFrame.at(0L, 0.0D, Double.NaN, 0.0D, mask, ONE_CELL));
        assertThrows(IllegalArgumentException.class,
                () -> CloudMotionFrame.at(-1L, 0.0D, 0.0D, 0.0D, mask, ONE_CELL));
        assertThrows(IllegalArgumentException.class,
                () -> CloudMotionFrame.at(CloudMotionFrame.MAX_SUPPORTED_CLIENT_TICKS + 1L,
                        0.0D, 0.0D, 0.0D, mask, ONE_CELL));
    }

    @Test
    public void fixedPositiveXMotionIsStableForStationaryMovingNegativeObserversAndSupportedClock() {
        CloudMask mask = opaqueMask(5, 1);
        CloudGeometrySettings geometry = new CloudGeometrySettings(12.0D, 4.0D, 2);
        CloudMotionFrame stationary = CloudMotionFrame.at(100L, 0.5D, 48.125D, -24.5D, mask, geometry);
        CloudMotionFrame repeated = CloudMotionFrame.at(100L, 0.5D, 48.125D, -24.5D, mask, geometry);
        assertEquals(stationary, repeated);
        assertEquals((100.0D + .5D) * CloudMotionFrame.WIND_BLOCKS_PER_TICK,
                stationary.windOffsetBlocks(), 0.0D);

        CloudMotionFrame movingCamera = CloudMotionFrame.at(100L, 0.5D, 48.5D, -24.5D, mask, geometry);
        assertEquals(stationary.anchorCellX(), movingCamera.anchorCellX());
        assertEquals(stationary.meshOffsetX() - 0.375D, movingCamera.meshOffsetX(), 1.0E-12D);

        CloudMotionFrame huge = CloudMotionFrame.at(100L, .25D,
                -1_000_000_000_000.25D, -1_000_000_000_000.5D, mask, geometry);
        assertEquals((100.0D + .25D) * CloudMotionFrame.WIND_BLOCKS_PER_TICK,
                huge.windOffsetBlocks(), 0.0D);
        assertTrue(huge.meshOffsetX() > -12.0D && huge.meshOffsetX() <= 0.0D);
        assertTrue(huge.meshOffsetZ() > -12.0D && huge.meshOffsetZ() <= 0.0D);

        CloudMotionFrame maxQuarter = CloudMotionFrame.at(CloudMotionFrame.MAX_SUPPORTED_CLIENT_TICKS, .25D,
                0.0D, 0.0D, mask, geometry);
        CloudMotionFrame maxThreeQuarters = CloudMotionFrame.at(CloudMotionFrame.MAX_SUPPORTED_CLIENT_TICKS, .75D,
                0.0D, 0.0D, mask, geometry);
        assertEquals(.5D * CloudMotionFrame.WIND_BLOCKS_PER_TICK,
                maxThreeQuarters.windOffsetBlocks() - maxQuarter.windOffsetBlocks(), 1.0E-5D);
        assertEquals(.5D * CloudMotionFrame.WIND_BLOCKS_PER_TICK,
                maxThreeQuarters.meshOffsetX() - maxQuarter.meshOffsetX(), 1.0E-5D);
    }

    @Test
    public void unwrappedWindCrossesTheOld768BlockPeriodWithoutFormationReset() {
        CloudMask mask = opaqueMask(2, 1);
        CloudGeometrySettings geometry = new CloudGeometrySettings(12.0D, 4.0D, 0);
        // 25,600 ticks * .03 = 768 blocks: the old 32 x 24m wrap point.
        CloudMotionFrame before = CloudMotionFrame.at(25_599L, .999D, 7.25D, 3.75D, mask, geometry);
        CloudMotionFrame after = CloudMotionFrame.at(25_600L, 0.0D, 7.25D, 3.75D, mask, geometry);
        CloudMotionFrame oldTwentyFourBlockPoint = CloudMotionFrame.at(800L, 0.0D, 7.25D, 3.75D, mask, geometry);
        assertEquals(768.0D, after.windOffsetBlocks(), 0.0D);
        assertEquals(.001D * CloudMotionFrame.WIND_BLOCKS_PER_TICK,
                after.windOffsetBlocks() - before.windOffsetBlocks(), 1.0E-9D);
        assertEquals(before.anchorCellX(), after.anchorCellX());
        assertEquals(24.0D, oldTwentyFourBlockPoint.windOffsetBlocks(), 0.0D);
        assertTrue(after.anchorCellX() < oldTwentyFourBlockPoint.anchorCellX());

        CloudMesh beforeMesh = CloudMeshBuilder.build(before.cacheKey(mask, geometry, 0.0D, OPEN_CLIP));
        CloudMesh afterMesh = CloudMeshBuilder.build(after.cacheKey(mask, geometry, 0.0D, OPEN_CLIP));
        float[] beforeVertices = beforeMesh.copyVertices();
        float[] afterVertices = afterMesh.copyVertices();
        for (int index = 0; index < beforeVertices.length; index += CloudMesh.FLOATS_PER_VERTEX) {
            assertEquals(before.meshOffsetX() + beforeVertices[index], after.meshOffsetX() + afterVertices[index],
                    1.0E-3D);
            assertEquals(before.meshOffsetZ() + beforeVertices[index + 2], after.meshOffsetZ() + afterVertices[index + 2],
                    1.0E-6D);
        }
    }

    @Test
    public void cacheKeyContainsAnchorMaskGenerationIdentityAndGeometryButNotFrameFraction() {
        CloudMask first = opaqueMask(1, 1);
        CloudMask sameBitsDifferentIdentity = opaqueMask(1, 1);
        CloudGeometrySettings geometry = new CloudGeometrySettings(12.0D, 4.0D, 1);
        CloudMeshCacheKey firstKey = CloudMotionFrame.at(12L, 0.1D, 1.0D, 1.0D, first, geometry)
                .cacheKey(first, geometry, 0.0D, OPEN_CLIP);
        CloudMeshCacheKey sameCellDifferentFraction = CloudMotionFrame.at(12L, 0.9D, 1.0D, 1.0D, first, geometry)
                .cacheKey(first, geometry, 0.0D, OPEN_CLIP);
        assertEquals(firstKey, sameCellDifferentFraction);
        assertNotEquals(firstKey, CloudMeshCacheKey.at(firstKey.anchorCellX(), firstKey.anchorCellZ(),
                sameBitsDifferentIdentity, geometry, 0.0D, OPEN_CLIP));
        assertNotEquals(firstKey, CloudMeshCacheKey.at(firstKey.anchorCellX(), firstKey.anchorCellZ(), first,
                new CloudGeometrySettings(12.0D, 5.0D, 1), 0.0D, OPEN_CLIP));
        assertThrows(IllegalArgumentException.class, () -> new CloudMeshCacheKey(0L, 0L, 1,
                first.generation() + 1L, first, geometry, 0.0D, OPEN_CLIP));
        assertNotEquals(firstKey, CloudMeshCacheKey.at(firstKey.anchorCellX(), firstKey.anchorCellZ(), first,
                geometry, 0.0D, new CloudClipBounds(-10.0D, 9.0D, -100.0D, 100.0D)));
    }

    @Test
    public void emptyAllAndAdjacentMasksHaveExpectedExposedFacesBoundsWindingAndReadOnlyOutput() {
        CloudMesh empty = CloudMeshBuilder.build(key(0L, 0L,
                new CloudMask(0L, 1, 1, new int[]{0x00000000}), ONE_CELL));
        assertEquals(0, empty.quadCount());

        CloudMesh one = CloudMeshBuilder.build(key(0L, 0L, opaqueMask(1, 1), ONE_CELL));
        assertEquals(6, one.quadCount());
        assertBoundsAndOutwardWinding(one, 0.0F, 12.0F, 0.0F, 12.0F, 0.0F, 4.0F);
        float[] original = one.copyVertices();
        float[] changed = one.copyVertices();
        changed[0] = 99.0F;
        assertArrayEquals(original, one.copyVertices(), 0.0F);
        FloatBuffer readOnly = one.vertexBuffer();
        assertTrue(readOnly.isReadOnly());

        CloudGeometrySettings pairWindow = new CloudGeometrySettings(12.0D, 4.0D, 1);
        CloudMesh allRepeating = CloudMeshBuilder.build(key(0L, 0L, opaqueMask(1, 1), pairWindow));
        // The closed 3x3 solid window is one same-colour rectangular box.
        assertEquals(6, allRepeating.quadCount());

        CloudMask adjacent = new CloudMask(0L, 2, 1, new int[]{0xFF112233, 0xFF445566});
        CloudMesh twoCells = CloudMeshBuilder.build(key(0L, 0L, adjacent, ONE_CELL));
        assertEquals(6, twoCells.quadCount());
    }

    @Test
    public void clippingProducesClosedNewBoundaryCapsAndDropsAirExteriorCells() {
        CloudMask solid = opaqueMask(1, 1);
        CloudClipBounds halfHeight = new CloudClipBounds(2.0D, 6.0D, 3.0D, 9.0D);
        CloudMesh halfCut = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, solid, ONE_CELL, 0.0D, halfHeight));
        assertEquals(6, halfCut.quadCount());
        assertBoundsAndOutwardWinding(halfCut, 0.0F, 12.0F, 3.0F, 9.0F, 2.0F, 4.0F);

        CloudMesh outsideY = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, solid, ONE_CELL, 10.0D,
                new CloudClipBounds(0.0D, 5.0D, -100.0D, 100.0D)));
        CloudMesh outsideZ = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, solid, ONE_CELL, 0.0D,
                new CloudClipBounds(-10.0D, 10.0D, 12.0D, 24.0D)));
        assertEquals(0, outsideY.quadCount());
        assertEquals(0, outsideZ.quadCount());

        // Adjacent remaining cells share their X wall, while the finite 2-cell
        // build window remains closed around every exterior boundary.
        CloudGeometrySettings twoWide = new CloudGeometrySettings(12.0D, 4.0D, 1);
        CloudMask twoColumns = new CloudMask(1L, 2, 1, new int[]{0xFFFFFFFF, 0xFFFFFFFF});
        CloudMesh closed = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, twoColumns, twoWide, 0.0D,
                new CloudClipBounds(-1.0D, 5.0D, -12.0D, 24.0D)));
        // The closed clipped 3x3 solid window greedily reduces to its six box faces.
        assertEquals(6, closed.quadCount());
    }

    @Test
    public void clippedCoordinatesRemainAnchorRelativeAtNegativeAndLargeWorldCells() {
        CloudMask solid = opaqueMask(1, 1);
        long anchorX = -83_333_333_333L;
        long anchorZ = 83_333_333_333L;
        double cell = 12.0D;
        double worldZ = anchorZ * cell;
        CloudMesh mesh = CloudMeshBuilder.build(CloudMeshCacheKey.at(anchorX, anchorZ, solid, ONE_CELL, 250.0D,
                new CloudClipBounds(252.0D, 253.0D, worldZ + 2.0D, worldZ + 10.0D)));
        assertEquals(6, mesh.quadCount());
        assertBoundsAndOutwardWinding(mesh, 0.0F, 12.0F, 2.0F, 10.0F, 2.0F, 3.0F);
    }

    @Test
    public void gradientPerlinFieldIsDeterministicTileableAndUsesNontrivialVerticalLayers() {
        CloudMask first = CloudMaskGenerator.defaultMask();
        CloudMask repeat = CloudMaskGenerator.defaultMask();
        CloudMask differentSeed = CloudMaskGenerator.generate(1L, 64, 64, 8, 1L, 3, 0.5D, 0.72D);
        assertEquals(64, first.width());
        assertEquals(64, first.depth());
        assertEquals(8, first.layers());
        assertArrayEquals(first.copyArgb(), repeat.copyArgb());
        assertFalse(java.util.Arrays.equals(first.copyArgb(), differentSeed.copyArgb()));
        assertEquals(first.cellArgb(3L, 4, -9L), first.cellArgb(67L, 4, 55L));
        int occupied = occupiedCount(first, -1);
        assertTrue(occupied > 0 && occupied < first.copyArgb().length);
        assertNotEquals(occupiedCount(first, 0), occupiedCount(first, 4));
    }

    @Test
    public void gradientPerlinSupportsNegativeCoordinatesTileBoundariesAndSmoothCellJoins() {
        double negative = GradientPerlinNoise.tileable(9L, -2.75D, 1.125D, -7.5D, 8, 16);
        assertEquals(negative, GradientPerlinNoise.tileable(9L, 5.25D, 1.125D, 8.5D, 8, 16), 0.0D);
        assertTrue(Double.compare(negative,
                GradientPerlinNoise.tileable(10L, -2.75D, 1.125D, -7.5D, 8, 16)) != 0);
        assertTrue(Double.compare(negative,
                GradientPerlinNoise.tileable(9L, -2.75D, 1.625D, -7.5D, 8, 16)) != 0);

        double h = 1.0E-3D;
        double before = GradientPerlinNoise.tileable(9L, 1.0D - h, .37D, .61D, 8, 16);
        double at = GradientPerlinNoise.tileable(9L, 1.0D, .37D, .61D, 8, 16);
        double after = GradientPerlinNoise.tileable(9L, 1.0D + h, .37D, .61D, 8, 16);
        assertEquals((at - before) / h, (after - at) / h, 2.0E-3D);
        // These two one-sided finite stencils estimate f'' at -h and +h,
        // respectively, not f'' at the same point. C2 therefore requires
        // their gap to converge to zero as h is halved; exact fixed-h equality
        // was the previous RED's invalid premise.
        double coarseGap = oneSidedSecondDerivativeGap(1.0E-3D);
        double fineGap = oneSidedSecondDerivativeGap(5.0E-4D);
        assertTrue(fineGap < coarseGap);
    }

    @Test
    public void coverageFootprintIsExactMonotonicAndLeavesLargeDefaultOpenings() {
        CloudFieldSettings low = new CloudFieldSettings(0L, .35D, false, .2D);
        CloudFieldSettings normal = CloudFieldSettings.DEFAULT;
        CloudFieldSettings high = new CloudFieldSettings(0L, .55D, false, .2D);
        boolean[] lowFootprint = CloudMaskGenerator.footprint(low);
        boolean[] normalFootprint = CloudMaskGenerator.footprint(normal);
        boolean[] highFootprint = CloudMaskGenerator.footprint(high);
        assertEquals((int) StrictMath.round(.35D * lowFootprint.length), trueCount(lowFootprint));
        assertEquals((int) StrictMath.round(.45D * normalFootprint.length), trueCount(normalFootprint));
        assertEquals((int) StrictMath.round(.55D * highFootprint.length), trueCount(highFootprint));
        for (int index = 0; index < lowFootprint.length; index++) {
            assertTrue(!lowFootprint[index] || normalFootprint[index]);
            assertTrue(!normalFootprint[index] || highFootprint[index]);
        }

        CloudMask normalMask = CloudMaskGenerator.generate(normal);
        int emptyColumns = 0;
        for (int z = 0; z < normalMask.depth(); z++) {
            for (int x = 0; x < normalMask.width(); x++) {
                boolean anyLayer = false;
                for (int layer = 0; layer < normalMask.layers(); layer++) {
                    anyLayer |= normalMask.occupiedAt(x, layer, z);
                    assertTrue(!normalMask.occupiedAt(x, layer, z) || normalFootprint[z * normalMask.width() + x]);
                }
                assertEquals(normalFootprint[z * normalMask.width() + x], anyLayer);
                if (!anyLayer) {
                    emptyColumns++;
                }
            }
        }
        assertTrue(emptyColumns >= normalMask.width() * normalMask.depth() * .45D);
    }

    @Test
    public void defaultFieldUsesThreeDimensionalNoiseToVaryVisibleColumnThickness() {
        CloudMask mask = CloudMaskGenerator.defaultMask();
        boolean[] footprint = CloudMaskGenerator.footprint(CloudFieldSettings.DEFAULT);
        java.util.Set<Integer> thicknesses = new java.util.HashSet<>();
        java.util.Set<Integer> bottoms = new java.util.HashSet<>();
        java.util.Set<Integer> tops = new java.util.HashSet<>();
        for (int z = 0; z < mask.depth(); z++) {
            for (int x = 0; x < mask.width(); x++) {
                if (!footprint[z * mask.width() + x]) {
                    continue;
                }
                int count = 0;
                int bottom = mask.layers();
                int top = -1;
                for (int layer = 0; layer < mask.layers(); layer++) {
                    if (mask.occupiedAt(x, layer, z)) {
                        count++;
                        bottom = Math.min(bottom, layer);
                        top = Math.max(top, layer);
                    }
                }
                // The middle bell core is guaranteed for every selected
                // footprint column; changes here are genuine 3D geometry.
                assertTrue(count > 0);
                thicknesses.add(count);
                bottoms.add(bottom);
                tops.add(top);
            }
        }
        assertTrue(thicknesses.size() >= 3);
        assertTrue(bottoms.size() >= 2);
        assertTrue(tops.size() >= 2);
    }

    @Test
    public void coverageExtremesAndWorleyToggleHaveExplicitFieldBehavior() {
        CloudMask empty = CloudMaskGenerator.generate(new CloudFieldSettings(0L, 0.0D, false, .2D));
        assertEquals(0, occupiedCount(empty, -1));

        boolean[] fullFootprint = CloudMaskGenerator.footprint(new CloudFieldSettings(0L, 1.0D, false, .2D));
        assertEquals(fullFootprint.length, trueCount(fullFootprint));
        CloudMask fullCoverage = CloudMaskGenerator.generate(new CloudFieldSettings(0L, 1.0D, false, .2D));
        assertTrue(occupiedCount(fullCoverage, -1) > 0);
        assertTrue(occupiedCount(fullCoverage, -1) < fullCoverage.copyArgb().length);

        CloudMask smooth = CloudMaskGenerator.generate(new CloudFieldSettings(0L, .45D, false, 1.0D));
        CloudMask eroded = CloudMaskGenerator.generate(new CloudFieldSettings(0L, .45D, true, 1.0D));
        assertFalse(java.util.Arrays.equals(smooth.copyArgb(), eroded.copyArgb()));
    }

    @Test
    public void legacyOctavesAndPersistenceAffectTheGeneratedArgbField() {
        CloudMask oneOctave = CloudMaskGenerator.generate(21L, 64, 64, 8, 77L, 1, .50D, .55D);
        CloudMask fourOctaves = CloudMaskGenerator.generate(21L, 64, 64, 8, 77L, 4, .50D, .55D);
        CloudMask lowPersistence = CloudMaskGenerator.generate(21L, 64, 64, 8, 77L, 3, .10D, .55D);
        CloudMask highPersistence = CloudMaskGenerator.generate(21L, 64, 64, 8, 77L, 3, .90D, .55D);
        assertFalse(java.util.Arrays.equals(oneOctave.copyArgb(), fourOctaves.copyArgb()));
        assertFalse(java.util.Arrays.equals(lowPersistence.copyArgb(), highPersistence.copyArgb()));
        assertThrows(IllegalArgumentException.class,
                () -> CloudMaskGenerator.generate(21L, 64, 64, 8, 77L, 5, .50D, .55D));
    }

    @Test
    public void worleyNearestFeatureIncludesTheRequiredSecondRingOfCells() {
        long seed = -1_703_566_749L;
        double x = 4.923496626317501D;
        double y = 6.99900870397687D;
        double z = -3.2599253356456757D;
        double expectedSquaredDistance = 1.281450005847182D;
        double expected = StrictMath.sqrt(expectedSquaredDistance) / StrictMath.sqrt(3.0D);
        double nearest = WorleyNoise.nearestFeature(seed, x, y, z, 4, 4);
        assertEquals(expected, nearest, 1.0E-14D);
        // The radius-four oracle is independently wider than the production
        // proof bound and guards against future stencil shrinkage.
        assertEquals(WorleyNoise.nearestFeatureWideReference(seed, x, y, z, 4, 4), nearest, 0.0D);
    }

    @Test
    public void stackedVoxelsCullTheirInternalHorizontalFaceAndClippedCapsRemainClosed() {
        CloudMask twoLayers = new CloudMask(11L, 1, 1, 2, new int[]{0xFFFFFFFF, 0xFFFFFFFF});
        CloudMesh fullStack = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, twoLayers, ONE_CELL, 0.0D,
                new CloudClipBounds(0.0D, 8.0D, 0.0D, 12.0D)));
        // Four coplanar side strips merge across the two layers, while the
        // shared horizontal face remains absent.
        assertEquals(6, fullStack.quadCount());
        assertBoundsAndOutwardWinding(fullStack, 0.0F, 12.0F, 0.0F, 12.0F, 0.0F, 8.0F);
        assertEquals(672.0D, surfaceArea(fullStack), 1.0E-6D);
        assertNoHorizontalFaceAt(fullStack, 4.0F);

        CloudMesh clippedStack = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, twoLayers, ONE_CELL, 0.0D,
                new CloudClipBounds(2.0D, 6.0D, 3.0D, 9.0D)));
        // Greedy side faces may introduce legal T-junctions, so directed-edge
        // pairing is no longer a topology oracle. Area, winding, bounds and
        // the missing shared cap remain the mesh contract.
        assertEquals(6, clippedStack.quadCount());
        assertBoundsAndOutwardWinding(clippedStack, 0.0F, 12.0F, 3.0F, 9.0F, 2.0F, 6.0F);
        assertEquals(288.0D, surfaceArea(clippedStack), 1.0E-6D);
        assertNoHorizontalFaceAt(clippedStack, 4.0F);
    }

    @Test
    public void canonicalFractionalGridPlanesKeepAdjacentVoxelBoundariesExact() {
        CloudGeometrySettings fractional = new CloudGeometrySettings(1_000_000.1D, 4.0D, 1);
        CloudMask repeating = new CloudMask(12L, 1, 1, new int[]{0xFFFFFFFF});
        CloudMesh mesh = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, repeating, fractional, 0.0D,
                new CloudClipBounds(-1.0D, 5.0D, -2_000_001.0D, 2_000_001.0D)));
        assertClosedOrientedEdges(mesh);
    }

    @Test
    public void firstReleaseRejectsMoreThanEightVerticalLayers() {
        assertThrows(IllegalArgumentException.class,
                () -> new CloudMask(13L, 1, 1, CloudMask.MAX_LAYERS + 1, new int[CloudMask.MAX_LAYERS + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> CloudMaskGenerator.generate(1L, 64, 64, CloudMask.MAX_LAYERS + 1,
                        0L, 3, 0.5D, 0.72D));
    }

    @Test
    public void diagonalVoxelContactsRemainSourceGeometryWithoutTwoManifoldRewriting() {
        CloudMask diagonal = new CloudMask(14L, 2, 2,
                new int[]{0xFFFFFFFF, 0, 0, 0xFFFFFFFF});
        CloudMesh mesh = CloudMeshBuilder.build(CloudMeshCacheKey.at(0L, 0L, diagonal,
                new CloudGeometrySettings(12.0D, 4.0D, 1), 0.0D,
                new CloudClipBounds(-1.0D, 5.0D, -24.0D, 24.0D)));
        // Five source voxels in the 3x3 window touch only at corners. They
        // remain five closed visible voxel boundaries, not altered CSG input.
        assertEquals(30, mesh.quadCount());
        assertClosedOrientedEdges(mesh);
    }

    private static CloudMask opaqueMask(int width, int height) {
        int[] pixels = new int[width * height];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = 0xFF336699;
        }
        return new CloudMask(3L, width, height, pixels);
    }

    private static CloudMeshCacheKey key(long anchorX, long anchorZ, CloudMask mask,
                                         CloudGeometrySettings geometry) {
        return CloudMeshCacheKey.at(anchorX, anchorZ, mask, geometry, 0.0D, OPEN_CLIP);
    }

    private static int occupiedCount(CloudMask mask, int layer) {
        int count = 0;
        int start = layer < 0 ? 0 : layer;
        int end = layer < 0 ? mask.layers() : layer + 1;
        for (int y = start; y < end; y++) {
            for (int z = 0; z < mask.depth(); z++) {
                for (int x = 0; x < mask.width(); x++) {
                    if (mask.occupiedAt(x, y, z)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int trueCount(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static double oneSidedSecondDerivativeGap(double h) {
        double beforeTwo = GradientPerlinNoise.tileable(9L, 1.0D - 2.0D * h, .37D, .61D, 8, 16);
        double before = GradientPerlinNoise.tileable(9L, 1.0D - h, .37D, .61D, 8, 16);
        double at = GradientPerlinNoise.tileable(9L, 1.0D, .37D, .61D, 8, 16);
        double after = GradientPerlinNoise.tileable(9L, 1.0D + h, .37D, .61D, 8, 16);
        double afterTwo = GradientPerlinNoise.tileable(9L, 1.0D + 2.0D * h, .37D, .61D, 8, 16);
        double left = (at - 2.0D * before + beforeTwo) / (h * h);
        double right = (afterTwo - 2.0D * after + at) / (h * h);
        return StrictMath.abs(left - right);
    }

    private static void assertBoundsAndOutwardWinding(CloudMesh mesh, float minX, float maxX,
                                                      float minZ, float maxZ, float minY, float maxY) {
        float[] vertices = mesh.copyVertices();
        for (int index = 0; index < vertices.length; index += CloudMesh.FLOATS_PER_VERTEX) {
            assertTrue(vertices[index] >= minX && vertices[index] <= maxX);
            assertTrue(vertices[index + 1] >= minY && vertices[index + 1] <= maxY);
            assertTrue(vertices[index + 2] >= minZ && vertices[index + 2] <= maxZ);
        }
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            float[] normal = cross(vertices, start, start + CloudMesh.FLOATS_PER_VERTEX,
                    start + 2 * CloudMesh.FLOATS_PER_VERTEX);
            float centerX = (vertices[start] + vertices[start + CloudMesh.FLOATS_PER_VERTEX]
                    + vertices[start + 2 * CloudMesh.FLOATS_PER_VERTEX] + vertices[start + 3 * CloudMesh.FLOATS_PER_VERTEX]) / 4.0F;
            float centerY = (vertices[start + 1] + vertices[start + CloudMesh.FLOATS_PER_VERTEX + 1]
                    + vertices[start + 2 * CloudMesh.FLOATS_PER_VERTEX + 1] + vertices[start + 3 * CloudMesh.FLOATS_PER_VERTEX + 1]) / 4.0F;
            float centerZ = (vertices[start + 2] + vertices[start + CloudMesh.FLOATS_PER_VERTEX + 2]
                    + vertices[start + 2 * CloudMesh.FLOATS_PER_VERTEX + 2] + vertices[start + 3 * CloudMesh.FLOATS_PER_VERTEX + 2]) / 4.0F;
            float outwardX = centerX == minX ? -1.0F : centerX == maxX ? 1.0F : 0.0F;
            float outwardY = centerY == minY ? -1.0F : centerY == maxY ? 1.0F : 0.0F;
            float outwardZ = centerZ == minZ ? -1.0F : centerZ == maxZ ? 1.0F : 0.0F;
            assertTrue(normal[0] * outwardX + normal[1] * outwardY + normal[2] * outwardZ > 0.0F);
        }
    }

    /** Checks algebraic directed-edge balance, not a unique-pair manifold requirement. */
    private static void assertClosedOrientedEdges(CloudMesh mesh) {
        float[] vertices = mesh.copyVertices();
        Map<DirectedEdge, Integer> balance = new HashMap<>();
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            for (int corner = 0; corner < 4; corner++) {
                int next = (corner + 1) % 4;
                VertexPoint from = point(vertices, start + corner * CloudMesh.FLOATS_PER_VERTEX);
                VertexPoint to = point(vertices, start + next * CloudMesh.FLOATS_PER_VERTEX);
                DirectedEdge edge = new DirectedEdge(from, to);
                DirectedEdge reverse = new DirectedEdge(to, from);
                balance.merge(edge, 1, Integer::sum);
                balance.merge(reverse, -1, Integer::sum);
            }
        }
        assertTrue(balance.values().stream().allMatch(value -> value == 0));
    }

    private static void assertNoHorizontalFaceAt(CloudMesh mesh, float y) {
        float[] vertices = mesh.copyVertices();
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            float[] normal = cross(vertices, start, start + CloudMesh.FLOATS_PER_VERTEX,
                    start + 2 * CloudMesh.FLOATS_PER_VERTEX);
            boolean horizontal = normal[1] != 0.0F;
            boolean atSharedLayerBoundary = true;
            for (int corner = 0; corner < 4; corner++) {
                atSharedLayerBoundary &= vertices[start + corner * CloudMesh.FLOATS_PER_VERTEX + 1] == y;
            }
            assertFalse(horizontal && atSharedLayerBoundary);
        }
    }

    private static double surfaceArea(CloudMesh mesh) {
        float[] vertices = mesh.copyVertices();
        double area = 0.0D;
        for (int start = 0; start < vertices.length; start += 4 * CloudMesh.FLOATS_PER_VERTEX) {
            area += triangleArea(vertices, start, start + CloudMesh.FLOATS_PER_VERTEX,
                    start + 2 * CloudMesh.FLOATS_PER_VERTEX);
            area += triangleArea(vertices, start, start + 2 * CloudMesh.FLOATS_PER_VERTEX,
                    start + 3 * CloudMesh.FLOATS_PER_VERTEX);
        }
        return area;
    }

    private static double triangleArea(float[] vertices, int first, int second, int third) {
        float[] normal = cross(vertices, first, second, third);
        return 0.5D * StrictMath.sqrt(normal[0] * normal[0] + normal[1] * normal[1]
                + normal[2] * normal[2]);
    }

    private static VertexPoint point(float[] vertices, int index) {
        return new VertexPoint(vertices[index], vertices[index + 1], vertices[index + 2]);
    }

    private static float[] cross(float[] vertices, int first, int second, int third) {
        float ax = vertices[second] - vertices[first];
        float ay = vertices[second + 1] - vertices[first + 1];
        float az = vertices[second + 2] - vertices[first + 2];
        float bx = vertices[third] - vertices[first];
        float by = vertices[third + 1] - vertices[first + 1];
        float bz = vertices[third + 2] - vertices[first + 2];
        return new float[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private record VertexPoint(float x, float y, float z) {
    }

    private record DirectedEdge(VertexPoint from, VertexPoint to) {
    }
}
