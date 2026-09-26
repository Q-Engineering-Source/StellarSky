package stellarium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.versioning.ArtifactVersion;
import net.minecraftforge.fml.common.versioning.DefaultArtifactVersion;
import net.minecraftforge.fml.common.versioning.DependencyParser;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.Test;

/** Executes the same side-aware parser used by the selected Cleanroom Loader, not a string check. */
public class ActiniumDependencyTest {
    @Test public void physicalClientRequiresActiniumAndLoadsAfterIt() {
        var dependencies = parse(Side.CLIENT);
        assertEquals(Set.of("stellarapi", "actinium"), labels(dependencies.requirements));
        assertEquals(Set.of("stellarapi", "actinium"), labels(dependencies.dependencies));
        assertTrue(dependencies.dependants.isEmpty());
    }

    @Test public void dedicatedServerDoesNotRequireClientOnlyRenderer() {
        var dependencies = parse(Side.SERVER);
        assertEquals(Set.of("stellarapi"), labels(dependencies.requirements));
        assertEquals(Set.of("stellarapi"), labels(dependencies.dependencies));
    }

    @Test public void actiniumMixinContractIsPinnedToTheVerifiedRelease() {
        ArtifactVersion actinium = parse(Side.CLIENT).requirements.stream()
                .filter(value -> value.getLabel().equals("actinium")).findFirst().orElseThrow();
        assertTrue(actinium.containsVersion(new DefaultArtifactVersion("actinium", "alpha-0.0.8")));
        assertEquals(false, actinium.containsVersion(new DefaultArtifactVersion("actinium", "alpha-0.0.7")));
        assertEquals(false, actinium.containsVersion(new DefaultArtifactVersion("actinium", "alpha-0.0.9")));
    }

    @Test public void existingStellarApiVersionContractRemainsRequiredOnBothSides() {
        for (Side side : Side.values()) {
            ArtifactVersion api = parse(side).requirements.stream()
                    .filter(value -> value.getLabel().equals("stellarapi")).findFirst().orElseThrow();
            assertTrue(api.containsVersion(new DefaultArtifactVersion("stellarapi", "1.12.2-0.5.2.3")));
            assertEquals("[1.12.2-0.5.2.1,1.12.2-0.5.3.0)", api.getRangeString());
        }
    }

    private static DependencyParser.DependencyInfo parse(Side side) {
        return new DependencyParser(StellarSkyReferences.MODID, side)
                .parseDependencies(StellarSky.class.getAnnotation(Mod.class).dependencies());
    }

    private static Set<String> labels(Collection<ArtifactVersion> versions) {
        return versions.stream().map(ArtifactVersion::getLabel).collect(Collectors.toSet());
    }
}
