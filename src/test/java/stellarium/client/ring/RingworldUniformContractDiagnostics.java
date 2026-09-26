package stellarium.client.ring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import com.google.gson.JsonParser;
import stellarium.client.ring.dh.DistantHorizonsCurvatureUniforms;

/** Runs the production uniform binding flow against real linked-shader reflection; never creates a GL context. */
public final class RingworldUniformContractDiagnostics {
    public static void main(String[] args) throws IOException {
        Path reflections = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        var receipt = JsonParser.parseString(Files.readString(reflections.resolve("link-receipt.json"))).getAsJsonArray();
        Set<String> linked = new LinkedHashSet<>();
        for (var element : receipt) {
            var program = element.getAsJsonObject();
            if (program.get("ExitCode").getAsInt() != 0) {
                throw new IllegalStateException("Input shader did not link: " + program.get("Program"));
            }
            linked.add(program.get("Program").getAsString());
        }
        List<String> summary = new ArrayList<>();
        int missingTotal = 0;
        for (var binding : bindings().entrySet()) {
            String name = binding.getKey();
            if (!linked.contains(name)) throw new IllegalArgumentException("No successful shader link for " + name);
            Set<String> active = readActiveUniforms(reflections.resolve(name + ".link.log"));
            Set<String> required = new LinkedHashSet<>();
            // Symbolic locations collect all requests from the actual binding code.
            // They are never used to draw and never count as successful GPU lookup.
            binding.getValue().accept(uniform -> { required.add(uniform); return required.size() - 1; });
            List<String> missing = required.stream().filter(uniform -> !active.contains(uniform)).toList();
            missingTotal += missing.size();
            if (missing.isEmpty()) {
                List<String> linkedNames = List.copyOf(active);
                binding.getValue().accept(linkedNames::indexOf);
            }
            Files.write(output.resolve(name + "-required.txt"), required);
            Files.write(output.resolve(name + "-missing.txt"), missing);
            summary.add(name + ": required=" + required.size() + ", missing=" + missing);
        }
        summary.add("missingTotal=" + missingTotal);
        Files.write(output.resolve("summary.txt"), summary);
        summary.forEach(System.out::println);
        if (missingTotal != 0) throw new IllegalStateException("Production uniform bindings require inactive linked uniforms");
    }

    static Map<String, Consumer<ToIntFunction<String>>> bindings() {
        Map<String, Consumer<ToIntFunction<String>>> bindings = new LinkedHashMap<>();
        bindings.put("board", new RingworldBoardProgram()::bindUniforms);
        bindings.put("board_mesh", new RingworldBoardProgram(true)::bindUniforms);
        bindings.put("cloud", new SSCloudProgram()::bindUniforms);
        bindings.put("horizon", new SSCloudHorizonProgram()::bindUniforms);
        bindings.put("horizon_raw", new SSCloudHorizonProgram()::bindRawUniforms);
        bindings.put("horizon_composite", new SSCloudHorizonProgram()::bindCompositeUniforms);
        bindings.put("spatial_air", new RingworldSpatialAirProgram()::bindUniforms);
        bindings.put("spatial_air_curved", new RingworldSpatialAirProgram(true)::bindUniforms);
        bindings.put("spatial_air_local", new RingworldSpatialAirProgram(true, true)::bindUniforms);
        bindings.put("dh", lookup -> new DistantHorizonsCurvatureUniforms(lookup));
        bindings.put("model", new ProceduralRingModelProgram()::bindUniforms);
        bindings.put("cloud_lod", new CloudLodRasterProgram()::bindUniforms);
        bindings.put("cloud_debug", new CloudDebugProgram()::bindUniforms);
        return bindings;
    }

    private static Set<String> readActiveUniforms(Path file) throws IOException {
        Set<String> uniforms = new LinkedHashSet<>();
        boolean inUniforms = false;
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.equals("Uniform reflection:")) { inUniforms = true; continue; }
            if (!inUniforms) continue;
            if (line.isEmpty() || line.equals("Uniform block reflection:")) break;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IOException("Malformed uniform reflection: " + file + ": " + line);
            uniforms.add(line.substring(0, separator));
        }
        if (uniforms.isEmpty()) throw new IOException("No active uniform reflection in " + file);
        return uniforms;
    }
}
