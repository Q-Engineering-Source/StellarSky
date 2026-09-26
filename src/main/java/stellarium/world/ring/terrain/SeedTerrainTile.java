package stellarium.world.ring.terrain;

import java.util.List;
import java.util.Objects;

/** Complete immutable 64x64 seed samples, x-major, at the key's spacing. No seed is exposed. */
public record SeedTerrainTile(TerrainTileKey key, List<Column> columns) {
    /** Far summaries hold one actual seed sample per 4x4 cells, replicated on the wire/cache grid. */
    public static int sampleStride(TerrainTileKey key) {return key.level()>=8?4:1;}
    /** Preserve the 8192m half-strip boundary when far cells become wider than the playable strip. */
    public static int sampleStrideZ(TerrainTileKey key) {
        return Math.min(sampleStride(key),Math.max(1,8192/(1<<key.level())));
    }
    public SeedTerrainTile {
        Objects.requireNonNull(key);
        columns=List.copyOf(columns);
        if(columns.size()!=TerrainTileKey.COLUMN_COUNT) throw new IllegalArgumentException("Incomplete preview tile");
    }
    public enum Kind { EMPTY, LAND, OCEAN }
    public record Column(Kind kind,int groundTop,int visibleTop) {
        public static final Column EMPTY=new Column(Kind.EMPTY,0,0);
        public Column {
            Objects.requireNonNull(kind);
            if(groundTop<0 || groundTop>256 || visibleTop<groundTop || visibleTop>256
                    || (kind==Kind.EMPTY && visibleTop!=0)) throw new IllegalArgumentException("Invalid preview height");
        }
    }
}
