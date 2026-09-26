package stellarium.mixin;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import net.minecraft.world.gen.NoiseGeneratorImproved;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import stellarium.world.ring.terrain.PreviewNoiseState;

@Mixin(NoiseGeneratorImproved.class)
public abstract class MixinImprovedPreviewState implements PreviewNoiseState {
    @Shadow @Final private int[] permutations;
    @Shadow public double xCoord;
    @Shadow public double yCoord;
    @Shadow public double zCoord;
    @Shadow @Final private static double[] GRAD_X;
    @Shadow @Final private static double[] GRAD_Y;
    @Shadow @Final private static double[] GRAD_Z;
    @Shadow @Final private static double[] GRAD_2X;
    @Shadow @Final private static double[] GRAD_2Z;
    @Override public void stellarium$appendNoiseState(MessageDigest digest) {
        if (((Object)this).getClass()!=NoiseGeneratorImproved.class || permutations.length!=512)
            throw new UnsupportedOperationException("Unadmitted improved-noise state");
        var bytes=ByteBuffer.allocate(3*8+512*4);
        for(double coordinate:new double[]{xCoord,yCoord,zCoord}) {
            if(!Double.isFinite(coordinate))throw new IllegalStateException("Non-finite noise offset");
            bytes.putDouble(coordinate);
        }
        for(int permutation:permutations) {
            if(permutation<0||permutation>255)throw new IllegalStateException("Invalid noise permutation");
            bytes.putInt(permutation);
        }
        digest.update(bytes.array());
        for(double[] gradient:new double[][]{GRAD_X,GRAD_Y,GRAD_Z,GRAD_2X,GRAD_2Z}) {
            if(gradient.length!=16)throw new UnsupportedOperationException("Unadmitted noise gradient count");
            var values=ByteBuffer.allocate(16*8);
            for(double value:gradient) {
                if(!Double.isFinite(value))throw new IllegalStateException("Non-finite noise gradient");
                values.putDouble(value);
            }
            digest.update(values.array());
        }
    }
}
