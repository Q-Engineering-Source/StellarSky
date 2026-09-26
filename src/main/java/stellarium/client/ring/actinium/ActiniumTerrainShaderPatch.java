package stellarium.client.ring.actinium;

import java.util.Objects;

/**
 * Exact-source patch for the official Actinium alpha-0.0.8 opaque terrain vertex shader.
 *
 * <p>This class deliberately has no Actinium class reference and no resource or GL access.  The
 * caller supplies the already-loaded source and StellarSky-owned snippet at Actinium's verified
 * shader transformation seam.  Any upstream source drift fails closed instead of rewriting an
 * unknown shader.</p>
 */
public final class ActiniumTerrainShaderPatch {
    /** Official alpha-0.0.8 shader resource path, verified at commit 7e0128b. */
    public static final String OPAQUE_VERTEX_PATH = "actinium:blocks/block_layer_opaque.vsh";

    static final String VERSION_DIRECTIVE = "#version 330 core";
    static final String POSITION_ANCHOR = "vec3 position = _vert_position + translation;";
    static final String SNIPPET_SENTINEL = "/* STELLARSKY_ACTINIUM_C25_CURVATURE_BEGIN */";

    private ActiniumTerrainShaderPatch() {
    }

    /**
     * Patches only the alpha-0.0.8 opaque terrain vertex source.
     *
     * <p>Unknown resources are returned as the same {@link String} instance without inspecting
     * their source.  The supported resource requires exactly one GLSL version directive and one
     * draw-position anchor; repeated or already-patched input is an integration error, not an
     * opportunity for a best-effort rewrite.</p>
     */
    public static String patch(String path, String source, String snippet) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(snippet, "snippet");
        if (!OPAQUE_VERTEX_PATH.equals(path)) {
            return source;
        }
        requireExactlyOnce(source, VERSION_DIRECTIVE, "GLSL version directive");
        requireExactlyOnce(source, POSITION_ANCHOR, "Actinium terrain position anchor");
        if (count(source, SNIPPET_SENTINEL) != 0) {
            throw new IllegalStateException("Actinium terrain shader is already patched");
        }
        requireExactlyOnce(snippet, SNIPPET_SENTINEL, "StellarSky curvature snippet sentinel");

        int versionEnd = source.indexOf(VERSION_DIRECTIVE) + VERSION_DIRECTIVE.length();
        String withDeclarations = source.substring(0, versionEnd) + '\n' + snippet + '\n'
                + source.substring(versionEnd);
        String replacement = POSITION_ANCHOR + '\n'
                + "#ifndef CELERITAS_NO_LIGHTMAP\n"
                + "    _vert_tex_light_coord = ssActiniumLocalLight(_vert_tex_light_coord, position, u_ModelViewMatrix);\n"
                + "#endif\n"
                + "    position = ssApplyRingworldCurvature(position, u_ModelViewMatrix);";
        return replaceExactlyOnce(withDeclarations, POSITION_ANCHOR, replacement);
    }

    private static String replaceExactlyOnce(String source, String expected, String replacement) {
        int index = source.indexOf(expected);
        if (index < 0 || source.indexOf(expected, index + expected.length()) >= 0) {
            throw new IllegalStateException("Actinium terrain shader changed while applying its exact patch");
        }
        return source.substring(0, index) + replacement + source.substring(index + expected.length());
    }

    private static void requireExactlyOnce(String source, String token, String description) {
        int count = count(source, token);
        if (count != 1) {
            throw new IllegalStateException(description + " must occur exactly once; found " + count);
        }
    }

    private static int count(String source, String token) {
        int occurrences = 0;
        int offset = 0;
        while (true) {
            int next = source.indexOf(token, offset);
            if (next < 0) {
                return occurrences;
            }
            occurrences++;
            offset = next + token.length();
        }
    }
}
