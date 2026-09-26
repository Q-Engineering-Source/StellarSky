package stellarium.client.ring.actinium;

import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.gl.shader.uniform.*;
import org.junit.Test;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldSunshade;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4f;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4fc;
import stellarium.client.ring.dh.DistantHorizonsLocalLight;

public class ActiniumLocalLightUniformsTest {
    @Test public void lightingUploadsItsCameraMatrixWithoutCurvatureUniforms() {
        var origin = new RingworldRenderObserver(0,64,0);
        var shade = new RingworldSunshade(1000,500,24000,0,0,20,10);
        var snapshot = new RingworldDisplaySnapshot(new Object(),new Object(),null,shade,null,
                512,8,origin,1,0);
        var view = new Matrix4f().translation(2,-3,4);
        var frame = new RingworldCurvatureFrame(snapshot.world(),snapshot.scene(),origin,149597870700.0,
                new Matrix4f().get(new float[16]),view.get(new float[16]),0,0,800,600);
        var bindings = new Bindings();
        var uniforms = new ActiniumLocalLightUniforms(bindings);
        RingworldRenderSnapshots.withSnapshot(() -> snapshot, () -> {
            assertNull(RingworldRenderSnapshots.currentCurvatureFrameFor(snapshot.world(),snapshot.scene()));
            uniforms.upload(frame);
            assertEquals(1,bindings.mode);
            assertArrayEquals(new Matrix4f(view).invert().get(new float[16]),
                    bindings.values.get("uSSLightInverseView"),0);
        });
        uniforms.upload((RingworldCurvatureFrame)null);
        assertEquals(0,bindings.mode);
    }
    @Test public void publishesSplitParametersAndClearsLightingForInactiveDraw() {
        Bindings bindings = new Bindings();
        var uniforms = new ActiniumLocalLightUniforms(bindings);
        uniforms.upload(light());
        assertEquals(2, bindings.mode);
        float[] hi = bindings.values.get("uSSDhBandHigh");
        float[] lo = bindings.values.get("uSSDhBandLow");
        assertEquals(29999999.25, (double)hi[2] + lo[2], 0);
        uniforms.upload((RingworldCurvatureFrame)null);
        assertEquals(0, bindings.mode);
    }

    @Test public void failureCannotLeavePreviousShadowActive() {
        Bindings bindings = new Bindings();
        var uniforms = new ActiniumLocalLightUniforms(bindings);
        uniforms.upload(light());
        bindings.fail = true;
        assertThrows(IllegalStateException.class, () -> uniforms.upload(light()));
        assertEquals(0, bindings.mode);
    }

    @Test public void noLightmapVariantDoesNotRequireOptimizedOutUniforms() {
        Bindings bindings = new Bindings();
        bindings.absent = true;
        var uniforms = new ActiniumLocalLightUniforms(bindings);
        uniforms.upload(light());
        assertTrue(bindings.values.isEmpty());
    }

    private static DistantHorizonsLocalLight light() {
        return new DistantHorizonsLocalLight(2, new double[]{1000,500,29999999.25,20},
                new double[]{0,8,-8192,8192}, new double[]{1,0,128,0});
    }

    private static final class Bindings implements ShaderBindingContext {
        int mode;
        boolean fail, absent;
        final Map<String,float[]> values = new HashMap<>();
        @Override @SuppressWarnings("unchecked")
        public <U extends GlUniform<?>> U bindUniformIfPresent(String name, IntFunction<U> factory) {
            if (absent) return null;
            if (name.equals("uSSLightInverseView")) return (U)new GlUniformMatrix4f(0) {
                @Override public void set(Matrix4fc value) {
                    if (fail) throw new IllegalStateException("Simulated matrix upload failure");
                    values.put(name,value.get(new float[16]));
                }
            };
            if (name.equals("uSSDhLightMode")) return (U)new GlUniformInt(0) {
                @Override public void setInt(int value) { mode = value; }
            };
            return (U)new GlUniformFloat4v(0) {
                @Override public void set(float[] value) {
                    if (fail) throw new IllegalStateException("Simulated upload failure");
                    values.put(name, value.clone());
                }
            };
        }
        @Override public GlUniformBlock bindUniformBlockIfPresent(String name,int point) {
            throw new UnsupportedOperationException();
        }
    }
}
