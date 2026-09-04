package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.util.Annotations;

/** Executes the real Mixin selector matcher over the compiled wrapper contract; no client launch. */
public class RingworldRenderSelectorTest {
    @Test
    public void compiledWholeMethodWrapperSelectsExactlyOneMcpOrSrgTarget() throws Exception {
        AnnotationNode wrapper = readWrapper();
        List<String> names = Annotations.getValue(wrapper, "method");
        assertTargetCount(names, "renderWorld", 1);
        assertTargetCount(names, "func_181560_a", 1);
        assertTargetCount(names, "updateCameraAndRender", 0);
        assertEquals(Boolean.FALSE, Annotations.getValue(wrapper, "remap"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(wrapper, "require"));
        assertEquals(Integer.valueOf(1), Annotations.getValue(wrapper, "allow"));
    }

    private static void assertTargetCount(List<String> names, String target, int expected) {
        int matches = 0;
        for (String name : names) {
            MemberInfo selector = (MemberInfo) MemberInfo.parse(name, null).validate()
                    .configure(ITargetSelector.Configure.SELECT_MEMBER);
            // An absent alternative must not independently require a target.
            assertEquals(0, selector.getMinMatchCount());
            if (selector.matches("net/minecraft/client/renderer/EntityRenderer", target, "(FJ)V")
                    .isExactMatch()) matches++;
        }
        assertEquals(target, expected, matches);
    }

    private static AnnotationNode readWrapper() throws Exception {
        try (InputStream stream = RingworldRenderSelectorTest.class.getResourceAsStream(
                "/stellarium/mixin/MixinEntityRendererRingworldSnapshot.class")) {
            assertNotNull("Compiled wrapper must exist", stream);
            ClassNode mixin = new ClassNode();
            new ClassReader(stream).accept(mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            for (MethodNode method : mixin.methods) {
                AnnotationNode annotation = Annotations.getVisible(method, WrapMethod.class);
                if (annotation != null) return annotation;
            }
            fail("Compiled wrapper has no WrapMethod contract");
            return null;
        }
    }
}
