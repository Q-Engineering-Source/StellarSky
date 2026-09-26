package stellarium.client.ring.dh;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the two StellarSky-owned GLSL snippets that form DH's exact vertex patch. */
public final class DistantHorizonsShaderSource {
    private static final String CURVED_RAY = "/assets/stellarium/shaders/ringworld/curvature_ray.glsl";
    private static final String DH_BRIDGE = "/assets/stellarium/shaders/ringworld/dh_curvature.glsl";
    private static final String DH_OCCLUSION = "/assets/stellarium/shaders/ringworld/dh_own_media_occlusion.glsl";

    private DistantHorizonsShaderSource() {
    }

    public static String curvatureSnippet() {
        String ray = read(CURVED_RAY);
        String bridge = read(DH_BRIDGE);
        if (!ray.contains("dvec3 ssCurvedDisplayPoint(dvec3 physicalRelative)")
                || !bridge.contains(DistantHorizonsTerrainShaderPatch.SNIPPET_SENTINEL)) {
            throw new IllegalStateException("StellarSky's Distant Horizons curvature shader contract is incomplete");
        }
        return ray + '\n' + bridge;
    }

    public static String ownMediaOcclusionSnippet() {
        String source = read(DH_OCCLUSION);
        if (!source.contains(DistantHorizonsTerrainShaderPatch.OCCLUSION_SENTINEL)) {
            throw new IllegalStateException("StellarSky's Distant Horizons own-media shader contract is incomplete");
        }
        return source + '\n' + read("/assets/stellarium/shaders/ringworld/dh_local_light.glsl");
    }

    private static String read(String resource) {
        try (InputStream stream = DistantHorizonsShaderSource.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing StellarSky curvature shader: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read StellarSky curvature shader: " + resource, exception);
        }
    }
}
