package stellarium.client.ring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.apache.commons.io.IOUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import stellarium.client.ring.cloud.CloudRayBudget;
import stellarium.client.ring.cloud.CloudLodLayout;

/** Small resource loader for ringworld GLSL sources and the shared cloud-query include. */
final class RingworldShaderSource {
    private static final ResourceLocation CLOUD_QUERY =
            new ResourceLocation("stellarium", "shaders/ringworld/cloud_query.glsl");
    private static final String CLOUD_QUERY_INCLUDE = "#include \"cloud_query.glsl\"";
    private static final ResourceLocation CLOUD_STYLE =
            new ResourceLocation("stellarium", "shaders/ringworld/cloud_style.glsl");
    private static final String CLOUD_STYLE_INCLUDE = "#include \"cloud_style.glsl\"";

    private RingworldShaderSource() {
    }

    static byte[] read(ResourceLocation location) {
        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(location);
             InputStream stream = resource.getInputStream()) {
            return IOUtils.toByteArray(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read ringworld shader " + location, exception);
        }
    }

    static byte[] readWithCloudQuery(ResourceLocation location) {
        String source = new String(read(location), StandardCharsets.UTF_8);
        if (!source.contains(CLOUD_QUERY_INCLUDE)) {
            throw new IllegalStateException("Ringworld cloud shader does not include the shared cloud query: " + location);
        }
        return assembleRuntime(source, true, location);
    }

    /** Raw cache trace has a literal style specialization before Actinium sees declarations. */
    static byte[] readWithRawCloudQuery(ResourceLocation location) {
        String source = new String(read(location), StandardCharsets.UTF_8);
        if (!source.contains(CLOUD_QUERY_INCLUDE)) {
            throw new IllegalStateException("Raw ringworld cloud shader does not include the shared cloud query: " + location);
        }
        return assembleRawCloudQuery(source, name -> new String(read(new ResourceLocation("stellarium",
                "shaders/ringworld/" + name)), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] readWithCloudStyle(ResourceLocation location) {
        String source = new String(read(location), StandardCharsets.UTF_8);
        if (!source.contains(CLOUD_STYLE_INCLUDE)) {
            throw new IllegalStateException("Ringworld cloud shader lacks shared style include: " + location);
        }
        return assembleRuntime(source, true, location);
    }

    /** Cloud constants without importing the full cloud tracer. */
    static byte[] readCloudComposite(ResourceLocation location) {
        return assembleRuntime(new String(read(location), StandardCharsets.UTF_8), true, location);
    }

    static byte[] readCurved(ResourceLocation location) {
        return assembleRuntime(new String(read(location), StandardCharsets.UTF_8), false, location);
    }

    static byte[] readBoardMesh(ResourceLocation location) {
        return assembleBoardMesh(new String(read(location), StandardCharsets.UTF_8),
                location.getPath().endsWith(".frag"), name -> new String(read(new ResourceLocation(
                        "stellarium", "shaders/ringworld/" + name)), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
    }

    static String assembleBoardMesh(String source, boolean fragment, Function<String, String> resources) {
        // Actinium separates macro definitions from the source before its parser
        // evaluates conditional branches. A literal branch survives that stage.
        return assemble(source.replace("#ifdef SS_BOARD_MESH", "#if 1"), false, fragment, resources);
    }

    static byte[] readSpatialAir(ResourceLocation location, boolean curvedOnly) {
        return assembleSpatialAir(new String(read(location), StandardCharsets.UTF_8), curvedOnly,
                name -> new String(read(new ResourceLocation("stellarium", "shaders/ringworld/" + name)),
                        StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    static String assembleSpatialAir(String source, boolean curvedOnly, Function<String, String> resources) {
        // A literal specialization survives Actinium's macro-preamble split.
        // The curved branch consumes already captured opaque distances; carrying
        // a complete legacy cloud tracer into that program serves no rendering role.
        String specialized = source.replace("#ifdef SS_AIR_CURVED_ONLY", curvedOnly ? "#if 1" : "#if 0")
                .replace("#ifndef SS_AIR_CURVED_ONLY", curvedOnly ? "#if 0" : "#if 1");
        if (curvedOnly) specialized = specialized.replace(CLOUD_QUERY_INCLUDE, "");
        // Retain the Java-owned numeric sentinel defines in both variants.
        return assemble(specialized, true, true, resources);
    }

    static String assembleRawCloudQuery(String source, Function<String, String> resources) {
        // Macro definitions are separated from declarations by Actinium. A
        // literal branch keeps the raw-only gl_FragCoord offset style intact.
        return assemble(source, true, true, name -> name.equals("cloud_query.glsl")
                ? resources.apply(name).replace("#ifdef SS_CLOUD_RAW_TRACE", "#if 1")
                : resources.apply(name));
    }

    private static byte[] assembleRuntime(String source, boolean cloud, ResourceLocation location) {
        return assemble(source, cloud, location.getPath().endsWith(".frag"), name -> new String(read(new ResourceLocation("stellarium",
                "shaders/ringworld/" + name)), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    /** Same source assembly for runtime resource packs and the offline compiler harness. */
    static String assemble(String source, boolean cloud, boolean fragment, Function<String, String> resources) {
        int versionEnd = source.indexOf('\n');
        if (versionEnd < 0 || !(source.substring(0, versionEnd).trim().equals("#version 120")
                || source.substring(0, versionEnd).trim().equals("#version 400 compatibility"))) {
            throw new IllegalStateException("Unexpected ringworld GLSL version directive");
        }
        String body = source.substring(versionEnd + 1);
        if(body.contains("#include \"dh_shadow_kernel\"")) {
            String shared=resources.apply("dh_local_light.glsl");
            int start=shared.indexOf("double ssDhSkySubtraction("),end=shared.indexOf("vec3 ssDhLocalLight()");
            if(start<0||end<=start)throw new IllegalStateException("Missing shared terrain shadow kernel");
            body=body.replace("#include \"dh_shadow_kernel\"",shared.substring(start,end));
        }
        String[] includes = {"cloud_query.glsl", "cloud_style.glsl", "cloud_style_raw.glsl",
                "cloud_composite_style.glsl", "board_query.glsl"};
        for (int iteration = 0; iteration < 4; iteration++) {
            boolean expanded = false;
            for (String name : includes) {
                String directive = "#include \"" + name + "\"";
                if (body.contains(directive)) {
                    body = body.replace(directive, resources.apply(name));
                    expanded = true;
                }
            }
            if (!expanded) break;
        }
        if (body.contains("#include")) throw new IllegalStateException("Unresolved or recursive ringworld GLSL include");
        String defines = cloud ? "#define SS_CLOUD_MAX_STEPS " + CloudRayBudget.MAX_STEPS + "\n"
                + CloudLodLayout.glslDefines() : "";
        // Actinium's core-profile transformer requires all macros before GLSL
        // declarations; keeping this preamble intact also preserves FP64 setup.
        return "#version 400 compatibility\n" + defines + resources.apply("curvature_ray.glsl") + "\n"
                + (fragment ? resources.apply("distant_depth.glsl") + "\n"
                + resources.apply("own_media.glsl") + "\n"
                + resources.apply("board_depth.glsl") + "\n" : "") + body;
    }
}
