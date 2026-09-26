package stellarium.client.ring;

import static org.junit.Assert.*;

import java.util.Random;
import org.junit.BeforeClass;
import org.junit.Test;
import stellarium.client.ring.cloud.CloudClipBounds;
import stellarium.client.ring.cloud.CloudFieldSettings;
import stellarium.client.ring.cloud.CloudGeometrySettings;
import stellarium.client.ring.cloud.CloudLodTransition;
import stellarium.client.ring.cloud.CloudLodLayout;
import stellarium.client.ring.cloud.CloudMeshCacheKey;
import stellarium.client.ring.cloud.CloudMotionFrame;
import stellarium.client.ring.cloud.CloudRayBudget;
import stellarium.client.ring.cloud.CloudWorldCache;
import stellarium.world.ring.RingworldDisplayGeometry;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;

/** Exercises the same frame admission used by near rasterization and horizon caching without GL. */
public class SSCloudTraceCachePolicyTest {
    private static final Object WORLD = new Object(), SCENE = new Object();
    private static final CloudFieldSettings FIELD = new CloudFieldSettings(19, 0.0, false, 0.2);
    private static final CloudGeometrySettings GEOMETRY = new CloudGeometrySettings(12, 4, 32);
    private static final CloudClipBounds CLIP = new CloudClipBounds(0, 256, -8192, 8192);
    private static final CloudLodTransition TRANSITION = new CloudLodTransition(64, 128, 256);
    private static final RingworldRenderObserver EYE = new RingworldRenderObserver(100, 4096, 0);
    private static final double RADIUS = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
    private static CloudWorldCache atlas;

    @BeforeClass public static void prepareAtlas() {
        atlas = CloudWorldCache.around(1, FIELD, GEOMETRY, 0, 0);
    }

    @Test public void stationaryWindActuallyReusesButNeverStopsAdvancing() {
        SSCloudTraceCachePolicy policy = new SSCloudTraceCachePolicy();
        SSCloudTraceCacheKey prior = null;
        int reused = 0, refreshed = 0;
        double finalWind = 0;
        for (int i = 0; i < 240; i++) {
            SSCloudFrame live = frame(i * 0.005, EYE, camera(), 4096, 0.6, atlas, false, TRANSITION, CLIP);
            RingworldCurvatureFrame optical = curved(live);
            SSCloudFrame selected = policy.select(live, optical);
            assertSame(live.snapshot(), selected.snapshot());
            double selectedWind = selected.meshMotion().windOffsetBlocks();
            assertTrue(selectedWind >= finalWind);
            assertTrue(live.meshMotion().windOffsetBlocks() - selectedWind
                    <= SSCloudTraceCachePolicy.windAllowance(live, optical));
            assertEquals(selectedWind - EYE.x(), selected.meshMotion().meshOffsetX(), 0.0);
            assertEquals(selected.pageOriginX(CloudLodLayout.LOD0_FINE_3D),
                    atlas.page(CloudLodLayout.LOD0_FINE_3D).originX() * 12
                            + selectedWind - EYE.x(), 0.0);
            SSCloudTraceCacheKey key = SSCloudTraceCacheKey.capture(selected, optical);
            if (key.equals(prior)) reused++; else refreshed++;
            prior = key;
            finalWind = selectedWind;
        }
        assertTrue("Stationary capture must skip most raw traces", reused > 180);
        assertTrue("Continuing wind must refresh repeatedly", refreshed > 2);
        assertTrue(finalWind > 1.0);
    }

    @Test public void freshAuthorityAndBrightnessDoNotInvalidateRawCloudGeometry() {
        SSCloudTraceCachePolicy policy = new SSCloudTraceCachePolicy();
        SSCloudFrame first = frame(10, EYE, camera(), 4096, 0.6, atlas, false, TRANSITION, CLIP);
        SSCloudFrame selected = policy.select(first, curved(first));
        SSCloudFrame changedLighting = frame(10.001, EYE, camera(), 8192, 0.2, atlas, false, TRANSITION, CLIP);
        SSCloudFrame reused = policy.select(changedLighting, curved(changedLighting));
        assertEquals(SSCloudTraceCacheKey.capture(selected, curved(selected)),
                SSCloudTraceCacheKey.capture(reused, curved(reused)));
        assertSame(changedLighting.snapshot(), reused.snapshot());
        assertEquals(8192, reused.snapshot().sunshadeHeightBlocks());
        assertEquals(0.2, reused.bottomBrightness(), 0.0);
    }

