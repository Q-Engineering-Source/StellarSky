package stellarium.client.ring.cloud;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression coverage for vertical volume shape in the absolute cloud field. */
public class CloudVolumeShapeTest {
    private static final int WIDTH = 64;
    private static final int LAYERS = 8;
    private static final int MIDDLE_LAYER = LAYERS / 2;
    private static final double FINE_CELL_SIZE = 12.0D;
    private static final CloudFieldSettings FLAT_SYMPTOM_SETTINGS = new CloudFieldSettings(0L, .6D, true, .6D);

    @Test
    public void fixedFineRegionDoesNotKeepOneTopHeightAcrossNearlyEveryCoveredColumn() {
        VolumeShape shape = sample(FLAT_SYMPTOM_SETTINGS, 0L, 0L);

        assertTrue("the fixed .6 coverage fixture must contain covered columns", shape.coveredColumns > 0);
        assertTrue("flat-cloud regression: one top height dominates the fixed covered region: " + shape,
                shape.topModeFraction() < .90D);
    }

    @Test
    public void fixedFineRegionDoesNotKeepItsMiddleCoreSolidForEveryCoveredColumn() {
        VolumeShape shape = sample(FLAT_SYMPTOM_SETTINGS, 0L, 0L);

        assertTrue("the fixed .6 coverage fixture must contain covered columns", shape.coveredColumns > 0);
        assertTrue("flat-cloud regression: every covered column still fills the middle core: " + shape,
                shape.middleOccupiedColumns < shape.coveredColumns);
    }

    @Test
    public void verticalVariationIsNotOnlyAnAccidentOfOneWorldCoordinateOrSeed() {
        VolumeShape[] regions = {
                sample(FLAT_SYMPTOM_SETTINGS, 0L, 0L),
                sample(FLAT_SYMPTOM_SETTINGS, 1_024L, -2_048L),
                sample(new CloudFieldSettings(0x5EED_F00DL, .6D, true, .6D), -4_096L, 3_072L)
        };

        int shapedRegions = 0;
        for (VolumeShape region : regions) {
            if (region.coveredColumns > 0 && region.topModeFraction() < .90D
                    && region.middleOccupiedColumns < region.coveredColumns) {
                shapedRegions++;
            }
        }
        assertTrue("at least two deterministic regions must avoid the flat top/core pattern; shaped="
                + shapedRegions, shapedRegions >= 2);
    }

    private static VolumeShape sample(CloudFieldSettings settings, long originX, long originZ) {
        int[] topHeightCounts = new int[LAYERS + 1];
        int coveredColumns = 0;
        int middleOccupiedColumns = 0;
        for (int localZ = 0; localZ < WIDTH; localZ++) {
            long cellZ = originZ + localZ;
            for (int localX = 0; localX < WIDTH; localX++) {
                long cellX = originX + localX;
                int topHeight = 0;
                for (int layer = 0; layer < LAYERS; layer++) {
                    if (CloudWorldField.occupied(settings, cellX, layer, cellZ, LAYERS, FINE_CELL_SIZE)) {
                        topHeight = layer + 1;
                    }
                }
                if (topHeight == 0) {
                    continue;
                }
                coveredColumns++;
                topHeightCounts[topHeight]++;
                if (CloudWorldField.occupied(settings, cellX, MIDDLE_LAYER, cellZ, LAYERS, FINE_CELL_SIZE)) {
                    middleOccupiedColumns++;
                }
            }
        }
        int topModeCount = 0;
        for (int count : topHeightCounts) {
            topModeCount = Math.max(topModeCount, count);
        }
        return new VolumeShape(coveredColumns, middleOccupiedColumns, topModeCount);
    }

    private record VolumeShape(int coveredColumns, int middleOccupiedColumns, int topModeCount) {
        private double topModeFraction() {
            return topModeCount / (double) coveredColumns;
        }
    }
}
