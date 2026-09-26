package stellarium.client.ring.dh;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import stellarium.world.ring.terrain.TerrainColumnState;
import stellarium.world.ring.terrain.TerrainTileKey;

/** Owned copy of the exact input data. This proves stored coverage, never successful rendering. */
public record DistantHorizonsColumnCoverage(long sectionPos, int minHeight, int maxHeightExclusive,
                                          boolean sourceSummaryEmpty, List<TerrainColumnState> columns) {
    public DistantHorizonsColumnCoverage {
        if (minHeight >= maxHeightExclusive || (long) maxHeightExclusive - minHeight > 4096) {
            throw new IllegalArgumentException("Unsupported DH vertical range");
        }
        columns = List.copyOf(columns);
        if (columns.size() != TerrainTileKey.COLUMN_COUNT) throw new IllegalArgumentException("Expected 4096 columns");
    }

    /** Call while the producer owns this source, before DH closes/recycles it. No disk or chunk access. */
    public static DistantHorizonsColumnCoverage capture(FullDataSourceV2 source, int minHeight, int maxHeightExclusive) {
        Objects.requireNonNull(source, "source");
        long span = (long) maxHeightExclusive - minHeight;
        if (span <= 0 || span > 4096) throw new IllegalArgumentException("Unsupported DH vertical range");
        if (source.getWidthInDataColumns() != TerrainTileKey.WIDTH) throw new IllegalArgumentException("Unsupported DH column width");
        int height = (int) span;
        var states = new TerrainColumnState[TerrainTileKey.COLUMN_COUNT];
        Arrays.fill(states, TerrainColumnState.UNKNOWN);
        // DH 3.3 propagates the MIN generation step of all four children to each parent column.
        // LIGHT therefore admits completed coarse coverage too. Coarse-to-fine replication is
        // explicitly DOWN_SAMPLED and stays UNKNOWN. Still require a complete vertical column.
        {
            var covered = new BitSet(height);
            for (int x = 0; x < TerrainTileKey.WIDTH; x++) for (int z = 0; z < TerrainTileKey.WIDTH; z++) {
                int index = FullDataSourceV2.relativePosToIndex(x, z);
                if (source.columnGenerationSteps.getByte(index) != EDhApiWorldGenerationStep.LIGHT.value) continue;
                var data = source.getColumnAtRelPos(x, z);
                if (data == null || data.isEmpty()) continue;
                covered.clear();
                boolean solid = false;
                for (int i = 0; i < data.size(); i++) {
                    long point = data.getLong(i);
                    int size = FullDataPointUtil.getHeight(point);
                    if (size <= 0) throw new IllegalArgumentException("DH coverage contains an empty height run");
                    int bottom = FullDataPointUtil.getBottomY(point);
                    int top = Math.min(height, Math.addExact(bottom, size));
                    // Native chunk conversion can include a sentinel above exclusive build height.
                    if (bottom >= height) continue;
                    int overlap = covered.nextSetBit(bottom);
                    if (overlap >= 0 && overlap < top) throw new IllegalArgumentException("Overlapping DH coverage runs");
                    covered.set(bottom, top);
                    var block = Objects.requireNonNull(source.mapping.getBlockStateWrapper(FullDataPointUtil.getId(point)),
                            "DH data point block mapping");
                    solid |= !block.isAir();
                }
                if (covered.nextClearBit(0) >= height) {
                    states[index] = solid ? TerrainColumnState.REAL_SOLID : TerrainColumnState.REAL_AIR;
                }
            }
        }
        return new DistantHorizonsColumnCoverage(source.getPos(), minHeight, maxHeightExclusive,
                source.isEmpty, Arrays.asList(states));
    }

    public TerrainColumnState column(int x, int z) {
        return columns.get(FullDataSourceV2.relativePosToIndex(x, z));
    }
}