    @Test public void submillimetreTravelAndViewportOriginChangesRefreshImmediately() {
        assertRefresh(frame(0.001, new RingworldRenderObserver(100.00001, 4096, 0), camera(), 4096, 0.6,
                atlas, false, TRANSITION, CLIP));
        assertRefresh(frame(0.001, EYE, CloudCameraFrame.from(projection(), identity(), 1, 0, 1920, 1009),
                4096, 0.6, atlas, false, TRANSITION, CLIP));
        float[] zoom = projection(); zoom[0] = Math.nextUp(zoom[0]);
        assertRefresh(frame(0.001, EYE, CloudCameraFrame.from(zoom, identity(), 0, 0, 1920, 1009),
                4096, 0.6, atlas, false, TRANSITION, CLIP));
        float[] turned = identity(); turned[0] = 0; turned[2] = -1; turned[8] = 1; turned[10] = 0;
        assertRefresh(frame(0.001, EYE, CloudCameraFrame.from(projection(), turned, 0, 0, 1920, 1009),
                4096, 0.6, atlas, false, TRANSITION, CLIP));
    }

    @Test public void representationChangesRefreshAndReloadReleasesTheAnchor() {
        assertRefresh(frame(0.001, EYE, camera(), 4096, 0.6, atlas, true, TRANSITION, CLIP));
        assertRefresh(frame(0.001, EYE, camera(), 4096, 0.6, atlas, false,
                new CloudLodTransition(63, 128, 256), CLIP));
        assertRefresh(frame(0.001, EYE, camera(), 4096, 0.6, atlas, false, TRANSITION,
                new CloudClipBounds(1, 256, -8192, 8192)));
        CloudWorldCache nextAtlas = CloudWorldCache.around(2, FIELD, GEOMETRY, 0, 0, atlas);
        assertRefresh(frame(0.001, EYE, camera(), 4096, 0.6, nextAtlas, false, TRANSITION, CLIP));
        SSCloudTraceCachePolicy policy = new SSCloudTraceCachePolicy();
        SSCloudFrame first = frame(0, EYE, camera(), 4096, 0.6, atlas, false, TRANSITION, CLIP);
        policy.select(first, curved(first));
        policy.clear();
        SSCloudFrame live = first.withWind(0.001);
        assertSame(live, policy.select(live, curved(live)));
    }

    @Test public void nearCloudsAndUnsupportedViewsKeepContinuousWind() {
        SSCloudFrame inside = frame(10, new RingworldRenderObserver(100, 140, 0), camera(), 4096, 0.6,
                atlas, false, TRANSITION, CLIP);
        assertEquals(0.0, SSCloudTraceCachePolicy.windAllowance(inside, curved(inside)), 0.0);
        CloudCameraFrame orthographic = CloudCameraFrame.from(identity(), identity(), 0, 0, 1920, 1009);
        SSCloudFrame unsupported = frame(10, EYE, orthographic, 4096, 0.6, atlas, false, TRANSITION, CLIP);
        assertEquals(0.0, SSCloudTraceCachePolicy.windAllowance(unsupported, curved(unsupported)), 0.0);
        SSCloudTraceCachePolicy policy = new SSCloudTraceCachePolicy();
        policy.select(inside, curved(inside));
        SSCloudFrame later = inside.withWind(10.001);
        assertSame(later, policy.select(later, curved(later)));
        assertSame(later, policy.select(later, null));
    }

    @Test public void radiusWorldAndSceneCannotReuseOldRayDistances() {
        SSCloudFrame first = frame(0, EYE, camera(), 4096, 0.6, atlas, false, TRANSITION, CLIP);
        SSCloudTraceCacheKey key = SSCloudTraceCacheKey.capture(first, curved(first));
        assertNotEquals(key, SSCloudTraceCacheKey.capture(first,
                new RingworldCurvatureFrame(WORLD, SCENE, EYE, RADIUS * 2, projection(), identity(), 0, 0, 1920, 1009)));
        assertThrows(IllegalArgumentException.class, () -> SSCloudTraceCacheKey.capture(first,
                new RingworldCurvatureFrame(new Object(), SCENE, EYE, RADIUS, projection(), identity(), 0, 0, 1920, 1009)));
        assertThrows(IllegalArgumentException.class, () -> SSCloudTraceCacheKey.capture(first,
                new RingworldCurvatureFrame(WORLD, new Object(), EYE, RADIUS, projection(), identity(), 0, 0, 1920, 1009)));
    }

