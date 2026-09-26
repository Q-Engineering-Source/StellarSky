package stellarium.client.ring.actinium;

import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat4v;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformInt;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldRenderSnapshots;
import stellarium.client.ring.dh.DistantHorizonsLocalLight;

/** Native terrain consumes the same frozen physical shadow field as DH. */
public final class ActiniumLocalLightUniforms {
    public static RingworldCurvatureFrame currentLightingFrame() {
        var snapshot = RingworldRenderSnapshots.current();
        return snapshot == null ? null : RingworldRenderSnapshots.currentLightingFrameFor(snapshot.world(), snapshot.scene());
    }
    private final GlUniformInt mode;
    private final GlUniformMatrix4f inverseView;
    private final FloatBuffer scratch = BufferUtils.createFloatBuffer(16);
    private final Matrix4f matrix = new Matrix4f();
    private final GlUniformFloat4v[] parameters = new GlUniformFloat4v[6];

    public ActiniumLocalLightUniforms(ShaderBindingContext context) {
        // CELERITAS_NO_LIGHTMAP legitimately optimizes the whole lighting path away.
        mode = context.bindUniformIfPresent("uSSDhLightMode", GlUniformInt::new);
        inverseView = mode == null ? null : context.bindUniform("uSSLightInverseView", GlUniformMatrix4f::new);
        if (mode == null) return;
        String[] names = {"BandHigh", "BandLow", "BoundsHigh", "BoundsLow", "DirectionHigh", "DirectionLow"};
        for (int i = 0; i < names.length; i++) {
            parameters[i] = context.bindUniform("uSSDh" + names[i], GlUniformFloat4v::new);
        }
    }

    public void reset() {
        if (mode != null) mode.setInt(0);
    }

    public void upload(RingworldCurvatureFrame frame) {
        reset();
        if (mode == null || frame == null) return;
        var snapshot = RingworldRenderSnapshots.current();
        if (snapshot == null || !frame.belongsTo(snapshot.world(), snapshot.scene())) {
            throw new IllegalStateException("Actinium local lighting lacks its frozen display snapshot");
        }
        // Curvature stays disabled for near chunks; lighting owns its camera bridge separately.
        frame.copyInverseModelView(scratch);
        inverseView.set(matrix.set(scratch));
        upload(DistantHorizonsLocalLight.from(snapshot, frame.renderOrigin()));
    }

    void upload(DistantHorizonsLocalLight light) {
        reset();
        if (mode == null) return;
        split(0, light.band());
        split(2, light.bounds());
        split(4, light.direction());
        mode.setInt(light.mode());
    }

    private void split(int index, double[] values) {
        float[] high = new float[4];
        float[] low = new float[4];
        for (int i = 0; i < 4; i++) {
            high[i] = (float) values[i];
            low[i] = (float) (values[i] - high[i]);
            if (!Float.isFinite(high[i]) || !Float.isFinite(low[i])) {
                throw new IllegalArgumentException("Actinium lighting parameter exceeds uniform range");
            }
        }
        parameters[index].set(high);
        parameters[index + 1].set(low);
    }
}
