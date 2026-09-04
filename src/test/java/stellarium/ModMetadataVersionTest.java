package stellarium;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import net.minecraftforge.fml.common.Mod;

public class ModMetadataVersionTest {
    @Test
    public void modAnnotationUsesTheBuildVersion() {
        String expectedVersion = System.getProperty("stellarsky.expectedVersion");
        assertNotNull("Gradle must provide the expected project version", expectedVersion);

        Mod mod = StellarSky.class.getAnnotation(Mod.class);
        assertNotNull("StellarSky must retain its @Mod annotation", mod);
        assertEquals(expectedVersion, mod.version());
        assertEquals(expectedVersion, StellarSkyReferences.VERSION);
    }
}
