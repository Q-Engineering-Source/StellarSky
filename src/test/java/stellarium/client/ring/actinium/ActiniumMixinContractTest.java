package stellarium.client.ring.actinium;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Compiled ABI audit against the actual official release; deliberately not a client Mixin-apply claim. */
public class ActiniumMixinContractTest {
    @Test public void everyRegisteredSelectorAndCapturedArgumentMatchesTheOfficialReleaseBytecode() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mixins.stellarsky.actinium.json")) {
            assertNotNull(stream);
            var config = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("required").getAsBoolean());
            assertEquals(0, config.getAsJsonArray("mixins").size());
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var entry : config.getAsJsonArray("client")) {
                ClassNode mixin = read(prefix + entry.getAsString());
                var mixinAnnotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                        .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
                assertEquals(Boolean.FALSE, value(mixinAnnotation, "remap"));
                var targets = (List<?>) value(mixinAnnotation, "targets");
                assertEquals(1, targets.size());
                ClassNode target = read(targets.getFirst().toString().replace('.', '/'));
                int audited = 0;
                for (MethodNode handler : mixin.methods) {
                    for (AnnotationNode annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                        if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                        List<?> selectors = (List<?>) value(annotation, "method");
                        var at = (AnnotationNode) ((List<?>) value(annotation, "at")).getFirst();
                        String location = (String) value(at, "value");
                        int injectionSites = 0;
                        for (var selector : selectors) {
                            var candidates = target.methods.stream()
                                    .filter(m -> (m.name + m.desc).equals(selector)).toList();
                            assertEquals(target.name + "#" + selector, 1, candidates.size());
                            MethodNode method = candidates.getFirst();
                            if (location.equals("RETURN")) {
                                for (var instruction : method.instructions) {
                                    int opcode = instruction.getOpcode();
                                    if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) injectionSites++;
                                }
                            } else {
                                assertTrue(location, location.equals("HEAD") || location.equals("TAIL"));
                                injectionSites++;
                            }
                            assertEquals("static handler mismatch", (method.access & Opcodes.ACC_STATIC) != 0,
                                    (handler.access & Opcodes.ACC_STATIC) != 0);
                            Type[] captured = Type.getArgumentTypes(handler.desc);
                            Type[] arguments = Type.getArgumentTypes(method.desc);
                            // Mixin permits callback-only injection or the full target argument prefix.
                            assertTrue(handler.name, captured.length == 1 || captured.length == arguments.length + 1);
                            if (captured.length > 1) for (int i = 0; i < arguments.length; i++) {
                                assertEquals(handler.name + " argument " + i, arguments[i], captured[i]);
                            }
                            audited++;
                        }
                        assertEquals(handler.name + " required sites", injectionSites, value(annotation, "require"));
                        assertEquals(handler.name + " allowed sites", injectionSites, value(annotation, "allow"));
                    }
                }
                assertTrue(mixin.name + " has no audited selectors", audited > 0);
            }
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = ActiniumMixinContractTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull("Missing class " + name, stream);
            ClassNode result = new ClassNode();
            new ClassReader(stream).accept(result, ClassReader.SKIP_DEBUG);
            return result;
        }
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation field " + key);
    }
}
