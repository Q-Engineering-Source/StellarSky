package stellarium.world.ring.generation;

import static org.junit.Assert.*;
import org.junit.Test;

public class RingworldGenerationPolicyTest {
    private final RingworldGenerationPolicy policy = new RingworldGenerationPolicy();

    @Test public void terrainIncludesOnlyTheBoardNotConstructionBands() {
        assertFalse(policy.generatesTerrain(-514));
        assertFalse(policy.generatesTerrain(-513));
        assertTrue(policy.generatesTerrain(-512));
        assertTrue(policy.generatesTerrain(511));
        assertFalse(policy.generatesTerrain(512));
        assertFalse(policy.generatesTerrain(513));
    }

    @Test public void structureStartsExcludeFiveInteriorChunkRowsOnBothSides() {
        for (int z = -512; z < -507; z++) assertFalse(policy.allowsStructureStart(z));
        assertTrue(policy.allowsStructureStart(-507));
        assertTrue(policy.allowsStructureStart(506));
        for (int z = 507; z < 512; z++) assertFalse(policy.allowsStructureStart(z));
        assertFalse(policy.allowsStructureStart(-513));
        assertFalse(policy.allowsStructureStart(512));
    }

    @Test public void structureComponentsMayReachTheMarginButNotCrossTheBoard() {
        assertTrue(policy.containsStructure(-8192, 8191));
        assertTrue(policy.containsStructure(-8192, -8113));
        assertTrue(policy.containsStructure(8112, 8191));
        assertFalse(policy.containsStructure(-8193, 0));
        assertFalse(policy.containsStructure(0, 8192));
    }

    @Test public void inclusiveSingleBlockBoxesAreValid() {
        assertTrue(policy.containsStructure(-8192, -8192));
        assertTrue(policy.containsStructure(8191, 8191));
        assertFalse(policy.containsStructure(8192, 8192));
        assertThrows(IllegalArgumentException.class, () -> policy.containsStructure(1, 0));
    }

    @Test public void extremeCoordinatesNeverWrapIntoTheBoard() {
        for (int z : new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,268435456,-268435456}) {
            assertFalse(policy.generatesTerrain(z));
            assertFalse(policy.allowsStructureStart(z));
        }
        assertFalse(policy.containsStructure(Integer.MIN_VALUE,Integer.MAX_VALUE));
    }
}
