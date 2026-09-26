package stellarium.client.ring.dh;

import static org.junit.Assert.*;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.awt.Color;
import java.io.IOException;
import java.sql.SQLException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Actual DH objects/codec/repository; no Minecraft chunk generation or GL context. */
public class DistantHorizonsDataEvidenceTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private static final long POS = DhSectionPos.encode((byte) 6, -1, 2);

    @Test public void missingColumnAndRecordedAirHaveDifferentPerColumnEvidence() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            int index = FullDataSourceV2.relativePosToIndex(0, 0);
            assertTrue(source.getColumnAtRelPos(0, 0).isEmpty());
            assertEquals(0, source.columnGenerationSteps.getByte(index));
            putColumn(source, 0, true);
            assertEquals(EDhApiWorldGenerationStep.LIGHT.value, source.columnGenerationSteps.getByte(index));
            long point = source.getColumnAtRelPos(0, 0).getLong(0);
            assertTrue(source.mapping.getBlockStateWrapper(FullDataPointUtil.getId(point)).isAir());
            assertEquals(256, FullDataPointUtil.getHeight(point));
            // A populated column does not establish its neighbours' coverage.
            assertTrue(source.getColumnAtRelPos(1, 0).isEmpty());
            assertEquals(0, source.columnGenerationSteps.getByte(FullDataSourceV2.relativePosToIndex(1, 0)));
        }
    }

    @Test public void airAndSolidColumnsRemainDistinctEvenWhenTheSummaryFlagIsStale() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            putColumn(source, 0, true);
            putColumn(source, 1, false);
            // DH's public per-column setter does not update the region-wide isEmpty field.
            assertTrue(source.isEmpty);
            assertTrue(source.mapping.getBlockStateWrapper(FullDataPointUtil.getId(
                    source.getColumnAtRelPos(0, 0).getLong(0))).isAir());
            assertFalse(source.mapping.getBlockStateWrapper(FullDataPointUtil.getId(
                    source.getColumnAtRelPos(1, 0).getLong(0))).isAir());
        }
    }

    @Test public void repositoryReadDistinguishesMissingRowAndDoesNotRewriteStoredFormat() throws Exception {
        try (var repo = new FullDataSourceV2Repo(AbstractDhRepo.DEFAULT_DATABASE_TYPE,
                directory.getRoot().toPath().resolve("fixture.sqlite").toFile());
             var source = FullDataSourceV2.createEmpty(POS)) {
            assertNull(readRow(repo, POS));
            putColumn(source, 0, true);
            source.isEmpty = false;
            try (var dto = FullDataSourceV2DTO.CreateFromDataSource(source, EDhApiDataCompressionMode.UNCOMPRESSED)) {
                // A sentinel old-format tag tests storage reads, not legacy blob decoding.
                dto.dataFormatVersion = 1;
                repo.save(dto);
            }
            long storedTime;
            try (var dto = readRow(repo, POS)) {
                // DH assigns the timestamp during save rather than preserving the input DTO's value.
                storedTime = dto.lastModifiedUnixDateTime;
            }
            long writesBeforeRead = totalChanges(repo);
            for (int i = 0; i < 2; i++) {
                try (var dto = readRow(repo, POS)) {
                    assertNotNull(dto);
                    assertEquals(1, dto.dataFormatVersion);
                    assertEquals(storedTime, dto.lastModifiedUnixDateTime);
                }
            }
            assertNull(readRow(repo, DhSectionPos.encode((byte)6, 9, 9)));
            assertEquals(writesBeforeRead, totalChanges(repo));
        }
    }

    @Test public void directQueryPreservesDatabaseFailureInsteadOfReturningMissing() throws Exception {
        try (var repo = new FullDataSourceV2Repo(AbstractDhRepo.DEFAULT_DATABASE_TYPE,
                directory.getRoot().toPath().resolve("failure-fixture.sqlite").toFile())) {
            // Damage only this disposable fixture, never a live DH database.
            try (var statement = repo.getConnection().createStatement()) {
                statement.executeUpdate("DROP TABLE " + repo.getTableName());
            }
            var failure = assertThrows(RuntimeException.class, () -> readRow(repo, POS));
            assertTrue(failure.getCause() instanceof SQLException);
        }
    }

    private static FullDataSourceV2DTO readRow(FullDataSourceV2Repo repo, long pos)
            throws SQLException, IOException {
        // getByKey catches some SQL/DTO errors and returns null; preserve those errors here.
        try (var statement = repo.createSelectStatementByKey(pos)) {
            if (statement == null) {
                throw new SQLException("DH repository unavailable; this is not a missing row");
            }
            try (var result = statement.executeQuery()) {
                return result.next() ? repo.convertResultSetToDto(result) : null;
            }
        }
    }

    @Test public void codecPreservesMissingAndPopulatedColumnCoverage() throws Exception {
        try (var source = FullDataSourceV2.createEmpty(POS)) {
            putColumn(source, 0, true);
            putColumn(source, 1, false);
            source.isEmpty = false;
            try (var dto = FullDataSourceV2DTO.CreateFromDataSource(source, EDhApiDataCompressionMode.UNCOMPRESSED);
                 var decoded = dto.createUnitTestDataSource()) {
                // DH's test decode skips Minecraft wrapper reconstruction, but uses the real column codec.
                for (int x = 0; x < 3; x++) {
                    int index = FullDataSourceV2.relativePosToIndex(x, 0);
                    assertEquals(source.getColumnAtRelPos(x, 0), decoded.getColumnAtRelPos(x, 0));
                    assertEquals(source.columnGenerationSteps.getByte(index), decoded.columnGenerationSteps.getByte(index));
                }
            }
        }
    }

    private static long totalChanges(FullDataSourceV2Repo repo) throws Exception {
        // Borrow the same thread's connection; closing it would close DH-owned state.
        try (var statement = repo.getConnection().createStatement();
             var result = statement.executeQuery("SELECT total_changes()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void putColumn(FullDataSourceV2 source, int x, boolean air) throws Exception {
        int id = source.mapping.addIfNotPresentAndGetId(new TestBiome(), new TestBlock(air));
        source.setSingleColumn(LongArrayList.of(FullDataPointUtil.encode(id,256,0,(byte)15,(byte)0)),
                x,0,EDhApiWorldGenerationStep.LIGHT,EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
    }

    record TestBiome() implements IBiomeWrapper {
        public String getName() { return "fixture"; }
        public String getSerialString() { return "fixture:biome"; }
        public boolean isColdBiome() { return false; }
        public Object getWrappedMcObject() { throw new UnsupportedOperationException("No Minecraft world"); }
    }

    record TestBlock(boolean isAir) implements IBlockStateWrapper {
        public String getSerialString() { return isAir ? "fixture:air" : "fixture:stone"; }
        public boolean isSolid() { return !isAir; }
        public boolean isLiquid() { return false; }
        public int getOpacity() { return isAir ? 0 : 15; }
        public int getLightEmission() { return 0; }
        public byte getMaterialId() { return 0; }
        public boolean isBeaconBlock() { return false; }
        public boolean isBeaconTintBlock() { return false; }
        public boolean allowsBeaconBeamPassage() { return false; }
        public boolean isBeaconBaseBlock() { return false; }
        public boolean isIceBlock() { return false; }
        public boolean renderTexture() { return false; }
        public boolean useBottomTextureForSides() { return false; }
        public boolean alwaysRasterizeTexture() { return false; }
        public boolean allowApiColorOverride() { return false; }
        public boolean allowApiTextureOverride() { return false; }
        public Color getMapColor() { return Color.GRAY; }
        public Color getBeaconTintColor() { return Color.WHITE; }
        public Object getWrappedMcObject() { throw new UnsupportedOperationException("No Minecraft world"); }
    }
}
