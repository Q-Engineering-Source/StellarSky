package stellarium.client.ring.actinium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Behavioral contract for the fail-closed source patch; root owns real assembled GLSL compilation. */
public class ActiniumTerrainShaderPatchTest {
    private static final String SNIPPET = "/* STELLARSKY_ACTINIUM_C25_CURVATURE_BEGIN */\n"
            + "#extension GL_ARB_gpu_shader_fp64 : require\n"
            + "vec3 ssApplyRingworldCurvature(vec3 position, mat4 nativeModelView) { return position; }\n"
            + "/* STELLARSKY_ACTINIUM_C25_CURVATURE_END */";

    @Test public void patchesOnlyTheVerifiedPositionStatementAndPreservesTheRemainingShaderProgram() {
        String source = officialOpaqueFixture();
        String patched = ActiniumTerrainShaderPatch.patch(ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH,
                source, SNIPPET);
        int position = patched.indexOf(ActiniumTerrainShaderPatch.POSITION_ANCHOR);
        int curvature = patched.indexOf("position = ssApplyRingworldCurvature(position, u_ModelViewMatrix);", position);
        int fog = patched.indexOf("v_FragDistance = getFragDistance(u_FogShape, position);", curvature);
        int projection = patched.indexOf("gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);", fog);
        int lightmap = patched.indexOf("vec4 lightColor = _sample_lightmap(u_LightTex, _vert_tex_light_coord);",
                projection);
        assertTrue(position >= 0 && curvature > position && fog > curvature && projection > fog && lightmap > projection);
        assertEquals(1, occurrences(patched, ActiniumTerrainShaderPatch.SNIPPET_SENTINEL));
        assertEquals(1, occurrences(patched, ActiniumTerrainShaderPatch.POSITION_ANCHOR));
    }

    @Test public void unknownPathIsTheSameUnchangedSourceInstance() {
        String source = new String("#version 400 core\nvoid main() {}\n");
        assertSame(source, ActiniumTerrainShaderPatch.patch("actinium:blocks/block_layer_cutout.vsh", source, SNIPPET));
    }

    @Test public void missingOrDuplicateOfficialAnchorFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ActiniumTerrainShaderPatch.patch(
                ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH,
                officialOpaqueFixture().replace(ActiniumTerrainShaderPatch.POSITION_ANCHOR, "vec3 position = translation;"),
                SNIPPET));
        assertThrows(IllegalStateException.class, () -> ActiniumTerrainShaderPatch.patch(
                ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH,
                officialOpaqueFixture() + ActiniumTerrainShaderPatch.POSITION_ANCHOR,
                SNIPPET));
    }

    @Test public void anAlreadyPatchedOfficialSourceFailsInsteadOfDoubleInjection() {
        String patched = ActiniumTerrainShaderPatch.patch(ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH,
                officialOpaqueFixture(), SNIPPET);
        assertThrows(IllegalStateException.class, () -> ActiniumTerrainShaderPatch.patch(
                ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH, patched, SNIPPET));
    }

    private static String officialOpaqueFixture() {
        return "#version 330 core\n#import <actinium:include/fog.glsl>\nvoid main() {\n"
                + "    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);\n"
                + "    vec3 position = _vert_position + translation;\n"
                + "    v_FragDistance = getFragDistance(u_FogShape, position);\n"
                + "    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);\n"
                + "    vec4 lightColor = _sample_lightmap(u_LightTex, _vert_tex_light_coord);\n}\n";
    }

    private static int occurrences(String value, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }
}
