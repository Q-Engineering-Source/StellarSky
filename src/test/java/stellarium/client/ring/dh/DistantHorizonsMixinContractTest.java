package stellarium.client.ring.dh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;

/** ABI audit against the exact 3.2.0-b test-runtime artifact; it is not a GL/client acceptance claim. */
public class DistantHorizonsMixinContractTest {
    private static final String GL_SHADER = "com/seibel/distanthorizons/common/render/openGl/glObject/shader/GlShader";
    private static final String TERRAIN_PROGRAM = "com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram";
    private static final String BUFFER_HANDLER = "com/seibel/distanthorizons/core/render/RenderBufferHandler";
    private static final String LOD_RENDERER = "com/seibel/distanthorizons/core/render/renderer/LodRenderer";

    @Test public void exactDistantHorizons320bSelectorsRetainTheirRequiredInjectionCounts() throws Exception {
        ClassNode shader = read(GL_SHADER);
        assertEquals(1, returns(method(shader, "loadFile", "(Ljava/lang/String;Z)Ljava/lang/String;")));

        ClassNode terrain = read(TERRAIN_PROGRAM);
        assertEquals(2, returns(method(terrain, "tryInit", "()V")));
        assertEquals(1, returns(method(terrain, "fillUniformData",
                "(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;)V")));
        assertEquals(1, returns(method(terrain, "render",
                "(Lcom/seibel/distanthorizons/core/render/RenderParams;Z"
                        + "Lcom/seibel/distanthorizons/core/util/objects/SortedArraySet;"
                        + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V")));

        MethodNode culling = method(read(BUFFER_HANDLER), "buildRenderList",
                "(Lcom/seibel/distanthorizons/core/render/RenderParams;)V");
        assertEquals(1, invocations(culling,
                "com/seibel/distanthorizons/api/interfaces/override/rendering/IDhApiCullingFrustum",
                "intersects", "(IIII)Z"));

        MethodNode fog = method(read(LOD_RENDERER), "renderTerrain",
                "(Lcom/seibel/distanthorizons/core/render/RenderParams;"
                        + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V");
        assertEquals(2, invocations(fog,
                "com/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhFogRenderer",
                "render", "(Lcom/seibel/distanthorizons/core/render/RenderParams;"
                        + "Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiFogRenderParam;)V"));
    }

    @Test public void exactlyOneVersionSpecificDepthCopySelectorMatches() throws Exception {
        ClassNode meta = read("com/seibel/distanthorizons/common/render/openGl/GlDhMetaRenderer");
        int count = 0;
        for (MethodNode method : meta.methods) {
            if ((method.name.equals("applyToMcTexture") || method.name.equals("copyToMcTexture"))
                    && method.desc.equals("(Lcom/seibel/distanthorizons/core/render/RenderParams;)V")) count += returns(method);
        }
        assertEquals(1, count);
        assertNotNull(method(meta, "getActiveDepthTextureId", "()I"));
    }

    @Test public void temporalJitterAndResolveReadTheSameOptionalEntry() throws Exception {
        String owner = "com/seibel/distanthorizons/core/config/Config$Client$Advanced$Graphics";
        ClassNode config = read(owner);
        var field = config.fields.stream().filter(f -> f.name.equals("enableAntiAliasing")).findFirst();
        if (field.isEmpty()) {
            assertNotNull(method(read("com/seibel/distanthorizons/common/render/openGl/GlDhMetaRenderer"),
                    "applyToMcTexture", "(Lcom/seibel/distanthorizons/core/render/RenderParams;)V"));
            return;
        }
        assertEquals("Lcom/seibel/distanthorizons/core/config/types/ConfigEntry;", field.get().desc);
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.get().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        assertEquals(1, returns(method(config, "<clinit>", "()V")));
        MethodNode uniforms = method(read(TERRAIN_PROGRAM), "fillUniformData",
                "(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;)V");
        MethodNode terrain = method(read(LOD_RENDERER), "renderTerrain",
                "(Lcom/seibel/distanthorizons/core/render/RenderParams;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V");
        for (MethodNode consumer : new MethodNode[]{uniforms, terrain}) {
            int gets = 0;
            for (var instruction : consumer.instructions) {
                if (instruction instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                        && f.owner.equals(owner) && f.name.equals("enableAntiAliasing")) {
                    var next = instruction.getNext();
                    while (next != null && next.getOpcode() < 0) next = next.getNext();
                    if (next instanceof MethodInsnNode call && call.owner.equals("com/seibel/distanthorizons/core/config/types/ConfigEntry")
                            && call.name.equals("get") && call.desc.equals("()Ljava/lang/Object;")) gets++;
                }
            }
            assertEquals(consumer.name, 1, gets);
        }
        assertEquals(1, invocations(terrain, "com/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhAntiAliasRenderer",
                "render", "(Lcom/seibel/distanthorizons/core/render/RenderParams;)V"));
    }

    @Test public void irisInitializerAndAddedInterfaceMethodsMatchTheCompatibilityAdapter() throws Exception {
        ClassNode main = read("com/seibel/distanthorizons/cleanroom/CleanroomMain");
        MethodNode init = method(main, "initializeModCompat", "()V");
        assertEquals(1, returns(init));
        var field = main.fields.stream().filter(f -> f.name.equals("IRIS_ACCESSOR")).findFirst();
        if (field.isEmpty()) {
            assertEquals(1, init.instructions.size()); // DH 3.2: empty initializer.
            return;
        }
        assertEquals("Lcom/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IIrisAccessor;", field.get().desc);
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field.get().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        assertEquals(1, invocations(init, main.name, "tryCreateModCompatAccessor",
                "(Ljava/lang/String;Ljava/lang/Class;Ljava/util/function/Supplier;)V"));
        ClassNode adapter = read("stellarium/client/ring/dh/DistantHorizonsIrisBinding$CompatibleAccessor");
        ClassNode parent = read(adapter.superName);
        ClassNode contract = read("com/seibel/distanthorizons/core/wrapperInterfaces/modAccessor/IIrisAccessor");
        for (MethodNode required : contract.methods) {
            if ((required.access & Opcodes.ACC_ABSTRACT) == 0) continue;
            var implementation = java.util.stream.Stream.concat(adapter.methods.stream(), parent.methods.stream())
                    .filter(m -> m.name.equals(required.name) && m.desc.equals(required.desc))
                    .findFirst().orElseThrow(() -> new AssertionError("Unimplemented DH interface method " + required.name));
            assertEquals(0, implementation.access & Opcodes.ACC_ABSTRACT);
        }
    }

    private static ClassNode read(String internalName) throws Exception {
        try (InputStream stream = DistantHorizonsMixinContractTest.class.getResourceAsStream('/' + internalName + ".class")) {
            assertNotNull("Missing exact DH test-runtime class " + internalName, stream);
            ClassNode result = new ClassNode();
            new ClassReader(stream).accept(result, ClassReader.SKIP_DEBUG);
            return result;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError("Missing DH selector " + owner.name + '#' + name + descriptor));
    }

    private static int returns(MethodNode method) {
        int count = 0;
        for (var instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) count++;
        }
        return count;
    }

    private static int invocations(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(owner)
                    && invoke.name.equals(name) && invoke.desc.equals(descriptor)) {
                count++;
            }
        }
        return count;
    }
}
