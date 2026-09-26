package stellarium.client.ring.actinium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.embeddedt.embeddium.impl.gl.shader.ShaderConstants;
import org.embeddedt.embeddium.impl.gl.shader.ShaderParser;
import org.embeddedt.embeddium.impl.render.shader.ShaderLoader;

/** Offline diagnostic entry point: uses the production patch and official release's actual parser/resources. */
public final class ActiniumShaderAssembly {
    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        String snippet = ActiniumTerrainShaderSource.snippet();
        String vertexPath = ActiniumTerrainShaderPatch.OPAQUE_VERTEX_PATH;
        String patched = ActiniumTerrainShaderPatch.patch(vertexPath, ShaderLoader.getShaderSource(vertexPath), snippet);
        for (int i = 0; i < 64; i++) {
            var defines = ShaderConstants.builder();
            if ((i & 1) != 0) defines.add("USE_FRAGMENT_DISCARD");
            if ((i & 2) != 0) defines.add("USE_BILINEAR_CORRECTION");
            if ((i & 4) != 0) defines.add("CELERITAS_NO_LIGHTMAP");
            if ((i & 8) != 0) {
                defines.add("USE_VERTEX_COMPRESSION");
                defines.add("VERT_POS_SCALE", "0.00048828125");
                defines.add("VERT_POS_OFFSET", "-8.0");
                defines.add("VERT_TEX_SCALE", "0.000030517578125");
            }
            int fog = i >> 4;
            if (fog != 0) {
                defines.add("USE_FOG");
                defines.add(switch (fog) {
                    case 1 -> "USE_FOG_SMOOTH";
                    case 2 -> "USE_FOG_EXP2";
                    default -> "USE_FOG_POSTMODERN";
                });
                defines.add("CHUNK_FADE_IN_DURATION_MS", "200");
            }
            var constants = defines.build();
            Files.writeString(output.resolve("terrain-" + i + ".vert"),
                    ShaderParser.parseShader(patched, ShaderLoader::getShaderSource, constants));
            Files.writeString(output.resolve("terrain-" + i + ".frag"), ShaderParser.parseShader(
                    ShaderLoader.getShaderSource("actinium:blocks/block_layer_opaque.fsh"),
                    ShaderLoader::getShaderSource, constants));
        }
        System.out.println("Assembled 64 native terrain shader pairs from the official Actinium release.");
    }
}
