package stellarium.world;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AtmosphereGeometryTest {
    @Test
    public void horizonAtSurfaceHasNoDepression() {
        assertEquals(0.0, AtmosphereGeometry.horizonDepressionDegrees(0.0, 800.0), 0.0);
    }

    @Test
    public void defaultOffsetProducesPhysicalHorizonDepression() {
        assertEquals(1.28103914419,
                AtmosphereGeometry.horizonDepressionDegrees(0.2, 800.0), 1.0e-9);
    }

    @Test
    public void higherObserverMovesHorizonDownward() {
        double low = AtmosphereGeometry.horizonDepressionDegrees(0.2, 800.0);
        double high = AtmosphereGeometry.horizonDepressionDegrees(1.0, 800.0);
        org.junit.Assert.assertTrue(high > low);
    }
}
