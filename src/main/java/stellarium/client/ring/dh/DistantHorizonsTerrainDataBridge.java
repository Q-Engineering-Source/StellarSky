package stellarium.client.ring.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import java.util.Objects;
import stellarium.world.ring.terrain.TerrainColumnState;
import stellarium.world.ring.terrain.TerrainTileCache;
import stellarium.world.ring.terrain.TerrainTileKey;

/** Data-side adapter only: publication does not authorize removing visible placeholder geometry. */
public final class DistantHorizonsTerrainDataBridge {
    private DistantHorizonsTerrainDataBridge() {}

    public static CapturedRead capture(TerrainTileCache.QueryTicket ticket, long sourceWorldEpoch,
                                       FullDataSourceV2 source, int minHeight, int maxHeightExclusive) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(source, "source");
        var actualKey = TerrainTileKey.atBlock(sourceWorldEpoch, source.getDataDetailLevel(),
                DhSectionPos.getMinCornerBlockX(source.getPos()), DhSectionPos.getMinCornerBlockZ(source.getPos()));
        if (!actualKey.equals(ticket.key())) throw new IllegalArgumentException("DH source does not match the requested world/tile");
        return new CapturedRead(ticket, DistantHorizonsColumnCoverage.capture(source, minHeight, maxHeightExclusive));
    }

    public static final class CapturedRead {
        private final TerrainTileCache.QueryTicket ticket;
        private final DistantHorizonsColumnCoverage coverage;
        private CapturedRead(TerrainTileCache.QueryTicket ticket, DistantHorizonsColumnCoverage coverage) {
            this.ticket = ticket;
            this.coverage = coverage;
        }
        public DistantHorizonsColumnCoverage coverage() { return coverage; }
        public boolean publish(TerrainTileCache cache) {
            return cache.publish(ticket, coverage.columns().toArray(TerrainColumnState[]::new));
        }
    }
}
