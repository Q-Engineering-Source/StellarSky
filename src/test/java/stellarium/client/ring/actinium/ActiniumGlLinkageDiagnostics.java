package stellarium.client.ring.actinium;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import com.gtnewhorizons.angelica.loading.fml.transformers.AngelicaRedirectorTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Applies the actual Actinium transformer and resolves emitted GL call descriptors without class initialization. */
public final class ActiniumGlLinkageDiagnostics {
    private static final String GLSM = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private static final Map<String, ClassNode> TYPES = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        var transformer = new AngelicaRedirectorTransformer();
        List<String> failures = new ArrayList<>();
        List<String> inspectedCalls = new ArrayList<>();
        int classes = 0;
        int transformedClasses = 0;
        try (ZipFile archive = new ZipFile(input.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("stellarium/") || !entry.getName().endsWith(".class")) continue;
                byte[] original;
                try (InputStream stream = archive.getInputStream(entry)) { original = stream.readAllBytes(); }
                String name = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                byte[] transformed = transformer.transform(name, name, original);
                classes++;
                if (transformed != original) transformedClasses++;
                ClassNode node = new ClassNode();
                new ClassReader(transformed).accept(node, 0);
                for (var method : node.methods) {
                    String caller = node.name + "." + method.name + method.desc;
                    for (var instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call) {
                            inspect(caller, call.owner, call.name, call.desc,
                                    call.getOpcode() == Opcodes.INVOKESTATIC, inspectedCalls, failures);
                        } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                            for (Object argument : dynamic.bsmArgs) {
                                if (argument instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                                    inspect(caller, handle.getOwner(), handle.getName(), handle.getDesc(),
                                            handle.getTag() == Opcodes.H_INVOKESTATIC, inspectedCalls, failures);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (classes == 0 || inspectedCalls.isEmpty()) {
            throw new IllegalArgumentException("Input has no StellarSky GL call sites: " + input);
        }
        Files.write(output.resolve("calls.txt"), inspectedCalls);
        Files.write(output.resolve("failures.txt"), failures);
        String summary = "input=" + input + "\nclasses=" + classes + "\ntransformedClasses=" + transformedClasses
                + "\ncheckedCalls=" + inspectedCalls.size() + "\nmissingLinks=" + failures.size() + "\n";
        Files.writeString(output.resolve("summary.txt"), summary);
        System.out.print(summary);
        for (String failure : failures) System.out.println(failure);
        if (!failures.isEmpty()) throw new IllegalStateException("Actinium-transformed GL calls contain missing method links");
    }

    private static void inspect(String caller, String owner, String name, String descriptor, boolean staticCall,
                                List<String> inspected, List<String> failures) throws IOException {
        // Ordinary org.lwjgl calls still pass through Cleanroom's later
        // namespace/descriptor adaptation. Audit the links introduced by
        // Actinium, plus our explicit compatibility-binding entrypoints.
        if (!(owner.equals(GLSM) || owner.startsWith("org/lwjglx/opengl/"))) return;
        String call = caller + " -> " + owner + "." + name + descriptor;
        inspected.add(call);
        if (!resolves(owner, name, descriptor, staticCall, new HashSet<>())) failures.add(call);
    }

    private static boolean resolves(String owner, String name, String descriptor, boolean staticCall,
                                    Set<String> visited) throws IOException {
        if (owner == null || !visited.add(owner)) return false;
        ClassNode node = type(owner);
        if (node == null) return false;
        for (var method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)
                    && ((method.access & Opcodes.ACC_STATIC) != 0) == staticCall
                    && (method.access & Opcodes.ACC_PUBLIC) != 0) return true;
        }
        if (resolves(node.superName, name, descriptor, staticCall, visited)) return true;
        for (String parent : node.interfaces) {
            if (resolves(parent, name, descriptor, staticCall, visited)) return true;
        }
        return false;
    }

    private static ClassNode type(String name) throws IOException {
        if (TYPES.containsKey(name)) return TYPES.get(name);
        try (InputStream stream = ActiniumGlLinkageDiagnostics.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) { TYPES.put(name, null); return null; }
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            TYPES.put(name, node);
            return node;
        }
    }
}
