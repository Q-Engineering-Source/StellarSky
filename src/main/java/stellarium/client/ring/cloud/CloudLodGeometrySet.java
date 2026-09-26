package stellarium.client.ring.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One coherent atlas/geometry publication; unchanged page meshes survive ordinary camera travel. */
public final class CloudLodGeometrySet {
    public static final long MAX_BYTES = 128L * 1024L * 1024L;
    private final CloudWorldCache cache;
    private final CloudGeometrySettings geometry;
    private final CloudClipBounds clip;
    private final double baseY, radius;
    private final List<CloudLodPatchMeshBuilder.PatchMesh> meshes;
    private final long byteCount;
    private final boolean localNearCoordinates;

    private CloudLodGeometrySet(CloudWorldCache cache, CloudGeometrySettings geometry, CloudClipBounds clip,
                                double baseY, double radius, List<CloudLodPatchMeshBuilder.PatchMesh> meshes, long bytes,
                                boolean localNearCoordinates) {
        this.cache=cache; this.geometry=geometry; this.clip=clip; this.baseY=baseY; this.radius=radius;
        this.meshes=List.copyOf(meshes); this.byteCount=bytes;
        this.localNearCoordinates = localNearCoordinates;
    }

    public static CloudLodGeometrySet build(CloudWorldCache cache, CloudGeometrySettings geometry,
                                            double baseY, CloudClipBounds clip, double radius,
                                            CloudLodGeometrySet previous) {
        return build(cache,geometry,baseY,clip,radius,previous,()->false);
    }
    static CloudLodGeometrySet build(CloudWorldCache cache, CloudGeometrySettings geometry,
                                     double baseY, CloudClipBounds clip, double radius,
                                     CloudLodGeometrySet previous, BooleanSupplier obsolete) {
        return build(cache, geometry, baseY, clip, radius, previous, obsolete, false);
    }
    static CloudLodGeometrySet build(CloudWorldCache cache, CloudGeometrySettings geometry,
                                     double baseY, CloudClipBounds clip, double radius,
                                     CloudLodGeometrySet previous, BooleanSupplier obsolete, boolean localNearCoordinates) {
        Objects.requireNonNull(cache, "cache"); Objects.requireNonNull(geometry, "geometry"); Objects.requireNonNull(clip, "clip");
        if (!Double.isFinite(radius) || radius<=0 || !Double.isFinite(baseY)) throw new IllegalArgumentException("Invalid cloud raster geometry");
        boolean compatible=previous!=null && previous.localNearCoordinates == localNearCoordinates && previous.geometry.equals(geometry) && previous.clip.equals(clip)
                && previous.baseY==baseY && previous.radius==radius && previous.cache.settings().equals(cache.settings());
        List<CloudLodPatchMeshBuilder.PatchMesh> meshes=new ArrayList<>();
        long bytes=0;
        for (int index=0;index<CloudLodLayout.ATLAS_LEVELS.size();index++) {
            if (obsolete.getAsBoolean()) throw new CancellationException("Cloud raster specification superseded");
            var level=CloudLodLayout.ATLAS_LEVELS.get(index);
            var page=cache.page(level);
            var old=compatible?previous.cache.page(level):null;
            var mesh=old!=null && old.originX()==page.originX() && old.originZ()==page.originZ()
                    ? previous.meshes.get(index) : CloudLodPatchMeshBuilder.build(page,geometry,baseY,clip,radius,localNearCoordinates && index < 4);
            bytes=Math.addExact(bytes,mesh.byteCount());
            if (bytes>MAX_BYTES) throw new IllegalStateException("Complete cloud raster geometry exceeds 128 MiB: "+bytes);
            meshes.add(mesh);
        }
        return new CloudLodGeometrySet(cache,geometry,clip,baseY,radius,meshes,bytes,localNearCoordinates);
    }
    public CloudWorldCache cache() { return cache; }
    public CloudLodPatchMeshBuilder.PatchMesh mesh(int level) { return meshes.get(level); }
    public long byteCount() { return byteCount; }
    public double radius() { return radius; }
}
