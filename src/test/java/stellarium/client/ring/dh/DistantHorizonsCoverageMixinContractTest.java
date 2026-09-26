package stellarium.client.ring.dh;

import static org.junit.Assert.*;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact bytecode selectors, not a claim that client Mixins or OpenGL have run. */
public class DistantHorizonsCoverageMixinContractTest {
    private static final String SECTION = "com/seibel/distanthorizons/core/render/QuadTree/LodRenderSection";
    private static final String BUFFER = "com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodBufferContainer";
    private static final String SOURCE = "com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2";
    private static final String LEVEL = "com/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper";
    private static final String COLUMN = "com/seibel/distanthorizons/core/dataObjects/render/ColumnRenderSource";

    @Test public void workerAndPrimaryCaptureSelectorsExistExactlyOnce() throws Exception {
        var owner = read(SECTION);
        method(owner, "lambda$uploadRenderDataToGpuAsync$1", "(Ljava/util/concurrent/CompletableFuture;)V");
        var source = method(owner, "getRenderSourceForPos", "(JLcom/seibel/distanthorizons/core/enums/EDhDirection;)L" + COLUMN + ";");
        assertEquals(1, calls(source, "com/seibel/distanthorizons/core/dataObjects/transformers/FullDataToRenderDataTransformer",
                "transformFullDataToRenderSource", "(L" + SOURCE + ";L" + LEVEL + ";)L" + COLUMN + ";"));
    }

    @Test public void uploadHookPrecedesDhPublicationRegistration() throws Exception {
        var upload = method(read(SECTION), "uploadToGpuAsync",
                "(Ljava/util/concurrent/CompletableFuture;Ljava/util/ArrayList;Ljava/util/ArrayList;)Ljava/util/concurrent/CompletableFuture;");
        int create = -1, publication = -1, index = 0;
        for (var instruction : upload.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(BUFFER) && call.name.equals("tryMakeAndUploadBuffersAsync")) create = index;
                if (call.owner.equals("java/util/concurrent/CompletableFuture") && call.name.equals("whenComplete")) publication = index;
            }
            index++;
        }
        assertEquals(1, calls(upload, BUFFER, "tryMakeAndUploadBuffersAsync",
                "(JL" + LEVEL + ";Ljava/util/ArrayList;Ljava/util/ArrayList;)Ljava/util/concurrent/CompletableFuture;"));
        assertTrue(create >= 0 && publication > create);
    }

    @Test public void allRetirementEntrypointsExist() throws Exception {
        method(read(SECTION), "close", "()V");
        method(read(BUFFER), "close", "()V");
        var tree = read("com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree");
        method(tree, "close", "()V");
        method(tree, "clearRenderDataCache", "()V");
    }

    @Test public void actualDrawHasUnambiguousLiveContainerAndVboLocals() throws Exception {
        var owner = read("com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram");
        var render = method(owner, "render", "(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V");
        var analyzer = new Analyzer<>(new BasicInterpreter(Opcodes.ASM9) {
            @Override public BasicValue newValue(Type type) {
                if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) return new BasicValue(type);
                return super.newValue(type);
            }
        });
        var frames = analyzer.analyze(owner.name, render);
        assertEquals(1, calls(render, "org/lwjgl/opengl/GL33", "glDrawElements", "(IIIJ)V"));
        for (int i = 0; i < render.instructions.size(); i++) {
            if (render.instructions.get(i) instanceof MethodInsnNode call && call.name.equals("glDrawElements")) {
                int containers = 0, vbos = 0;
                for (int local = 0; local < frames[i].getLocals(); local++) {
                    var type = frames[i].getLocal(local).getType();
                    if (type == null || type.getSort() != Type.OBJECT) continue;
                    if (type.getInternalName().equals(BUFFER)) containers++;
                    if (type.getInternalName().equals("com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLVertexBuffer")) vbos++;
                }
                assertEquals(1, containers); assertEquals(1, vbos);
            }
        }
    }

    @Test public void copyAcknowledgementIsAfterQuadNotEarlyReturn() throws Exception {
        var copy = read("com/seibel/distanthorizons/common/render/openGl/postProcessing/copy/GlDhCopyShader");
        String quad = "com/seibel/distanthorizons/common/render/openGl/postProcessing/GlScreenQuad";
        assertEquals(1, calls(method(copy, "renderToFrameBuffer", "()V"), quad, "render", "()V"));
        assertEquals(1, calls(method(copy, "renderToMcTexture", "()V"), quad, "render", "()V"));
        assertEquals(1, calls(method(read(quad), "render", "()V"), "org/lwjgl/opengl/GL33", "glDrawArrays", "(III)V"));
        method(read("com/seibel/distanthorizons/core/render/renderer/LodRenderer"), "renderTerrain",
                "(Lcom/seibel/distanthorizons/core/render/RenderParams;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V");
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = DistantHorizonsCoverageMixinContractTest.class.getResourceAsStream('/' + name + ".class")) {
            assertNotNull(name, input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG);
            return node;
        }
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        var matches = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        assertEquals(node.name + '#' + name + descriptor, 1, matches.size());
        return matches.getFirst();
    }
    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        }
        return count;
    }
}
