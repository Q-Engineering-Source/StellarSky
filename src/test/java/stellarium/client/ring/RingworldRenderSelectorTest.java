package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.util.Annotations;

/** Executes the real Mixin selector matcher over the compiled wrapper contract; no client launch. */
public class RingworldRenderSelectorTest {
    @Test
    public void compiledWholeMethodWrapperSelectsExactlyOneMcpOrSrgTarget() throws Exception {
        AnnotationNode wrapper = readAnnotation("/stellarium/mixin/MixinEntityRendererRingworldSnapshot.class", WrapMethod.class);
        List<String> names = Annotations.getValue(wrapper, "method");
        assertTargetCount(names, "net/minecraft/client/renderer/EntityRenderer", "renderWorld", "(FJ)V", 1);
        assertTargetCount(names, "net/minecraft/client/renderer/EntityRenderer", "func_181560_a", "(FJ)V", 1);
        assertTargetCount(names, "net/minecraft/client/renderer/EntityRenderer", "updateCameraAndRender", "(FJ)V", 0);
        assertEquals(Boolean.FALSE, Annotations.getValue(wrapper, "remap"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(wrapper, "require"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(wrapper, "allow"));
    }

    @Test
    public void compiledTerrainWrappersSelectOnlyTheirConcreteMcpOrSrgLayerMethods() throws Exception {
        assertTerrainWrapper("/stellarium/mixin/MixinVboRenderListRingworldTerrainLightmap.class",
                "net/minecraft/client/renderer/VboRenderList");
        assertTerrainWrapper("/stellarium/mixin/MixinRenderListRingworldTerrainLightmap.class",
                "net/minecraft/client/renderer/RenderList");
    }

    @Test
    public void compiledPreRenderHookUsesTheExactRenderChunkDescriptorAtReturn() throws Exception {
        AnnotationNode inject = readAnnotation(
                "/stellarium/mixin/MixinChunkRenderContainerRingworldTerrainLightmap.class", Inject.class);
        assertEquals(List.of("preRenderChunk(Lnet/minecraft/client/renderer/chunk/RenderChunk;)V"),
                Annotations.getValue(inject, "method"));
        List<AnnotationNode> atValues = Annotations.getValue(inject, "at");
        assertEquals(1, atValues.size());
        AnnotationNode at = atValues.getFirst();
        assertEquals("RETURN", Annotations.getValue(at, "value"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(inject, "require"));
    }

    private static void assertTerrainWrapper(String resource, String owner) throws Exception {
        AnnotationNode wrapper = readAnnotation(resource, WrapMethod.class);
        List<String> names = Annotations.getValue(wrapper, "method");
        assertTargetCount(names, owner, "renderChunkLayer", "(Lnet/minecraft/util/BlockRenderLayer;)V", 1);
        assertTargetCount(names, owner, "func_178001_a", "(Lnet/minecraft/util/BlockRenderLayer;)V", 1);
        assertEquals(Boolean.FALSE, Annotations.getValue(wrapper, "remap"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(wrapper, "require"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(wrapper, "allow"));
    }

    private static void assertTargetCount(List<String> names, String owner, String target, String descriptor, int expected) {
        int matches = 0;
        for (String name : names) {
            MemberInfo selector = (MemberInfo) MemberInfo.parse(name, null).validate()
                    .configure(ITargetSelector.Configure.SELECT_MEMBER);
            // An absent alternative must not independently require a target.
            assertEquals(0, selector.getMinMatchCount());
            if (selector.matches(owner, target, descriptor)
                    .isExactMatch()) matches++;
        }
        assertEquals(target, expected, matches);
    }

    private static AnnotationNode readAnnotation(String resource, Class<? extends Annotation> annotationType) throws Exception {
        try (InputStream stream = RingworldRenderSelectorTest.class.getResourceAsStream(resource)) {
            assertNotNull("Compiled wrapper must exist", stream);
            ClassNode mixin = new ClassNode();
            new ClassReader(stream).accept(mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            for (MethodNode method : mixin.methods) {
                AnnotationNode annotation = Annotations.getVisible(method, annotationType);
                if (annotation != null) return annotation;
            }
            fail("Compiled Mixin has no requested annotation contract");
            return null;
        }
    }
}
