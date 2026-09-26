package stellarium.mixin;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import net.minecraft.world.gen.NoiseGeneratorImproved;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import stellarium.world.ring.terrain.PreviewNoiseState;

@Mixin(NoiseGeneratorOctaves.class)
public abstract class MixinOctavesPreviewState implements PreviewNoiseState {
    @Shadow @Final private NoiseGeneratorImproved[] generatorCollection;
    @Shadow @Final private int octaves;
    @Override public void stellarium$appendNoiseState(MessageDigest digest) {
        if (((Object)this).getClass()!=NoiseGeneratorOctaves.class || octaves<1 || octaves>64
                || generatorCollection.length!=octaves) throw new UnsupportedOperationException("Unadmitted octave state");
        digest.update(ByteBuffer.allocate(4).putInt(octaves).array());
        for(var noise:generatorCollection) ((PreviewNoiseState)noise).stellarium$appendNoiseState(digest);
    }
}
