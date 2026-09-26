package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.HashSet;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

/** Actual mapped dependency ABI/call graph evidence; does not substitute for runtime Mixin tests. */
public class OverworldDensitySeamTest {
    @Test public void fingerprintCoversEverySettingsFieldReadByActualDensityMethod() throws Exception {
        var node=new ClassNode();
        try(var stream=getClass().getResourceAsStream("/net/minecraft/world/gen/ChunkGeneratorOverworld.class")) {
            assertNotNull(stream); new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG);
        }
        var fields=new HashSet<String>();
        for(var method:node.methods) if(method.name.equals("generateHeightmap")&&method.desc.equals("(III)V")) {
            for(var instruction:method.instructions) if(instruction instanceof FieldInsnNode field
                    &&field.owner.equals("net/minecraft/world/gen/ChunkGeneratorSettings")) fields.add(field.name);
        }
        assertEquals(Set.of("depthNoiseScaleX","depthNoiseScaleZ","depthNoiseScaleExponent",
                "coordinateScale","heightScale","mainNoiseScaleX","mainNoiseScaleY","mainNoiseScaleZ",
                "biomeDepthOffSet","biomeDepthWeight","biomeScaleOffset","biomeScaleWeight",
                "baseSize","stretchY","lowerLimitScale","upperLimitScale"),fields);
    }
    @Test public void privateDensityEntryOnlyCallsNoiseBiomeScalarsAndLerp() throws Exception {
        var node=new ClassNode();
        try(var stream=getClass().getResourceAsStream("/net/minecraft/world/gen/ChunkGeneratorOverworld.class")) {
            assertNotNull(stream); new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG);
        }
        var methods=node.methods.stream().filter(m->m.name.equals("generateHeightmap")&&m.desc.equals("(III)V")).toList();
        assertEquals(1,methods.size());
        var allowed=Set.of("net/minecraft/world/gen/NoiseGeneratorOctaves#generateNoiseOctaves",
                "net/minecraft/world/biome/Biome#getBaseHeight","net/minecraft/world/biome/Biome#getHeightVariation",
                "net/minecraft/util/math/MathHelper#clampedLerp");
        int calls=0;
        for(var instruction:methods.getFirst().instructions) if(instruction instanceof MethodInsnNode call) {
            assertTrue(call.owner+'#'+call.name,allowed.contains(call.owner+'#'+call.name)); calls++;
        }
        assertTrue(calls>=4);
    }
}
