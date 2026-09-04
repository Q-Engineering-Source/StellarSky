package stellarium.client.ring;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure fixed-function UV algebra; GL state ownership remains a real-client gate. */
public class RingworldTerrainLightmapTest {
    @Test
    public void textureMatrixTranslationKeepsBlockAndEdgeClampsSky() {
        for (int rawSky = 0; rawSky <= 15; rawSky++) {
            for (int subtraction = 0; subtraction <= 15; subtraction++) {
                RingworldTerrainLightmap.TextureCoordinates coordinates =
                        RingworldTerrainLightmap.terrainCoordinates(11, rawSky, subtraction);
                assertEquals(176, coordinates.blockCoordinate());
                assertEquals(16 * Math.max(rawSky - subtraction, 0), coordinates.skyCoordinate());
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rawSkyMustRemainInTheVanillaNibbleRange() {
        RingworldTerrainLightmap.terrainCoordinates(0, 16, 0);
    }
}
