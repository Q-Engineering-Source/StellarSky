package stellarium.client.ring.dh;

import static org.junit.Assert.*;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Test;
import stellarium.world.ring.terrain.TerrainColumnState;
import stellarium.world.ring.terrain.TerrainTileCache;
import stellarium.world.ring.terrain.TerrainTileKey;

public class DistantHorizonsColumnCoverageTest {
    private static final long POS = DhSectionPos.encode((byte) 6, -1, 2);
    private static long point(FullDataSourceV2 source, boolean air, int height, int bottom) throws Exception {
        int id = source.mapping.addIfNotPresentAndGetId(new DistantHorizonsDataEvidenceTest.TestBiome(),
                new DistantHorizonsDataEvidenceTest.TestBlock(air));
        return FullDataPointUtil.encode(id, height, bottom, (byte) 15, (byte) 0);
    }
    private static void column(FullDataSourceV2 source, int x, int z, long... points) {
        source.setSingleColumn(LongArrayList.of(points), x, z, EDhApiWorldGenerationStep.LIGHT,
                EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
    }

    @Test public void capturesRealAirAndSolidWithoutClaimingMissingNeighbours() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            column(source, 2, 3, point(source, true, 256, 0));
            column(source, 3, 2, point(source, true, 192, 64), point(source, false, 64, 0));
            assertTrue(source.isEmpty); // Deliberately stale DH summary must not erase column evidence.
            var copy = DistantHorizonsColumnCoverage.capture(source, 0, 256);
            assertTrue(copy.sourceSummaryEmpty());
            assertEquals(TerrainColumnState.REAL_AIR, copy.column(2, 3));
            assertEquals(TerrainColumnState.REAL_SOLID, copy.column(3, 2));
            assertEquals(TerrainColumnState.UNKNOWN, copy.column(3, 3));
            assertEquals(POS, copy.sectionPos());
        }
    }

    @Test public void gapsAndUnfinishedColumnsStayUnknown() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            column(source, 0, 0, point(source, true, 192, 64));
            column(source, 1, 0, point(source, true, 191, 65), point(source, false, 64, 0));
            source.setSingleColumn(LongArrayList.of(point(source, false, 256, 0)), 2, 0,
                    EDhApiWorldGenerationStep.SURFACE, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
            var copy = DistantHorizonsColumnCoverage.capture(source, 0, 256);
            for (int x = 0; x < 3; x++) assertEquals(TerrainColumnState.UNKNOWN, copy.column(x, 0));
        }
    }

    @Test public void relativeHeightsAndNativeTopSentinelAreClippedToActualLevelRange() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            // DH's chunk builder can include air at exclusive max height; it is not a world block.
            column(source, 0, 0, point(source, true, 385, 0));
            var copy = DistantHorizonsColumnCoverage.capture(source, -64, 320);
            assertEquals(TerrainColumnState.REAL_AIR, copy.column(0, 0));
            assertEquals(-64, copy.minHeight());
            assertEquals(320, copy.maxHeightExclusive());
        }
    }

    @Test public void snapshotSurvivesSourceMutationAndPoolClose() throws Exception {
        DistantHorizonsColumnCoverage copy;
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            column(source, 0, 0, point(source, true, 256, 0));
            copy = DistantHorizonsColumnCoverage.capture(source, 0, 256);
            column(source, 0, 0, point(source, false, 256, 0));
        }
        assertEquals(TerrainColumnState.REAL_AIR, copy.column(0, 0));
        assertThrows(UnsupportedOperationException.class, () -> copy.columns().set(0, TerrainColumnState.UNKNOWN));
    }

    @Test public void overlappingRunsAreAnErrorNotKnownAir() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            column(source, 0, 0, point(source, true, 200, 56), point(source, true, 64, 0));
            assertThrows(IllegalArgumentException.class, () -> DistantHorizonsColumnCoverage.capture(source, 0, 256));
        }
    }

    @Test public void nativeParentRequiresAllFourGeneratedChildren() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, 0, 0));
             var parent = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 7, 0, 0))) {
            for (int x=0;x<2;x++) for(int z=0;z<2;z++) {
                if(x==1 && z==1)continue;
                column(source,x,z,point(source,false,256,0));
            }
            source.isEmpty=false;
            parent.updateFromDataSource(source);
            assertEquals(TerrainColumnState.UNKNOWN,DistantHorizonsColumnCoverage.capture(parent,0,256).column(0,0));
            column(source,1,1,point(source,false,256,0));
            parent.updateFromDataSource(source);
            assertEquals(TerrainColumnState.REAL_SOLID,DistantHorizonsColumnCoverage.capture(parent,0,256).column(0,0));
            assertEquals(TerrainColumnState.UNKNOWN,DistantHorizonsColumnCoverage.capture(parent,0,256).column(0,1));
        }
    }

    @Test public void replicatedCoarseDataDoesNotPretendToBeRealFineCoverage() throws Exception {
        try (var parent = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte)7,0,0));
             var fine = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte)6,0,0))) {
            column(parent,0,0,point(parent,false,256,0));
            parent.isEmpty=false;
            fine.updateFromDataSource(parent);
            assertEquals(EDhApiWorldGenerationStep.DOWN_SAMPLED.value,fine.columnGenerationSteps.getByte(0));
            assertEquals(TerrainColumnState.UNKNOWN,DistantHorizonsColumnCoverage.capture(fine,0,256).column(0,0));
        }
    }

    @Test public void ownedReadPublishesAfterSourceCloseAndRejectsOtherWorldOrTile() throws Exception {
        var key = new TerrainTileKey(1, 0, -1, 2);
        var cache = new TerrainTileCache(1, 2);
        var ticket = cache.begin(key).orElseThrow();
        DistantHorizonsTerrainDataBridge.CapturedRead read;
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            column(source, 0, 0, point(source, true, 256, 0));
            assertThrows(IllegalArgumentException.class, () -> DistantHorizonsTerrainDataBridge.capture(ticket, 2, source, 0, 256));
            var wrong = cache.begin(new TerrainTileKey(1, 0, 0, 0)).orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> DistantHorizonsTerrainDataBridge.capture(wrong, 1, source, 0, 256));
            read = DistantHorizonsTerrainDataBridge.capture(ticket, 1, source, 0, 256);
        }
        assertTrue(read.publish(cache));
        assertEquals(TerrainColumnState.REAL_AIR, cache.get(key).orElseThrow().column(0));
        assertFalse(read.publish(cache));
    }

    @Test public void lateCapturedDhDataCannotReplaceNewerEvidence() throws Exception {
        var key = new TerrainTileKey(1, 0, -1, 2);
        var cache = new TerrainTileCache(1, 1);
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            column(source, 0, 0, point(source, false, 256, 0));
            var old = DistantHorizonsTerrainDataBridge.capture(cache.begin(key).orElseThrow(), 1, source, 0, 256);
            column(source, 0, 0, point(source, true, 256, 0));
            var latest = DistantHorizonsTerrainDataBridge.capture(cache.begin(key).orElseThrow(), 1, source, 0, 256);
            assertTrue(latest.publish(cache));
            assertFalse(old.publish(cache));
            assertEquals(TerrainColumnState.REAL_AIR, cache.get(key).orElseThrow().column(0));
        }
    }
}
