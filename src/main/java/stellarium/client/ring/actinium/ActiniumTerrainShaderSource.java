package stellarium.client.ring.actinium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shares DH's tested physical shadow kernel without copying its lighting algorithm. */
public final class ActiniumTerrainShaderSource {
    private ActiniumTerrainShaderSource() {}

    public static String snippet() {
        String dh = read("dh_local_light.glsl");
        int start = dh.indexOf("double ssDhSkySubtraction(");
        int end = dh.indexOf("vec3 ssDhLocalLight()");
        if (start < 0 || end <= start) throw new IllegalStateException("Missing shared shadow kernel");
        return read("actinium_curvature.glsl") + '\n' + dh.substring(start, end)
                + read("actinium_local_light.glsl");
    }

    private static String read(String name) {
        String path = "/assets/stellarium/shaders/ringworld/" + name;
        try (var stream = ActiniumTerrainShaderSource.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing terrain shader " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot read terrain shader " + path, cause);
        }
    }
}
