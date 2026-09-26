package stellarium.client.ring.dh;

import java.util.Objects;

/**
 * Fail-closed source patch for the verified Distant Horizons 3.2.0-b OpenGL terrain vertex shader.
 *
 * <p>The patch is deliberately source- and anchor-specific.  Rewriting a changed upstream shader
 * would risk applying ring curvature after projection or in addition to DH's Earth curve.</p>
 */
public final class DistantHorizonsTerrainShaderPatch {
    /** CurseForge file 8389134: Distant Horizons 3.2.0-b Forge 1.12.2. */
    public static final String VERTEX_PATH = "assets/distanthorizons/shaders/terrain/gl/vert.vert";
    public static final String FRAGMENT_PATH = "assets/distanthorizons/shaders/terrain/gl/frag.frag";
    static final String VERSION_DIRECTIVE = "#version 330 core";
    static final String FRAGMENT_VERSION_DIRECTIVE = "#version 330";
    static final String CURVATURE_ANCHOR = "// apply the earth curvature if needed";
    static final String VARYING_ANCHOR = "flat out uint vTextureTileId;";
    static final String PROJECTION_ANCHOR = "gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);";
    static final String FRAGMENT_INPUT_ANCHOR = "in vec4 gl_FragCoord;";
    static final String FRAGMENT_MAIN_ANCHOR = "void main()\n{\n    fragColor = vertexColor;";
    public static final String SNIPPET_SENTINEL = "/* STELLARSKY_DH_3_2_0_B_CURVATURE_BEGIN */";
    public static final String OCCLUSION_SENTINEL = "/* STELLARSKY_DH_3_2_0_B_OWN_MEDIA_OCCLUSION_BEGIN */";
    static final String APPLICATION = "ssApplyDistantHorizonsCurvature(vertexWorldPos);";
    static final String DISPLAY_VARYING = "vSSDisplayFromEye = vertexWorldPos + uSSDhCameraOffset - uSSEyeRelative;";
    static final String OCCLUSION_APPLICATION = "ssDiscardBehindOwnMedia();";
    static final String LIGHT_ANCHOR = "vertexColor = vec4(texture(uLightMap, vec2(skyLight, blockLight)).xyz, 1.0);";

    private DistantHorizonsTerrainShaderPatch() {
    }

    /**
     * Inserts declarations after GLSL's version directive and bends the camera-relative terrain
     * point immediately before DH's optional Earth curve.  The adapter sets that Earth-curve
     * uniform to zero only while an admitted StellarSky frame is active, so user configuration is
     * neither changed nor double-applied.
     */
    public static String patch(String path, String source, String snippet) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(snippet, "snippet");
        if (!VERTEX_PATH.equals(path)) return source;

        requireExactlyOnce(source, VERSION_DIRECTIVE, "DH GLSL version directive");
        requireExactlyOnce(source, CURVATURE_ANCHOR, "DH Earth-curvature anchor");
        requireExactlyOnce(source, VARYING_ANCHOR, "DH vertex varying anchor");
        requireExactlyOnce(source, PROJECTION_ANCHOR, "DH vertex projection anchor");
        if (count(source, SNIPPET_SENTINEL) != 0) {
            throw new IllegalStateException("Distant Horizons terrain shader is already patched");
        }
        requireExactlyOnce(snippet, SNIPPET_SENTINEL, "StellarSky DH curvature snippet sentinel");

        int versionEnd = source.indexOf(VERSION_DIRECTIVE) + VERSION_DIRECTIVE.length();
        String withDeclarations = source.substring(0, versionEnd) + '\n' + snippet + '\n'
                + source.substring(versionEnd);
        String withVarying = replaceExactlyOnce(withDeclarations, VARYING_ANCHOR,
                VARYING_ANCHOR + '\n' + "out vec3 vSSDisplayFromEye;");
        String withCurvature = replaceExactlyOnce(withVarying, CURVATURE_ANCHOR,
                "vSSDhPhysicalRelative = vertexWorldPos + uSSDhCameraOffset;\n    " + APPLICATION + '\n' + "    " + CURVATURE_ANCHOR);
        withCurvature = replaceExactlyOnce(withCurvature, LIGHT_ANCHOR,
                "vSSDhRawLight = vec2(skyLight, blockLight);\n    vertexColor = uSSDhLightMode == 0 ? "
                        + "vec4(texture(uLightMap, vec2(skyLight, blockLight)).xyz, 1.0) : vec4(1.0);");
        return replaceExactlyOnce(withCurvature, PROJECTION_ANCHOR,
                DISPLAY_VARYING + '\n' + "    " + PROJECTION_ANCHOR);
    }

    /** Exact-source fragment patch; it runs before all DH opaque or transparent color blending. */
    public static String patchFragment(String path, String source, String snippet) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(snippet, "snippet");
        if (!FRAGMENT_PATH.equals(path)) return source;
        requireExactlyOnce(source, FRAGMENT_VERSION_DIRECTIVE, "DH fragment GLSL version directive");
        if (!source.startsWith(FRAGMENT_VERSION_DIRECTIVE + '\n')) {
            throw new IllegalStateException("DH fragment GLSL version directive changed from exact 3.2.0-b source");
        }
        requireExactlyOnce(source, FRAGMENT_INPUT_ANCHOR, "DH fragment input anchor");
        requireExactlyOnce(source, FRAGMENT_MAIN_ANCHOR, "DH fragment main anchor");
        if (count(source, OCCLUSION_SENTINEL) != 0) {
            throw new IllegalStateException("Distant Horizons terrain fragment shader is already patched");
        }
        requireExactlyOnce(snippet, OCCLUSION_SENTINEL, "StellarSky DH own-media snippet sentinel");
        // gl_FragCoord is already a builtin. DH's redundant bare redeclaration would occur
        // after our helper first uses it, which GLSL rejects; remove only that exact anchor.
        source = replaceExactlyOnce(source, FRAGMENT_INPUT_ANCHOR, "");
        int versionEnd = source.indexOf(FRAGMENT_VERSION_DIRECTIVE) + FRAGMENT_VERSION_DIRECTIVE.length();
        String withDeclarations = source.substring(0, versionEnd) + '\n' + snippet + '\n'
                + source.substring(versionEnd);
        return replaceExactlyOnce(withDeclarations, FRAGMENT_MAIN_ANCHOR,
                "void main()\n{\n    " + OCCLUSION_APPLICATION + '\n'
                        + "    fragColor = vertexColor;\n    if (uSSDhLightMode != 0) fragColor.rgb *= ssDhLocalLight();");
    }

    private static String replaceExactlyOnce(String source, String expected, String replacement) {
        int index = source.indexOf(expected);
        if (index < 0 || source.indexOf(expected, index + expected.length()) >= 0) {
            throw new IllegalStateException("Distant Horizons terrain shader changed while applying curvature");
        }
        return source.substring(0, index) + replacement + source.substring(index + expected.length());
    }

    private static void requireExactlyOnce(String source, String token, String description) {
        int occurrences = count(source, token);
        if (occurrences != 1) {
            throw new IllegalStateException(description + " must occur exactly once; found " + occurrences);
        }
    }

    private static int count(String source, String token) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            occurrences++;
            offset += token.length();
        }
        return occurrences;
    }
}
