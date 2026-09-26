package stellarium.world.ring.terrain;

import java.util.Arrays;

/** Frozen global-cylinder triangles using the procedural ring's 16-float vertex layout. */
public final class SeedTerrainMesh {
    private SeedTerrainMesh() {}
    public static int stride(TerrainTileKey key) {return SeedTerrainTile.sampleStride(key);}
    public static float[] build(SeedTerrainTile tile,double radius) {
        return build(tile,radius,63.0);
    }
    /** Far layer is a flat land/ocean graphic on the cylinder, not a terrain-height mesh. */
    public static float[] build(SeedTerrainTile tile,double radius,double summaryY) {
        return build(tile,radius,summaryY,null,null,null);
    }
    public static float[] build(SeedTerrainTile tile,double radius,double summaryY,
                               SeedTerrainTile east,SeedTerrainTile north,SeedTerrainTile corner) {
        checkNeighbor(tile,east,1,0);checkNeighbor(tile,north,0,1);checkNeighbor(tile,corner,1,1);
        if(!Double.isFinite(radius)||radius<=256)throw new IllegalArgumentException("Invalid terrain radius");
        if(!Double.isFinite(summaryY)||summaryY>=radius)throw new IllegalArgumentException("Invalid summary plane");
        int stride=stride(tile.key());
        int strideZ=SeedTerrainTile.sampleStrideZ(tile.key());
        float[] result=new float[(64/stride)*(64/strideZ)*6*16];int cursor=0;
        long step=1L<<tile.key().level();
        for(int x=0;x<64;x+=stride)for(int z=0;z<64;z+=strideZ) {
            var column=tile.columns().get(x*64+z);
            if(column.kind()==SeedTerrainTile.Kind.EMPTY)continue;
            double wx=tile.key().minBlockX()+x*step,wz=tile.key().minBlockZ()+z*step;
            boolean flat=tile.key().level()>=8;
            double h=flat?summaryY:column.visibleTop();long cell=step*stride,cellZ=step*strideZ;
            double[] a=point(wx,h,wz,radius,flat),b=point(wx+cell,flat?h:height(tile,east,north,corner,x+stride,z,h),wz,radius,flat);
            double[] c=point(wx+cell,flat?h:height(tile,east,north,corner,x+stride,z+strideZ,h),wz+cellZ,radius,flat),d=point(wx,flat?h:height(tile,east,north,corner,x,z+strideZ,h),wz+cellZ,radius,flat);
            float material=column.kind()==SeedTerrainTile.Kind.OCEAN?0:1;
            cursor=triangle(result,cursor,a,b,c,material);
            cursor=triangle(result,cursor,a,c,d,material);
        }
        return Arrays.copyOf(result,cursor);
    }
    private static void checkNeighbor(SeedTerrainTile tile,SeedTerrainTile neighbor,int dx,int dz) {
        var key=tile.key();
        if(neighbor!=null&&!neighbor.key().equals(new TerrainTileKey(key.worldEpoch(),key.level(),key.x()+dx,key.z()+dz)))
            throw new IllegalArgumentException("Foreign mesh neighbor");
    }
    private static double height(SeedTerrainTile tile,SeedTerrainTile east,SeedTerrainTile north,SeedTerrainTile corner,int x,int z,double fallback) {
        var neighbor=x>=64?(z>=64?corner:east):(z>=64?north:tile);
        var sample=neighbor==null?tile.columns().get(Math.min(x,63)*64+Math.min(z,63))
                :neighbor.columns().get((x%64)*64+z%64);
        return sample.kind()==SeedTerrainTile.Kind.EMPTY?fallback:sample.visibleTop();
    }
    private static double[] point(double x,double y,double z,double r,boolean curved) {
        if(!curved)return new double[]{x,y,z};
        double angle=x/r,s=Math.sin(angle),half=Math.sin(angle*0.5);
        return new double[]{(r-y)*s,y+2*(r-y)*half*half,z};
    }
    private static int triangle(float[] out,int cursor,double[] a,double[] b,double[] c,float material) {
        double ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
        double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
        double length=Math.sqrt(nx*nx+ny*ny+nz*nz);nx/=length;ny/=length;nz/=length;
        double[] plane={nx,ny,nz,-nx*a[0]-ny*a[1]-nz*a[2]};
        for(var point:new double[][]{a,b,c}) {
            for(double value:point)out[cursor++]=(float)value;
            for(double value:point)out[cursor++]=(float)(value-(float)value);
            for(double value:plane)out[cursor++]=(float)value;
            for(double value:plane)out[cursor++]=(float)(value-(float)value);
            out[cursor++]=material;out[cursor++]=0;
        }
        return cursor;
    }
}
