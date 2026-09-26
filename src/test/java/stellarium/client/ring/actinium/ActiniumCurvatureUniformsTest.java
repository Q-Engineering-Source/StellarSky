package stellarium.client.ring.actinium;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import com.gtnewhorizons.angelica.client.rendering.GlUniformFloat2v;
import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.gl.shader.uniform.*;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4f;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4fc;
import org.junit.Test;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.world.ring.RingworldRenderObserver;

/** Runs the real uploader against recording uniform bindings; no GL context or reflection. */
public class ActiniumCurvatureUniformsTest {
    @Test public void uploadsSplitRadiusAndEyeBeforeEnablingAndClearsOnTheNextInactiveDraw() {
        RecordingBindings bindings = new RecordingBindings();
        var uniforms = new ActiniumCurvatureUniforms(bindings);
        var frame = frame();
        Matrix4f nativeView = new Matrix4f().rotateY(0.4F).translate(-2, -3, -4);
        uniforms.upload(frame, nativeView);
        assertEquals("uSSCurvatureEnabled=0", bindings.events.getFirst());
        assertEquals("uSSCurvatureEnabled=1", bindings.events.getLast());
        float[] radius = (float[]) bindings.values.get("uSSRadiusHiLo");
        assertEquals(149597870700.0, (double) radius[0] + radius[1], 0.0);
        assertArrayEquals(new float[] {0, 2, 0}, (float[]) bindings.values.get("uSSEyeRelative"), 0);
        Matrix4f inverse = (Matrix4f) bindings.values.get("uSSNativeInverseView");
        assertTrue(inverse.mul(nativeView, new Matrix4f()).equals(new Matrix4f(), 1e-5F));
        uniforms.upload(null, new Matrix4f());
        assertEquals(0, bindings.values.get("uSSCurvatureEnabled"));
    }

    @Test public void invalidNativeCameraClearsPreviouslyEnabledUniformBeforeRejectingTheDraw() {
        RecordingBindings bindings = new RecordingBindings();
        var uniforms = new ActiniumCurvatureUniforms(bindings);
        uniforms.upload(frame(), new Matrix4f());
        assertThrows(IllegalArgumentException.class, () -> uniforms.upload(frame(), new Matrix4f().zero()));
        assertEquals(0, bindings.values.get("uSSCurvatureEnabled"));
    }

    @Test public void uploadFailureDoesNotPublishPartialState() {
        RecordingBindings bindings = new RecordingBindings();
        var uniforms = new ActiniumCurvatureUniforms(bindings);
        uniforms.upload(frame(), new Matrix4f());
        bindings.failAt = "uSSBaseView";
        assertThrows(IllegalStateException.class, () -> uniforms.upload(frame(), new Matrix4f()));
        assertEquals(0, bindings.values.get("uSSCurvatureEnabled"));
    }

    @Test public void missingShaderUniformIsAnErrorAtProgramCreation() {
        RecordingBindings bindings = new RecordingBindings();
        bindings.missing = "uSSRadiusHiLo";
        assertThrows(NullPointerException.class, () -> new ActiniumCurvatureUniforms(bindings));
    }

    private static RingworldCurvatureFrame frame() {
        return new RingworldCurvatureFrame(new Object(), new Object(), new RingworldRenderObserver(1000, 64, 2000),
                149597870700.0, new Matrix4f().perspective(1, 1.6F, 0.1F, 10000).get(new float[16]),
                new Matrix4f().translation(0, -2, 0).get(new float[16]), 0, 0, 1600, 1000);
    }

    private static final class RecordingBindings implements ShaderBindingContext {
        final Map<String, Object> values = new HashMap<>();
        final List<String> events = new ArrayList<>();
        String missing;
        String failAt;

        void record(String name, Object value) {
            if (name.equals(failAt)) throw new IllegalStateException("Simulated uniform upload failure");
            values.put(name, value);
            events.add(name + "=" + value);
        }

        @Override @SuppressWarnings("unchecked")
        public <U extends GlUniform<?>> U bindUniformIfPresent(String name, IntFunction<U> factory) {
            if (name.equals(missing)) return null;
            GlUniform<?> uniform = switch (name) {
                case "uSSCurvatureEnabled" -> new GlUniformInt(0) {
                    @Override public void setInt(int value) { record(name, value); }
                };
                case "uSSRadiusHiLo", "uSSOriginYHiLo" -> new GlUniformFloat2v(0) {
                    @Override public void set(float x, float y) { record(name, new float[] {x, y}); }
                };
                case "uSSEyeRelative" -> new GlUniformFloat3v(0) {
                    @Override public void set(float x, float y, float z) { record(name, new float[] {x, y, z}); }
                };
                default -> new GlUniformMatrix4f(0) {
                    @Override public void set(Matrix4fc value) { record(name, new Matrix4f(value)); }
                };
            };
            return (U) uniform;
        }

        @Override public GlUniformBlock bindUniformBlockIfPresent(String name, int bindingPoint) {
            throw new UnsupportedOperationException("No uniform blocks in this program");
        }
    }
}
