package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import java.util.Random;
import net.minecraft.init.Biomes;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureStart;
import net.minecraft.world.gen.structure.template.TemplateManager;
import org.junit.BeforeClass;
import org.junit.Test;

public class RingworldStructureAdmissionTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }
    private final RingworldBiomeProvider biomes = new RingworldBiomeProvider(new BiomeProviderSingle(Biomes.PLAINS), new SpaceBiome());

    @Test public void startsRespectFiveChunkMarginAndSpace() {
        assertFalse(RingworldStructureAdmission.allowsStart(biomes, 0, -508));
        assertTrue(RingworldStructureAdmission.allowsStart(biomes, 0, -507));
        assertTrue(RingworldStructureAdmission.allowsStart(biomes, 0, 506));
        assertFalse(RingworldStructureAdmission.allowsStart(biomes, 0, 507));
        var space = new SpaceBiome();
        assertFalse(RingworldStructureAdmission.allowsStart(
                new RingworldBiomeProvider(new BiomeProviderSingle(space), space), 0, 0));
    }

    @Test public void componentsMayUseMarginButCannotCrossOuterBoundary() {
        assertTrue(RingworldStructureAdmission.allowsNewStructure(biomes, new Start(box(-8192, -8100), box(8100, 8191))));
        assertFalse(RingworldStructureAdmission.allowsNewStructure(biomes, new Start(box(-8193, -8100))));
        var crossing = new Start(box(0, 10), box(8190, 8192));
        assertFalse(RingworldStructureAdmission.allowsNewStructure(biomes, crossing));
        assertEquals(2, crossing.getComponents().size());
        assertEquals(8192, crossing.getComponents().get(1).getBoundingBox().maxZ);
    }

    @Test public void emptyOrSpaceComponentsAreRejected() {
        assertFalse(RingworldStructureAdmission.allowsNewStructure(biomes, new Start()));
        var space = new SpaceBiome();
        assertFalse(RingworldStructureAdmission.allowsNewStructure(
                new RingworldBiomeProvider(new BiomeProviderSingle(space), space), new Start(box(0, 10))));
    }

    private static StructureBoundingBox box(int minZ, int maxZ) {
        return new StructureBoundingBox(0, 32, minZ, 15, 48, maxZ);
    }
    private static final class Start extends StructureStart {
        Start(StructureBoundingBox... boxes) {
            for (var box : boxes) components.add(new Component(box));
            updateBoundingBox();
        }
    }
    private static final class Component extends StructureComponent {
        Component(StructureBoundingBox box) { boundingBox = box; }
        @Override protected void writeStructureToNBT(NBTTagCompound tag) { throw new UnsupportedOperationException(); }
        @Override protected void readStructureFromNBT(NBTTagCompound tag, TemplateManager manager) { throw new UnsupportedOperationException(); }
        @Override public boolean addComponentParts(World world, Random random, StructureBoundingBox box) { throw new UnsupportedOperationException(); }
    }
}
