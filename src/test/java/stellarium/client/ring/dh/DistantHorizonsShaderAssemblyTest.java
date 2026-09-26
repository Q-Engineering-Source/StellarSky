package stellarium.client.ring.dh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader;
import org.junit.Test;

/** Assembles against the exact packaged DH 3.2.0-b resource without creating a GL context. */
public class DistantHorizonsShaderAssemblyTest {
    @Test public void exactPackagedTerrainShadersAcceptOneFailClosedCurvatureAndOwnMediaInsertion() {
        String upstream = GlShader.loadFile(DistantHorizonsTerrainShaderPatch.VERTEX_PATH, false);
        String patched = DistantHorizonsTerrainShaderPatch.patch(DistantHorizonsTerrainShaderPatch.VERTEX_PATH,
                upstream, DistantHorizonsShaderSource.curvatureSnippet());

        int declaration = patched.indexOf(DistantHorizonsTerrainShaderPatch.SNIPPET_SENTINEL);
        int application = patched.indexOf(DistantHorizonsTerrainShaderPatch.APPLICATION);
        int earth = patched.indexOf(DistantHorizonsTerrainShaderPatch.CURVATURE_ANCHOR);
        int projection = patched.indexOf("gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);");
        assertTrue(declaration > patched.indexOf(DistantHorizonsTerrainShaderPatch.VERSION_DIRECTIVE));
        assertTrue(application > declaration && earth > application && projection > earth);
        assertEquals(1, occurrences(patched, DistantHorizonsTerrainShaderPatch.SNIPPET_SENTINEL));
        assertEquals(1, occurrences(patched, "uniform vec3 uSSDhCameraOffset;"));
        assertEquals(1, occurrences(patched, "out vec3 vSSDisplayFromEye;"));

        String fragment = GlShader.loadFile(DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH, false);
        String patchedFragment = DistantHorizonsTerrainShaderPatch.patchFragment(
                DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH, fragment,
                DistantHorizonsShaderSource.ownMediaOcclusionSnippet());
        int discard = patchedFragment.indexOf(DistantHorizonsTerrainShaderPatch.OCCLUSION_APPLICATION);
        int color = patchedFragment.indexOf("fragColor = vertexColor;");
        assertTrue(discard > 0 && color > discard);
        assertEquals(1, occurrences(patchedFragment, DistantHorizonsTerrainShaderPatch.OCCLUSION_SENTINEL));
        assertEquals(1, occurrences(patchedFragment, "uniform sampler2D uSSOwnMediaDistance;"));
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
