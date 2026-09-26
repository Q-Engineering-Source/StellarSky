package stellarium.client.ring;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The late ground settle's sampling scope.
 *
 * <p>{@code settleDeferredGround} enables the DH-depth and selected-board uniforms
 * through {@code program.use(...)}, which points the shader at reserved texture units
 * three and five/six. The early world pass borrows those textures only inside its own
 * try block, and the cloud renderer's selected-board borrow ends with its own call, so
 * the late settle must borrow both again for the draw it actually samples in.</p>
 *
 * <p>This reads the compiled renderer instead of its source text and pins the binding
 * order against the program that consumes it. It is a <em>structural contract check
 * only</em>: it does not open a GL context, does not run Mixin weaving, and says nothing
 * about the sampled distances or the resulting pixels.</p>
 */
public class RingworldDeferredGroundBindingScopeTest {
    private static final String RENDERER = "stellarium/client/ring/ProceduralRingModelRenderer";
    private static final String SETTLE = "settleDeferredGround";
    private static final String SETTLE_DESCRIPTOR = "(Lstellarium/client/ring/RingworldCurvatureFrame;)Z";
    private static final String DH_UNIFORMS = "stellarium/client/ring/RingworldDistantDepthUniforms";
    private static final String DH_BINDING = "stellarium/client/ring/RingworldDistantDepthUniforms$TextureBinding";
    private static final String BOARD_DISTANCE = "stellarium/client/ring/RingworldBoardMeshDistance";
    private static final String BOARD_BINDING = "stellarium/client/ring/RingworldBoardMeshDistance$SelectedBinding";
    private static final String OWN_MEDIA_UNIFORMS = "stellarium/client/ring/RingworldOwnMediaDepthUniforms";
    private static final String PROGRAM = "stellarium/client/ring/ProceduralRingModelProgram";
    private static final String MESH_DISTANCE = "stellarium/client/ring/RingworldMeshDistance";

    @Test
    public void settleBorrowsDhDepthAndSelectedBoardBeforeTheProgramSamplesThem() throws Exception {
        MethodNode settle = settle();
        int dh = callIndex(settle, DH_UNIFORMS, "bindTexture");
        int board = callIndex(settle, BOARD_DISTANCE, "bindSelected");
        int use = callIndex(settle, PROGRAM, "use");
        assertTrue("the settle must borrow this pass's DH depth texture", dh >= 0);
        assertTrue("the settle must borrow the selected board textures", board >= 0);
        assertTrue("the settle must run the model program", use >= 0);
        assertTrue("the DH depth borrow must precede program.use", dh < use);
        assertTrue("the selected board borrow must precede program.use", board < use);
    }

    @Test
    public void settleKeepsItsOwnMediaBorrowAndEnclosesTheDraw() throws Exception {
        MethodNode settle = settle();
        int ownMedia = callIndex(settle, OWN_MEDIA_UNIFORMS, "bindTextureOrNull");
        int dh = callIndex(settle, DH_UNIFORMS, "bindTexture");
        int board = callIndex(settle, BOARD_DISTANCE, "bindSelected");
        int use = callIndex(settle, PROGRAM, "use");
        int reduce = callIndex(settle, MESH_DISTANCE, "reduce");
        int renderColor = callIndex(settle, MESH_DISTANCE, "renderColor");
        assertTrue("the own-media borrow must remain in the settle", ownMedia >= 0);
        assertTrue("the own-media borrow must precede program.use", ownMedia < use);
        assertTrue("the settle must re-derive keys when retirement changed them", reduce > use);
        assertTrue("the settle must still draw the ground colour", renderColor > use);
        // A borrow only protects the draw when its release runs after it: a scope that
        // closed before the colour pass would leave those units unbound again.
        assertTrue("the DH depth borrow must enclose the colour draw", dh >= 0 && dh < renderColor);
        assertTrue("the selected board borrow must enclose the colour draw", board >= 0 && board < renderColor);
        int dhClose = callIndex(settle, DH_BINDING, "close");
        int boardClose = callIndex(settle, BOARD_BINDING, "close");
        assertTrue("the DH depth borrow must be released", dhClose >= 0);
        assertTrue("the selected board borrow must be released", boardClose >= 0);
        assertTrue("the DH depth release must follow the colour draw", dhClose > renderColor);
        assertTrue("the selected board release must follow the colour draw", boardClose > renderColor);
    }

    private static MethodNode settle() throws Exception {
        ClassNode owner = read(RENDERER);
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(SETTLE) && method.desc.equals(SETTLE_DESCRIPTOR))
                .toList();
        assertNotNull(owner.methods);
        assertTrue("exactly one " + SETTLE + SETTLE_DESCRIPTOR, matches.size() == 1);
        return matches.getFirst();
    }

    /** Instruction index of the first call to {@code owner.name}, or -1 when absent. */
    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = RingworldDeferredGroundBindingScopeTest.class.getResourceAsStream('/' + name + ".class")) {
            assertNotNull(name, input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
