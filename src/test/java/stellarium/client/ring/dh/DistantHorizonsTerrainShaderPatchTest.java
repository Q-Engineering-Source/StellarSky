package stellarium.client.ring.dh;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Contract tests for the fail-closed native-DH shader transformation seam. */
public class DistantHorizonsTerrainShaderPatchTest {
    private static final String SNIPPET = "/* STELLARSKY_DH_3_2_0_B_CURVATURE_BEGIN */\n"
            + "void ssApplyDistantHorizonsCurvature(inout vec3 vertexWorldPos) {}\n"
            + "/* STELLARSKY_DH_3_2_0_B_CURVATURE_END */";

    @Test public void insertsTheCurvedCameraSpaceMappingBeforeDhCanApplyItsOwnEarthCurve() {
        String source = officialVertexFixture();
        String patched = DistantHorizonsTerrainShaderPatch.patch(DistantHorizonsTerrainShaderPatch.VERTEX_PATH,
                source, SNIPPET);
        int declarations = patched.indexOf(DistantHorizonsTerrainShaderPatch.SNIPPET_SENTINEL);
        int application = patched.indexOf(DistantHorizonsTerrainShaderPatch.APPLICATION);
        int earthCurve = patched.indexOf(DistantHorizonsTerrainShaderPatch.CURVATURE_ANCHOR);
        int projection = patched.indexOf("gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);");
        int displayFromEye = patched.indexOf(DistantHorizonsTerrainShaderPatch.DISPLAY_VARYING);
        assertTrue(declarations > patched.indexOf(DistantHorizonsTerrainShaderPatch.VERSION_DIRECTIVE));
        assertTrue(application > declarations && earthCurve > application && displayFromEye > earthCurve
                && projection > displayFromEye);
    }

    @Test public void unrelatedShaderResourceIsTheOriginalStringInstance() {
        String source = new String("#version 330 core\nvoid main() {}\n");
        assertSame(source, DistantHorizonsTerrainShaderPatch.patch("assets/distanthorizons/shaders/fog/gl/fog.frag",
                source, SNIPPET));
    }

    @Test public void changedOrAlreadyPatchedTerrainSourceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> DistantHorizonsTerrainShaderPatch.patch(
                DistantHorizonsTerrainShaderPatch.VERTEX_PATH,
                officialVertexFixture().replace(DistantHorizonsTerrainShaderPatch.CURVATURE_ANCHOR, "// changed"),
                SNIPPET));
        String patched = DistantHorizonsTerrainShaderPatch.patch(DistantHorizonsTerrainShaderPatch.VERTEX_PATH,
                officialVertexFixture(), SNIPPET);
        assertThrows(IllegalStateException.class, () -> DistantHorizonsTerrainShaderPatch.patch(
                DistantHorizonsTerrainShaderPatch.VERTEX_PATH, patched, SNIPPET));
    }

    @Test public void fragmentDiscardIsInsertedAtMainHeadBeforeAnyDhColorOrBlendWork() {
        String snippet = "/* STELLARSKY_DH_3_2_0_B_OWN_MEDIA_OCCLUSION_BEGIN */\n"
                + "in vec3 vSSDisplayFromEye;\n"
                + "void ssDiscardBehindOwnMedia() {}\n"
                + "/* STELLARSKY_DH_3_2_0_B_OWN_MEDIA_OCCLUSION_END */";
        String patched = DistantHorizonsTerrainShaderPatch.patchFragment(
                DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH, officialFragmentFixture(), snippet);
        int input = patched.indexOf("in vec3 vSSDisplayFromEye;");
        int discard = patched.indexOf(DistantHorizonsTerrainShaderPatch.OCCLUSION_APPLICATION);
        int color = patched.indexOf("fragColor = vertexColor;");
        assertTrue(input > 0 && discard > input && color > discard);
        assertThrows(IllegalStateException.class, () -> DistantHorizonsTerrainShaderPatch.patchFragment(
                DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH, patched, snippet));
        assertThrows(IllegalStateException.class, () -> DistantHorizonsTerrainShaderPatch.patchFragment(
                DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH,
                officialFragmentFixture().replace("#version 330\n", "#version 330 core\n"), snippet));
    }

    private static String officialVertexFixture() {
        return "#version 330 core\n"
                + "uniform vec3 uModelOffset;\n"
                + "flat out uint vTextureTileId;\n"
                + "void main() {\n"
                + "    vec3 vertexWorldPos = vPosition.xyz + uModelOffset;\n"
                + "    // apply the earth curvature if needed\n"
                + "    if (uEarthRadius > 1.0f) { vertexWorldPos.y += 1.0; }\n"
                + "    " + DistantHorizonsTerrainShaderPatch.LIGHT_ANCHOR + "\n"
                + "    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);\n"
                + "}\n";
    }

    private static String officialFragmentFixture() {
        return "#version 330\n"
                + "in vec4 gl_FragCoord;\n"
                + "out vec4 fragColor;\n"
                + "void main()\n{\n"
                + "    fragColor = vertexColor;\n"
                + "}\n";
    }
}
