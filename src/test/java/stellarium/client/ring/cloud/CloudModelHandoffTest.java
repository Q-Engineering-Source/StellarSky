package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** Pure far-cloud/model handoff policy; GLSL receives these Java-owned band values as uniforms. */
public class CloudModelHandoffTest {
    @Test
    public void handoffBandIsStrictlyInsideTheAuthoritativeTailPageAtRelevantCellSizes() {
        for (double cellSize : new double[] {4.0D, 12.0D, 64.0D}) {
            double start = CloudModelHandoff.farStart(cellSize);
            double end = CloudModelHandoff.farEnd(cellSize);
            double tailHalfWidth = CloudLodLayout.LOD12_2D_TAIL.width() * cellSize
                    * CloudLodLayout.LOD12_2D_TAIL.xzScale() * 0.5D;

            assertTrue("handoff start must be within LOD12 tail for cell size " + cellSize,
                    start > 0.0D && start < tailHalfWidth);
            assertTrue("handoff end must remain within LOD12 tail for cell size " + cellSize,
                    end > start && end < tailHalfWidth);
            assertEquals("the policy begins with no far-field ownership", 0.0D,
                    CloudModelHandoff.farWeight(start, cellSize), 0.0D);
            assertEquals("the policy ends with full far-field ownership", 1.0D,
                    CloudModelHandoff.farWeight(end, cellSize), 0.0D);
        }
    }

    @Test
    public void sixteenBayerRanksAreUniqueAndPartitionNearAndFarCloudOwnership() {
        Set<Double> ranks = new HashSet<>();
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            double rank = CloudModelHandoff.rank(x, y);
            assertTrue("rank must be inside its half-open Bayer range", rank > 0.0D && rank < 1.0D);
            assertTrue("every 4x4 cell must own a unique rank", ranks.add(rank));
        }
        assertEquals(16, ranks.size());

        double cellSize = 12.0D;
        double start = CloudModelHandoff.farStart(cellSize);
        double end = CloudModelHandoff.farEnd(cellSize);
        for (double distance : new double[] {start, (start + end) * 0.5D, end}) {
            double farWeight = CloudModelHandoff.farWeight(distance, cellSize);
            int nearRetained = 0;
            int farRetained = 0;
            for (double rank : ranks) {
                // cloud_lod.frag discards rank < farWeight; model.frag's far layer
                // discards rank >= farWeight. These predicates are complements.
                boolean nearCloud = rank >= farWeight;
                boolean farCloud = rank < farWeight;
                assertTrue("one and only one layer must retain each Bayer rank", nearCloud ^ farCloud);
                if (nearCloud) nearRetained++;
                if (farCloud) farRetained++;
            }
            assertEquals("the complementary rank sets cover every pixel", 16, nearRetained + farRetained);
            if (distance == start) assertEquals(16, nearRetained);
            if (distance == end) assertEquals(16, farRetained);
        }
    }

    @Test
    public void handoffWeightClampsBeforeAndAfterItsFiniteBand() {
        double cellSize = 12.0D;
        double start = CloudModelHandoff.farStart(cellSize);
        double end = CloudModelHandoff.farEnd(cellSize);
        assertEquals(0.0D, CloudModelHandoff.farWeight(start - 1.0D, cellSize), 0.0D);
        assertEquals(1.0D, CloudModelHandoff.farWeight(end + 1.0D, cellSize), 0.0D);
        assertTrue(CloudModelHandoff.farWeight((start + end) * 0.5D, cellSize) > 0.0D);
        assertTrue(CloudModelHandoff.farWeight((start + end) * 0.5D, cellSize) < 1.0D);
    }
}
