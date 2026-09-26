package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CloudDebugSettingsTest {
    @Test
    public void materialComparisonDoesNotChangeOverlaySelectionOrGeometryCounters() {
        try {
            CloudDebugSettings.setMode(CloudDebugSettings.Mode.BOTH);
            CloudDebugSettings.publishStats(2, 3, 4L);
            CloudDebugSettings.setMaterialMode(CloudDebugSettings.MaterialMode.fromCommand("ExAcT"));
            assertEquals(CloudDebugSettings.MaterialMode.EXACT, CloudDebugSettings.materialMode());
            assertEquals(CloudDebugSettings.Mode.BOTH, CloudDebugSettings.mode());
            assertEquals(new CloudDebugSettings.Stats(2, 3, 4L), CloudDebugSettings.stats());
            assertThrows(IllegalArgumentException.class, () -> CloudDebugSettings.MaterialMode.fromCommand("fastest"));
            assertThrows(IllegalArgumentException.class, () -> CloudDebugSettings.setMaterialMode(null));
            assertEquals(CloudDebugSettings.MaterialMode.EXACT, CloudDebugSettings.materialMode());
        } finally {
            CloudDebugSettings.setMaterialMode(CloudDebugSettings.MaterialMode.CACHED);
            CloudDebugSettings.setMode(CloudDebugSettings.Mode.OFF);
        }
    }

    @Test
    public void commandModesAreExplicitAndDoNotPersistAcrossAnOffSelection() {
        assertEquals(CloudDebugSettings.Mode.VERTICES, CloudDebugCommand.parseMode("VeRtIcEs"));
        assertEquals(CloudDebugSettings.Mode.TRIANGLES, CloudDebugSettings.Mode.fromCommand("triangles"));
        assertTrue(CloudDebugSettings.Mode.BOTH.drawsVertices());
        assertTrue(CloudDebugSettings.Mode.BOTH.drawsTriangles());
        assertFalse(CloudDebugSettings.Mode.OFF.drawsVertices());
        assertThrows(IllegalArgumentException.class, () -> CloudDebugCommand.parseMode("wireframe"));
    }

    @Test
    public void changingModeClearsPublishedFrameCounters() {
        CloudDebugSettings.setMode(CloudDebugSettings.Mode.BOTH);
        CloudDebugSettings.publishStats(4, 7, 19L);
        assertEquals(new CloudDebugSettings.Stats(4, 7, 19L), CloudDebugSettings.stats());
        CloudDebugSettings.setMode(CloudDebugSettings.Mode.OFF);
        assertFalse(CloudDebugSettings.enabled());
        assertEquals(new CloudDebugSettings.Stats(0, 0, 0L), CloudDebugSettings.stats());
        assertTrue(CloudDebugSettings.status().contains("debug=off"));
        assertEquals(76L, new CloudDebugSettings.Stats(0, 0, 19L).submittedCorners());
        assertEquals(38L, new CloudDebugSettings.Stats(0, 0, 19L).submittedTriangles());
    }

    @Test
    public void countersRejectImpossibleSubmittedGeometry() {
        assertThrows(IllegalArgumentException.class, () -> CloudDebugSettings.publishStats(-1, 0, 0L));
        assertThrows(IllegalArgumentException.class, () -> new CloudDebugSettings.Stats(0, -1, 0L));
    }
}
