package stellarium.client.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader;
import stellarium.client.ring.dh.DistantHorizonsShaderSource;
import stellarium.client.ring.dh.DistantHorizonsTerrainShaderPatch;

/** Offline only: emits the exact production sources, including DH's packaged source expansion. */
public final class RingworldCurvatureShaderDiagnostics {
    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        IntBuffer rawDrawBuffers = IntBuffer.allocate(3);
        SSCloudTraceCacheTargets.copyDrawBufferLayout(rawDrawBuffers);
        Files.writeString(output.resolve("horizon_raw.draw-buffers.txt"),
                rawDrawBuffers.get(0) + "\n" + rawDrawBuffers.get(1) + "\n" + rawDrawBuffers.get(2) + "\n");
        for (String program : new String[] {"board", "cloud", "horizon", "horizon_raw", "horizon_composite", "spatial_air", "model", "cloud_lod", "cloud_debug"}) {
            for (String extension : new String[] {"vert", "frag"}) {
                String name = program + "." + extension;
                String resourceName = extension.equals("vert")
                        && (program.equals("horizon_raw") || program.equals("horizon_composite"))
                        ? "horizon.vert" : name;
                if (program.equals("cloud_debug") && extension.equals("vert")) resourceName = "cloud_lod.vert";
                boolean cloud = extension.equals("frag") && !program.equals("board") && !program.equals("model")
                        && !program.equals("cloud_lod") && !program.equals("cloud_debug");
                String source = program.equals("spatial_air") && extension.equals("frag")
                        ? RingworldShaderSource.assembleSpatialAir(read(name), false,
                                RingworldCurvatureShaderDiagnostics::read)
                        : program.equals("horizon_raw") && extension.equals("frag")
                        ? RingworldShaderSource.assembleRawCloudQuery(read(name), RingworldCurvatureShaderDiagnostics::read)
                        : RingworldShaderSource.assemble(read(resourceName), cloud, extension.equals("frag"),
                                RingworldCurvatureShaderDiagnostics::read);
                Files.writeString(output.resolve(name), source);
            }
        }
        Files.writeString(output.resolve("spatial_air_curved.vert"), RingworldShaderSource.assemble(
                read("spatial_air.vert"), false, false, RingworldCurvatureShaderDiagnostics::read));
        Files.writeString(output.resolve("spatial_air_curved.frag"), RingworldShaderSource.assembleSpatialAir(
                read("spatial_air.frag"), true, RingworldCurvatureShaderDiagnostics::read));
        Files.writeString(output.resolve("spatial_air_local.vert"), RingworldShaderSource.assemble(
                read("spatial_air.vert"), false, false, RingworldCurvatureShaderDiagnostics::read));
        Files.writeString(output.resolve("spatial_air_local.frag"), RingworldShaderSource.assemble(
                read("spatial_air_local.frag"), false, true, RingworldCurvatureShaderDiagnostics::read));
        Files.writeString(output.resolve("board_mesh.vert"), RingworldShaderSource.assembleBoardMesh(
                read("board_mesh.vert"), false, RingworldCurvatureShaderDiagnostics::read));
        Files.writeString(output.resolve("board_mesh.frag"), RingworldShaderSource.assembleBoardMesh(
                read("board.frag"), true, RingworldCurvatureShaderDiagnostics::read));
        String path = DistantHorizonsTerrainShaderPatch.VERTEX_PATH;
        Files.writeString(output.resolve("dh.vert"), DistantHorizonsTerrainShaderPatch.patch(path,
                GlShader.loadFile(path, false), DistantHorizonsShaderSource.curvatureSnippet()));
        String fragmentPath = DistantHorizonsTerrainShaderPatch.FRAGMENT_PATH;
        Files.writeString(output.resolve("dh.frag"), DistantHorizonsTerrainShaderPatch.patchFragment(
                fragmentPath, GlShader.loadFile(fragmentPath, false),
                DistantHorizonsShaderSource.ownMediaOcclusionSnippet()));
        System.out.println("Assembled board/cloud/horizon/raw/composite/air and exact DH 3.2.0 terrain shader pairs.");
    }

    private static String read(String name) {
        try (var stream = RingworldCurvatureShaderDiagnostics.class.getResourceAsStream(
                "/assets/stellarium/shaders/ringworld/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing production GLSL: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read production GLSL: " + name, exception);
        }
    }
}
