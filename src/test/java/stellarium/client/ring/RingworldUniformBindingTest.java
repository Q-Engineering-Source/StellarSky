package stellarium.client.ring;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import java.util.Map;
import org.junit.Test;

public class RingworldUniformBindingTest {
    @Test public void connectedCloudRequiresActualBoardSelectionUniforms() {
        assertThrows(IllegalStateException.class, () -> new CloudLodRasterProgram().bindUniforms(
                name -> name.equals("uSSBoardMeshDepthActive") ? -1 : 0));
    }
    @Test
    public void missingLiveProgramUniformsRemainFatal() {
        Map<String, String> required = Map.ofEntries(
                Map.entry("board", "uBandSpacing"), Map.entry("board_mesh", "uBandSpacing"), Map.entry("cloud", "uMeshOffset"),
                Map.entry("horizon", "uCloudAtlas"), Map.entry("horizon_raw", "uCloudRawPixelOffset"),
                Map.entry("horizon_composite", "uCloudTraceGeometry"), Map.entry("spatial_air", "uSceneDepth"),
                Map.entry("spatial_air_curved", "uSceneDepth"), Map.entry("spatial_air_local", "uSceneDepth"),
                Map.entry("dh", "uSSDhCameraOffset"), Map.entry("model", "uModelSurface"), Map.entry("cloud_lod", "uCloudAtlas"),
                Map.entry("cloud_debug", "uDebugMode"));
        assertEquals(required.keySet(), RingworldUniformContractDiagnostics.bindings().keySet());
        for (var binding : RingworldUniformContractDiagnostics.bindings().entrySet()) {
            assertThrows(binding.getKey(), IllegalStateException.class,
                    () -> binding.getValue().accept(name -> name.equals(required.get(binding.getKey())) ? -1 : 0));
        }
    }

    @Test
    public void missingSharedOpticalEyeUniformRemainsFatalForEveryConsumer() {
        for (var binding : RingworldUniformContractDiagnostics.bindings().entrySet()) {
            assertThrows(binding.getKey(), IllegalStateException.class,
                    () -> binding.getValue().accept(name -> name.equals("uSSEyeRelative") ? -1 : 0));
        }
    }
}
