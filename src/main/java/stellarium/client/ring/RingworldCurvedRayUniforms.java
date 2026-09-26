package stellarium.client.ring;

import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL20;

/**
 * The four uniform bindings shared by GLSL display-ray consumers.
 *
 * <p>The owning program must already be bound. The low components preserve the CPU double values
 * across the float uniform API; the shader reconstructs them before all curved calculations.</p>
 */
public final class RingworldCurvedRayUniforms {
    private final int enabled;
    private final int radius;
    private final int originY;
    private final int eyeRelative;

    public RingworldCurvedRayUniforms(int programId) {
        this(lookup(programId));
    }

    public RingworldCurvedRayUniforms(ToIntFunction<String> lookup) {
        enabled = uniform(lookup, "uSSCurvatureEnabled");
        radius = uniform(lookup, "uSSRadiusHiLo");
        originY = uniform(lookup, "uSSOriginYHiLo");
        eyeRelative = uniform(lookup, "uSSEyeRelative");
    }

    private static ToIntFunction<String> lookup(int programId) {
        if (programId == 0) {
            throw new IllegalArgumentException("Curved-ray uniform program must be non-zero");
        }
        return name -> OpenGlHelper.glGetUniformLocation(programId, name);
    }

    /** Clears stale state first; publishes enabled only after every curvature parameter is valid. */
    public void upload(RingworldCurvatureFrame frame) {
        GL20.glUniform1i(enabled, 0);
        boolean curved = frame != null;
        if (frame == null) frame = RingworldRenderSnapshots.currentOpticalFrame();
        if (frame == null) {
            return;
        }
        float[] radiusSplit = split(frame.geometry().radiusMeters());
        float[] originYSplit = split(frame.renderOrigin().y());
        float eyeX = frame.cameraX();
        float eyeY = frame.cameraY();
        float eyeZ = frame.cameraZ();
        if (!Float.isFinite(eyeX) || !Float.isFinite(eyeY) || !Float.isFinite(eyeZ)) {
            throw new IllegalArgumentException("Curved-ray optical eye must be finite");
        }
        GL20.glUniform2f(radius, radiusSplit[0], radiusSplit[1]);
        GL20.glUniform2f(originY, originYSplit[0], originYSplit[1]);
        GL20.glUniform3f(eyeRelative, eyeX, eyeY, eyeZ);
        GL20.glUniform1i(enabled, curved ? 1 : 0);
    }

    static float[] split(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Curved-ray parameter must be finite");
        }
        float high = (float) value;
        float low = (float) (value - high);
        if (!Float.isFinite(high) || !Float.isFinite(low)) {
            throw new IllegalArgumentException("Curved-ray parameter exceeds the float uniform range");
        }
        return new float[] {high, low};
    }

    private static int uniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) {
            throw new IllegalStateException("Curved-ray shader lacks uniform " + name);
        }
        return location;
    }
}
