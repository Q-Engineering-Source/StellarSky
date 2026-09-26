package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The deferred-preview hook's Mixin contract: owner, descriptor, ordinal, require/allow,
 * side classification and config registration, read from the compiled class and the
 * shipped config rather than from the source text.
 *
 * <p>This does not weave a Mixin or open a GL context.</p>
 */
public class RingworldPreviewMixinContractTest {
    private static final String MIXIN = "stellarium/mixin/MixinEntityRendererRingworldPreview";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN_TYPE = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String HANDLER = "stellarium$settleDeferredPreview";
    private static final String OWNER = "net/minecraft/client/renderer/EntityRenderer";
    private static final String METHOD = "renderWorldPass(IFJ)V";
    private static final String TARGET =
            "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer"
                    + "(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I";

    @Test
    public void hookNamesTheVerifiedOwnerDescriptorAndSingleOrdinal() throws Exception {
        ClassNode mixin = read(MIXIN);
        AnnotationNode declared = first(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN_TYPE);
        assertNotNull("@Mixin must be present", declared);
        assertTrue("mixin must target EntityRenderer: " + declared.values,
                String.valueOf(value(declared, "value")).contains(OWNER));

        MethodNode handler = mixin.methods.stream()
                .filter(m -> m.name.equals(HANDLER) && m.desc.equals("(IFJLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"))
                .findFirst().orElseThrow(() -> new AssertionError("handler " + HANDLER + " is missing"));
        AnnotationNode inject = first(handler.visibleAnnotations, handler.invisibleAnnotations, INJECT);
        assertNotNull("@Inject must be present on " + HANDLER, inject);
        // @Inject's method member is declared as an array, so a single value still arrives boxed.
        assertEquals(List.of(METHOD), value(inject, "method"));

        assertEquals(1, value(inject, "require"));
        assertEquals(1, value(inject, "allow"));

        // A single @At entry keeps the injection point unambiguous.
        assertTrue(value(inject, "at") instanceof List<?>);
        List<?> points = (List<?>) value(inject, "at");
        assertEquals("exactly one injection point", 1, points.size());
        AnnotationNode at = (AnnotationNode) points.getFirst();
        assertEquals("INVOKE", value(at, "value"));
        assertEquals(TARGET, value(at, "target"));
        assertEquals("ordinal must select the post-SOLID renderBlockLayer call site", 1, value(at, "ordinal"));
    }

    @Test
    public void hookIsRegisteredOnTheClientSide() throws Exception {
        String config;
        try (InputStream input = getClass().getResourceAsStream("/mixins.stellarsky.json")) {
            assertNotNull("mixins.stellarsky.json must ship on the classpath", input);
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int client = config.indexOf("\"client\"");
        int common = config.indexOf("\"common\"");
        assertTrue("config must declare a client section", client >= 0);
        int entry = config.indexOf("MixinEntityRendererRingworldPreview");
        assertTrue("the preview hook must be registered", entry >= 0);
        assertTrue("the hook must be registered in the client list, not the common list",
                common < 0 || client < common || entry > client);
        assertTrue("the hook must not be in the common list", entry > client);
    }

    private static Object value(AnnotationNode annotation, String name) {
        assertNotNull(annotation.values);
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static AnnotationNode first(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        for (List<AnnotationNode> list : List.of(visible == null ? List.<AnnotationNode>of() : visible,
                invisible == null ? List.<AnnotationNode>of() : invisible)) {
            for (AnnotationNode node : list) if (descriptor.equals(node.desc)) return node;
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = RingworldPreviewMixinContractTest.class.getResourceAsStream('/' + name + ".class")) {
            assertNotNull(name, input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
