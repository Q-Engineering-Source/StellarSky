package stellarium.render.util;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL33;
import org.lwjglx.opengl.GL30;

/** Indexed sampler queries through Cleanroom's explicit binding, outside Actinium's name-only redirect. */
public final class SamplerBindings {
    // Native LWJGL 2 checks for four slots even when this pname returns one integer.
    private static final ThreadLocal<IntBuffer> QUERY = ThreadLocal.withInitial(() -> BufferUtils.createIntBuffer(4));
    private SamplerBindings() { }

    public static int get(int unit) {
        IntBuffer buffer = QUERY.get();
        buffer.clear();
        GL30.glGetInteger(GL33.GL_SAMPLER_BINDING, unit, buffer);
        return buffer.get(0);
    }
}
