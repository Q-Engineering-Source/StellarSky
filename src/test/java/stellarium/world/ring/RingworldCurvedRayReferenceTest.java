package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

/** Tests the pure reference used to specify the shared GLSL root and metric contract. */
public class RingworldCurvedRayReferenceTest {
    @Test public void oneAuNearGroundRootUsesTheCancellationFreeConstant() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry();
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 64.0, 0.0);
        RingworldDisplayGeometry.Vector ray = new RingworldDisplayGeometry.Vector(0.0, 1.0, 0.0);
        RingworldCurvedRayReference.HeightRoots roots = RingworldCurvedRayReference.heightRoots(geometry, eye, ray, 128.0);
        assertSame(RingworldCurvedRayReference.HeightRootKind.TWO, roots.kind());
        assertEquals(64.0, roots.first(), 1.0e-12);
        assertEquals(2.0 * (geometry.radiusMeters() - 64.0) - 64.0, roots.second(), 1.0e-3);
    }

    @Test public void tangentAndAxisRemainExplicitInsteadOfBecomingFallbackHits() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1_000.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 100.0, 0.0);
        RingworldDisplayGeometry.Vector tangentRay = new RingworldDisplayGeometry.Vector(0.6, 0.8, 0.0);
        RingworldCurvedRayReference.HeightRoots tangent = RingworldCurvedRayReference.heightRoots(geometry, eye, tangentRay, 460.0);
        assertSame(RingworldCurvedRayReference.HeightRootKind.TANGENT, tangent.kind());
        assertEquals(720.0, tangent.first(), 1.0e-9);

        RingworldDisplayGeometry.Vector axisRay = new RingworldDisplayGeometry.Vector(0.0, 1.0, 0.0);
        assertThrows(IllegalStateException.class, () -> RingworldCurvedRayReference.pathWeight(geometry, eye, axisRay, 900.0));
    }

    @Test public void pathWeightMatchesTheExistingDisplayRayAtARepresentativeObliqueSample() {
        RingworldDisplayGeometry geometry = new RingworldDisplayGeometry(1_000.0);
        RingworldRenderObserver eye = new RingworldRenderObserver(0.0, 100.0, -20.0);
        RingworldDisplayGeometry.Vector ray = new RingworldDisplayGeometry.Vector(0.3, 0.4, Math.sqrt(0.75));
        RingworldDisplayRay existing = new RingworldDisplayRay(geometry, eye, ray);
        assertEquals(existing.physicalPathDerivative(200.0),
                RingworldCurvedRayReference.pathWeight(geometry, eye, existing.direction(), 200.0), 0.0);
    }
}
