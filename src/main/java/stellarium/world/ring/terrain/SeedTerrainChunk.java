package stellarium.world.ring.terrain;

import java.util.Objects;

public sealed interface SeedTerrainChunk {
    int chunkX();
    int chunkZ();
    SeedTerrainTile.Column column(int x,int z);

    record Empty(int chunkX,int chunkZ) implements SeedTerrainChunk {
        @Override public SeedTerrainTile.Column column(int x,int z) {
            if(x<0 || x>=16 || z<0 || z>=16) throw new IndexOutOfBoundsException("Chunk column");
            return SeedTerrainTile.Column.EMPTY;
        }
    }
    record Density(OverworldDensityLattice lattice) implements SeedTerrainChunk {
        public Density { Objects.requireNonNull(lattice); }
        @Override public int chunkX() { return lattice.chunkX(); }
        @Override public int chunkZ() { return lattice.chunkZ(); }
        @Override public SeedTerrainTile.Column column(int x,int z) {
            var surface=lattice.surface(x,z);
            return new SeedTerrainTile.Column(surface.land()?SeedTerrainTile.Kind.LAND:SeedTerrainTile.Kind.OCEAN,
                    surface.baseGroundTop(),surface.visibleTop());
        }
    }
}
