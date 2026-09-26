package stellarium.world.ring.generation;

import static org.junit.Assert.*;

import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.MapGenStronghold;
import org.junit.BeforeClass;
import org.junit.Test;

/** Counterexample for the next integration gate: vanilla locate does not use recursiveGenerate admission. */
public class RingworldStructureLocateEvidenceTest {
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void vanillaPredictionCanPointToARejectedSpaceStartWithoutGeneratingChunks() {
        var fixtureWorld = new RingworldChunkGeneratorTest.FixtureWorld();
        var generator = new MapGenStronghold() {{ this.world = fixtureWorld; }};
        var candidate = generator.getNearestStructurePos(fixtureWorld, new BlockPos(0, 64, 20000), false);
        assertNotNull(candidate);
        assertFalse(new RingworldGenerationPolicy().allowsStructureStart(Math.floorDiv(candidate.getZ(), 16)));
        System.out.println("Vanilla predicted disallowed Stronghold: " + candidate);
    }
}
