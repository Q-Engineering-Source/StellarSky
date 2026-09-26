package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the production scheduling relation the deferred preview depends on.
 *
 * <p>The early own-media pass and the late preview settle are only correct if the world
 * pass really submits the ring model before Distant Horizons' terrain call and settles the
 * preview afterwards. That relation lives in Cleanroom's compiled
 * {@code EntityRenderer.renderWorldPass}, not in this project's source, so this test reads
 * the actual Minecraft class the client will link against and asserts the call order and
 * the layer identities around the seam.</p>
 *
 * <p>This is source/bytecode evidence about the hook site. It does not run Mixin weaving,
 * OpenGL or a real frame.</p>
 */
public class MinecraftRenderWorldPassOrderTest {
    private static final String ENTITY_RENDERER = "net/minecraft/client/renderer/EntityRenderer";
    private static final String RENDER_GLOBAL = "net/minecraft/client/renderer/RenderGlobal";
    private static final String BLOCK_RENDER_LAYER = "net/minecraft/util/BlockRenderLayer";

    /** Ordinal of the {@code RenderGlobal.renderBlockLayer} call site the settle hook uses. */
    private static final int SETTLE_ORDINAL = 1;
    private static final String RENDER_BLOCK_LAYER_DESCRIPTOR =
            "(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I";

    @Test
    public void opaqueTerrainSubmissionAndBothLaterLayerCallsKeepTheirVerifiedOrder() throws Exception {
        MethodNode pass = worldPass();
        List<CallSite> layers = blockLayerCallSites(pass);
        assertEquals("renderWorldPass must submit the four verified block layers", 4, layers.size());
        assertEquals(List.of("SOLID", "CUTOUT_MIPPED", "CUTOUT", "TRANSLUCENT"),
                layers.stream().map(CallSite::layer).toList());

        List<Integer> fog = new ArrayList<>();
        int fogIndex = 0;
        for (AbstractInsnNode instruction : pass.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(ENTITY_RENDERER)
                    && (call.name.equals("setupFog") || call.name.equals("func_78480_b"))
                    && call.desc.equals("(IF)V")) {
                fog.add(fogIndex);
            }
            fogIndex++;
        }
        assertEquals("renderWorldPass must keep its five verified setupFog call sites", 5, fog.size());

        int early = fog.get(1);
        int solid = layers.get(0).index();
        int settle = layers.get(SETTLE_ORDINAL).index();
        int translucent = layers.get(3).index();

        assertTrue("the early own-media hook must precede the SOLID terrain submission", early < solid);
        assertTrue("the deferred preview settle must follow the SOLID terrain submission", settle > solid);
        assertTrue("the deferred preview settle must precede the translucent layer", settle < translucent);
        assertTrue("the deferred preview settle must precede the remaining cutout layers",
                settle < layers.get(2).index());
    }

    @Test
    public void settleOrdinalIsExactlyOneSlotAfterTheOpaqueSubmission() throws Exception {
        List<CallSite> layers = blockLayerCallSites(worldPass());
        assertEquals("CUTOUT_MIPPED", layers.get(SETTLE_ORDINAL).layer());
        assertTrue("no other renderBlockLayer call may sit between SOLID and the settle site",
                layers.get(SETTLE_ORDINAL).index() > layers.get(0).index());
        // The hook selects its site by ordinal, so the descriptor it targets must be the one
        // the verified bytecode uses; a changed signature would silently move the settle.
        assertEquals(RENDER_BLOCK_LAYER_DESCRIPTOR, layers.get(SETTLE_ORDINAL).descriptor());
    }

    private static MethodNode worldPass() throws Exception {
        ClassNode owner = read(ENTITY_RENDERER);
        List<MethodNode> matches = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            // Accept the MCP name or the production SRG name so the contract is checked
            // against whichever namespace the headless test classpath exposes.
            if (method.desc.equals("(IFJ)V")
                    && (method.name.equals("renderWorldPass") || method.name.equals("func_175068_a"))) {
                matches.add(method);
            }
        }
        assertEquals(ENTITY_RENDERER + " world pass", 1, matches.size());
        return matches.getFirst();
    }

    private static List<CallSite> blockLayerCallSites(MethodNode pass) {
        List<CallSite> result = new ArrayList<>();
        String layer = null;
        int index = 0;
        for (AbstractInsnNode instruction : pass.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(BLOCK_RENDER_LAYER)) {
                layer = field.name;
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals(RENDER_GLOBAL)
                    && (call.name.equals("renderBlockLayer") || call.name.equals("func_174977_a"))) {
                assertNotNull("renderBlockLayer must be called with an identifiable layer on the stack", layer);
                result.add(new CallSite(index, layer, call.desc));
            }
            index++;
        }
        return result;
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = MinecraftRenderWorldPassOrderTest.class.getResourceAsStream('/' + name + ".class")) {
            assertNotNull(name, input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private record CallSite(int index, String layer, String descriptor) { }
}
