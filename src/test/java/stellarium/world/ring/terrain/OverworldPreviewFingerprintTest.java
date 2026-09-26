package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.Arrays;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import org.junit.Test;

public class OverworldPreviewFingerprintTest {
    private byte[] capture(long seed, int sea, boolean amplified, ChunkGeneratorSettings.Factory factory, byte[] biome) {
        return OverworldPreviewFingerprint.capture(seed, sea, amplified, factory.build(), biome);
    }
    @Test public void equivalentEffectiveSettingsAreStableAndCoreInputsAreDistinct() {
        var factory = new ChunkGeneratorSettings.Factory(); var biome = new byte[32];
        var initial = capture(10,63,false,factory,biome);
        assertArrayEquals(initial,capture(10,63,false,new ChunkGeneratorSettings.Factory(),biome));
        assertFalse(Arrays.equals(initial,capture(11,63,false,factory,biome)));
        assertFalse(Arrays.equals(initial,capture(10,64,false,factory,biome)));
        assertFalse(Arrays.equals(initial,capture(10,63,true,factory,biome)));
        biome[0]=1; assertFalse(Arrays.equals(initial,capture(10,63,false,factory,biome)));
    }
    @Test public void eachConsumedDensityScalarChangesFingerprint() {
        var initial = capture(10,63,false,new ChunkGeneratorSettings.Factory(),new byte[32]);
        for(int index=0;index<16;index++) {
            var factory = new ChunkGeneratorSettings.Factory();
            switch(index) {
                case 0 -> factory.depthNoiseScaleX++;
                case 1 -> factory.depthNoiseScaleZ++;
                case 2 -> factory.depthNoiseScaleExponent++;
                case 3 -> factory.coordinateScale++;
                case 4 -> factory.heightScale++;
                case 5 -> factory.mainNoiseScaleX++;
                case 6 -> factory.mainNoiseScaleY++;
                case 7 -> factory.mainNoiseScaleZ++;
                case 8 -> factory.biomeDepthOffset++;
                case 9 -> factory.biomeDepthWeight++;
                case 10 -> factory.biomeScaleOffset++;
                case 11 -> factory.biomeScaleWeight++;
                case 12 -> factory.baseSize++;
                case 13 -> factory.stretchY++;
                case 14 -> factory.lowerLimitScale++;
                case 15 -> factory.upperLimitScale++;
            }
            assertFalse("setting " + index,Arrays.equals(initial,capture(10,63,false,factory,new byte[32])));
        }
    }
    @Test public void decorationDoesNotInvalidateBaseDensityAndMalformedInputsFail() {
        var factory = new ChunkGeneratorSettings.Factory();
        var initial = capture(10,63,false,factory,new byte[32]);
        factory.useVillages = !factory.useVillages; factory.dungeonChance++;
        assertArrayEquals(initial,capture(10,63,false,factory,new byte[32]));
        assertThrows(IllegalArgumentException.class,()->capture(10,257,false,factory,new byte[32]));
        assertThrows(IllegalArgumentException.class,()->capture(10,63,false,factory,new byte[31]));
        factory.heightScale=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->capture(10,63,false,factory,new byte[32]));
    }
}
