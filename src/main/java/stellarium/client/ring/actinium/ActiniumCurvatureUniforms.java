package stellarium.client.ring.actinium;

import java.nio.FloatBuffer;
import org.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat3v;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformInt;
import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import com.gtnewhorizons.angelica.client.rendering.GlUniformFloat2v;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4f;
import org.embeddedt.embeddium.impl.shadow.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import stellarium.client.ring.RingworldCurvatureFrame;

/** Per-program bindings owned and disposed with Actinium's native chunk program. */
public final class ActiniumCurvatureUniforms {
    private final GlUniformInt enabled;
    private final GlUniformFloat2v radius;
    private final GlUniformFloat2v originY;
    private final GlUniformFloat3v eye;
    private final GlUniformMatrix4f baseView;
    private final GlUniformMatrix4f inverseBaseView;
    private final GlUniformMatrix4f inverseNativeView;
    private final FloatBuffer scratch = BufferUtils.createFloatBuffer(16);
    private final Matrix4f matrix = new Matrix4f();

    public ActiniumCurvatureUniforms(ShaderBindingContext context) {
        enabled = context.bindUniform("uSSCurvatureEnabled", GlUniformInt::new);
        radius = context.bindUniform("uSSRadiusHiLo", GlUniformFloat2v::new);
        originY = context.bindUniform("uSSOriginYHiLo", GlUniformFloat2v::new);
        eye = context.bindUniform("uSSEyeRelative", GlUniformFloat3v::new);
        baseView = context.bindUniform("uSSBaseView", GlUniformMatrix4f::new);
        inverseBaseView = context.bindUniform("uSSInverseBaseView", GlUniformMatrix4f::new);
        inverseNativeView = context.bindUniform("uSSNativeInverseView", GlUniformMatrix4f::new);
    }

    /** Reset at every bind, including nested or non-ring draws using a previously curved program. */
    public void reset() {
        enabled.setInt(0);
    }

    public void upload(RingworldCurvatureFrame frame, Matrix4fc nativeView) {
        reset();
        if (frame == null) return;
        if (!nativeView.isFinite() || !Float.isFinite(nativeView.determinant())
                || nativeView.determinant() == 0.0F) {
            throw new IllegalArgumentException("Actinium terrain model-view must be finite and invertible");
        }
        matrix.set(nativeView).invert();
        if (!matrix.isFinite()) throw new IllegalArgumentException("Actinium terrain inverse view is not finite");
        inverseNativeView.set(matrix);
        setSplit(radius, frame.geometry().radiusMeters());
        setSplit(originY, frame.renderOrigin().y());
        eye.set(frame.cameraX(), frame.cameraY(), frame.cameraZ());
        frame.copyModelView(scratch);
        baseView.set(matrix.set(scratch));
        frame.copyInverseModelView(scratch);
        inverseBaseView.set(matrix.set(scratch));
        // Publish the enabled bit last: failed upload must never reuse a prior camera's state.
        enabled.setInt(1);
    }

    private static void setSplit(GlUniformFloat2v target, double value) {
        float high = (float) value;
        float low = (float) (value - high);
        if (!Float.isFinite(high) || !Float.isFinite(low)) {
            throw new IllegalArgumentException("Curvature parameter exceeds the terrain uniform range");
        }
        target.set(high, low);
    }
}