    @Test public void projectionAllowanceBoundsSilhouetteMotionIncludingViewportCorners() {
        Random random = new Random(35);
        float[] projection = projection();
        projection[8] = 0.3f; projection[9] = -0.2f;
        CloudCameraFrame camera = CloudCameraFrame.from(projection, identity(), 0, 0, 1920, 1009);
        double allowance = camera.displacementForQuarterPixel(3000);
        assertTrue(allowance > 0);
        for (int i = 0; i < 10000; i++) {
            double sx = i % 3 == 0 ? (i % 2 == 0 ? -1 : 1) : random.nextDouble() * 2 - 1;
            double sy = i % 3 == 0 ? (i % 2 == 0 ? 1 : -1) : random.nextDouble() * 2 - 1;
            double tx = (sx + projection[8]) / projection[0], ty = (sy + projection[9]) / projection[5];
            double distance = 3000 + random.nextDouble() * 100000;
            double z = -distance / Math.sqrt(1 + tx * tx + ty * ty), x = -z * tx, y = -z * ty;
            double dx = random.nextGaussian(), dy = random.nextGaussian(), dz = random.nextGaussian();
            double norm = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx *= allowance / norm; dy *= allowance / norm; dz *= allowance / norm;
            double errorX = 960 * projection[0] * ((x + dx) / -(z + dz) - x / -z);
            double errorY = 504.5 * projection[5] * ((y + dy) / -(z + dz) - y / -z);
            assertTrue("Projected cloud geometry exceeded quarter pixel", Math.hypot(errorX, errorY) <= 0.25);
        }
    }

    private static void assertRefresh(SSCloudFrame changed) {
        SSCloudTraceCachePolicy policy = new SSCloudTraceCachePolicy();
        SSCloudFrame first = frame(0, EYE, camera(), 4096, 0.6, atlas, false, TRANSITION, CLIP);
        policy.select(first, curved(first));
        assertSame(changed, policy.select(changed, curved(changed)));
    }

    private static SSCloudFrame frame(double wind, RingworldRenderObserver observer, CloudCameraFrame camera,
            int boardHeight, double brightness, CloudWorldCache cache, boolean cull,
            CloudLodTransition transition, CloudClipBounds clip) {
        RingworldDisplaySnapshot snapshot = new RingworldDisplaySnapshot(WORLD, SCENE, null,
                new RingworldSunshade(10, 4, 100, 0, 0, 0), null, boardHeight, 32, observer, 1, 0.2);
        CloudMotionFrame motion = new CloudMotionFrame(0, 0, wind - observer.x(), -observer.z(), wind);
        return new SSCloudFrame(snapshot, FIELD, cache, GEOMETRY, clip, motion,
                CloudMeshCacheKey.at(0, 0, cache.fineMask(), GEOMETRY, 128, clip),
                CloudRayBudget.prepare(16384, GEOMETRY, transition), false, 128,
                cull, false, false, false, camera, transition, brightness);
    }

    private static RingworldCurvatureFrame curved(SSCloudFrame frame) {
        // Cache tests intentionally use the production render origin; camera-specific changes
        // are independently checked by the raw key's CloudCameraFrame.
        return new RingworldCurvatureFrame(WORLD, SCENE, frame.snapshot().observer(), RADIUS,
                projection(), identity(), 0, 0, 1920, 1009);
    }
    private static CloudCameraFrame camera() { return CloudCameraFrame.from(projection(), identity(), 0, 0, 1920, 1009); }
    private static float[] projection() { return new float[]{0.9f,0,0,0, 0,1.7f,0,0, 0,0,-1.002f,-1, 0,0,-0.2002f,0}; }
    private static float[] identity() { return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
}
