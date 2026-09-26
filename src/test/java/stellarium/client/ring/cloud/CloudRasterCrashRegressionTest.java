package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Captured C39 input: player (-16447.05,4094.52,35225.83), coverage/erosion both 0.6. */
public class CloudRasterCrashRegressionTest {
    private static final CloudGeometrySettings GEOMETRY=new CloudGeometrySettings(12,4,64);
    private static final CloudClipBounds CLIP=new CloudClipBounds(0,256,-8192,8192);
    private static final double RADIUS=43_682_578.2444;

    @Test public void capturedOnRequestPublishesWithinBudgetOutsideTheRingStrip() {
        var target=new CloudWindowStreamer.Target(new CloudFieldSettings(0,0.6,true,0.6),GEOMETRY,
                -1376,2928,224,CLIP,false,1,RADIUS);
        try(var streamer=new CloudWindowStreamer(Runnable::run)) {
            streamer.request(target);
            assertNull("Captured ssmodel on request must not fail its background cache generation",streamer.failure(target));
            var ready=streamer.poll(target);
            assertNotNull(ready);
            assertTrue(ready.covers(target,16));
            assertTrue(ready.rasterGeometry().byteCount()<=CloudLodGeometrySet.MAX_BYTES);
            for(int level=0;level<4;level++) assertEquals("Entire 3D page lies beyond physical strip",0,
                    ready.rasterGeometry().mesh(level).quadCount());
        }
    }

    @Test public void transparentCacheNeverCreatesThreeDimensionalCloudFaces() {
        var cache=CloudWorldCache.around(1,new CloudFieldSettings(0,0,false,0),GEOMETRY,0,0);
        for(var level:CloudLodLayout.THREE_DIMENSIONAL_LEVELS) {
            var mesh=CloudLodPatchMeshBuilder.build(cache.page(level),GEOMETRY,224,CLIP,RADIUS);
            assertEquals(level.glslName()+" contains no occupied cells",0,mesh.quadCount());
        }
    }
}
