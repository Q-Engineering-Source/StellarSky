package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.Collections;
import org.junit.Test;

public class SeedTerrainMeshTest {
    @Test public void nearMeshStaysInPhysicalCoordinatesAtEveryRadius() {
        var tile=new SeedTerrainTile(new TerrainTileKey(1,6,10,0),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,80,80)));
        var small=SeedTerrainMesh.build(tile,43_683_000);
        var large=SeedTerrainMesh.build(tile,149_597_870_700.0);
        assertArrayEquals(small,large,0);
        for(int i=0;i<small.length;i+=16)assertEquals(80.0,(double)small[i+1]+small[i+4],0);
        assertEquals(tile.key().minBlockX(),(double)small[0]+small[3],0);
    }
    @Test public void adjacentFineTilesShareIdenticalBoundaryVertices() {
        var west=new SeedTerrainTile(new TerrainTileKey(1,6,0,0),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,80,80)));
        var east=new SeedTerrainTile(new TerrainTileKey(1,6,1,0),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,140,140)));
        var a=SeedTerrainMesh.build(west,43_683_000,63,east,null,null);
        var b=SeedTerrainMesh.build(east,43_683_000,63);
        for(int z=0;z<64;z++) {
            int left=((63*64+z)*6+1)*16,right=z*6*16;
            for(int component=0;component<6;component++)assertEquals(b[right+component],a[left+component],0);
        }
        assertThrows(IllegalArgumentException.class,()->SeedTerrainMesh.build(east,43_683_000,63,west,null,null));
    }
    @Test public void emptySpaceHasNoGeometry() {
        var tile=new SeedTerrainTile(new TerrainTileKey(1,6,0,2),Collections.nCopies(4096,SeedTerrainTile.Column.EMPTY));
        assertEquals(0,SeedTerrainMesh.build(tile,43_683_000).length);
    }
    @Test public void packedVerticesLieOnTheirTrianglePlanes() {
        var tile=new SeedTerrainTile(new TerrainTileKey(1,6,-2,-1),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,80,80)));
        var mesh=SeedTerrainMesh.build(tile,43_683_000);
        assertEquals(4096*6*16,mesh.length);
        for(int i=0;i<mesh.length;i+=16) {
            double x=(double)mesh[i]+mesh[i+3],y=(double)mesh[i+1]+mesh[i+4],z=(double)mesh[i+2]+mesh[i+5];
            double nx=(double)mesh[i+6]+mesh[i+10],ny=(double)mesh[i+7]+mesh[i+11],nz=(double)mesh[i+8]+mesh[i+12],d=(double)mesh[i+9]+mesh[i+13];
            assertEquals(0,nx*x+ny*y+nz*z+d,1e-6);
            assertEquals(1,mesh[i+14],0);
        }
    }
    @Test public void farBandUsesSixteenTimesFewerTrianglesAndPreservesOceanClass() {
        var tile=new SeedTerrainTile(new TerrainTileKey(1,8,2,0),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.OCEAN,30,63)));
        var mesh=SeedTerrainMesh.build(tile,43_683_000);
        assertEquals(256*6*16,mesh.length);
        for(int i=0;i<mesh.length;i+=16)assertEquals(0,mesh[i+14],0);
    }
    @Test public void farGraphicUsesOneSurfaceHeightEvenForTallLand() {
        var tile=new SeedTerrainTile(new TerrainTileKey(1,8,2,0),Collections.nCopies(4096,new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,240,240)));
        double radius=43_683_000;
        var mesh=SeedTerrainMesh.build(tile,radius,77);
        for(int i=0;i<mesh.length;i+=16) {
            double x=(double)mesh[i]+mesh[i+3],y=(double)mesh[i+1]+mesh[i+4];
            assertEquals(radius-77,Math.hypot(x,radius-y),1e-5);
            assertEquals(1,mesh[i+14],0);
        }
    }
}
