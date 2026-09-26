package stellarium.client.ring.cloud;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import stellarium.world.ring.RingworldDisplayGeometry;

/** Rebuilding a cache must retain only page meshes whose absolute page identity is unchanged. */
public class CloudLodGeometrySetTest {
    private static final CloudFieldSettings EMPTY_FIELD = new CloudFieldSettings(91L, 0.0D, false, 0.2D);
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12.0D, 4.0D, 64);
    private static final CloudClipBounds CLIP = new CloudClipBounds(0.0D, 256.0D, -8_192.0D, 8_192.0D);
    private static final double BASE_Y = 128.0D;
    private static final double RADIUS = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;

    @Test
    public void exactSamePageOriginsReuseEveryCachedPatchMeshByIdentity() {
        CloudWorldCache firstCache = cache(1L, 0L, 0L, null);
        CloudLodGeometrySet first = geometry(firstCache, null);
        CloudWorldCache sameOrigins = cache(2L, 0L, 0L, firstCache);
        CloudLodGeometrySet rebuilt = geometry(sameOrigins, first);

        for (int level = 0; level < CloudLodLayout.ATLAS_LEVELS.size(); level++) {
            assertSame("unchanged absolute page must retain its cached mesh at level " + level,
                    first.mesh(level), rebuilt.mesh(level));
        }
    }

    @Test
    public void movingOneFinestAnchorRebuildsOnlyItsChangedPageAndKeepsCoarserPages() {
        CloudWorldCache firstCache = cache(1L, 0L, 0L, null);
        CloudLodGeometrySet first = geometry(firstCache, null);

        // One finest-cell move changes LOD0's absolute origin. Every coarser page has an XZ
        // scale of at least two, so its floored anchor and immutable page origin remain unchanged.
        CloudWorldCache movedCache = cache(2L, 1L, 0L, firstCache);
        CloudLodGeometrySet moved = geometry(movedCache, first);

        assertNotSame("LOD0 must be rebuilt after its absolute page origin changes", first.mesh(0), moved.mesh(0));
        for (int level = 1; level < CloudLodLayout.ATLAS_LEVELS.size(); level++) {
            assertSame("unchanged coarser page must keep its mesh at level " + level,
                    first.mesh(level), moved.mesh(level));
        }
    }

    private static CloudWorldCache cache(long generation, long cellX, long cellZ, CloudWorldCache previous) {
        return CloudWorldCache.around(generation, EMPTY_FIELD, GEOMETRY, cellX, cellZ, previous);
    }

    private static CloudLodGeometrySet geometry(CloudWorldCache cache, CloudLodGeometrySet previous) {
        return CloudLodGeometrySet.build(cache, GEOMETRY, BASE_Y, CLIP, RADIUS, previous);
    }
}
