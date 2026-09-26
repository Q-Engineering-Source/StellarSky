package stellarium.client.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.gtnewhorizons.angelica.glsm.CompatShaderTransformer;
import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;

/** Exports actual catalogue shader inputs after Actinium's source interception, without a GL context. */
public final class ActiniumCompatShaderDiagnostics {
    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        int minimumVersion = BackendManager.RENDER_BACKEND.getMinGLSLVersion();
        System.out.println("Actinium shader backend minimum GLSL: " + minimumVersion);
        String[] legacyPrograms = {"atmosphere/atmosphere_single", "atmosphere/atmosphere_extinction",
                "atmosphere/atmosphere_refraction", "postprocess/scope", "postprocess/sky_to_queried",
                "postprocess/hdr_to_ldr", "postprocess/linear_to_srgb", "point", "textured", "extended_star"};
        for (String program : legacyPrograms) {
            write(output, program.replace('/', '_'), "vert", read(program + ".vsh"), minimumVersion);
            write(output, program.replace('/', '_'), "frag", read(program + ".psh"), minimumVersion);
        }
        Path raw = output.resolve("raw-ringworld");
        RingworldCurvatureShaderDiagnostics.main(new String[] {raw.toString()});
        Files.copy(raw.resolve("horizon_raw.draw-buffers.txt"), output.resolve("horizon_raw.draw-buffers.txt"),
                StandardCopyOption.REPLACE_EXISTING);
        for (String program : new String[] {"board", "board_mesh", "cloud", "horizon", "horizon_raw",
                "horizon_composite", "spatial_air", "spatial_air_curved", "spatial_air_local", "model", "cloud_lod", "cloud_debug", "dh"}) {
            for (String stage : new String[] {"vert", "frag"}) {
                write(output, program, stage, Files.readString(raw.resolve(program + "." + stage)), minimumVersion);
            }
        }
        System.out.println("Exported 23 actual catalogue shader pairs after Actinium compatibility transformation.");
    }

    private static void write(Path output, String program, String stage, String source, int minimumVersion)
            throws IOException {
        // Same pure-source operations and order as GLStateManager.glShaderSource when FFP emulation is enabled.
        String renamed = GlslTransformUtils.renameReservedWords(source, minimumVersion);
        String transformed = CompatShaderTransformer.transform(renamed, stage.equals("frag"));
        Files.writeString(output.resolve(program + "." + stage), transformed, StandardCharsets.UTF_8);
    }

    private static String read(String name) throws IOException {
        try (var stream = ActiniumCompatShaderDiagnostics.class.getResourceAsStream(
                "/assets/stellarium/shaders/" + name)) {
            if (stream == null) throw new IOException("Missing production shader: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
