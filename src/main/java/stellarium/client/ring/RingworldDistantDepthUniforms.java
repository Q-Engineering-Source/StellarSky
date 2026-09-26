package stellarium.client.ring;

import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import stellarium.client.ring.dh.DistantHorizonsDepthBridge;
import stellarium.render.util.SamplerBindings;

/** Uniform bindings for the borrowed, frame-checked DH depth on reserved texture unit three. */
public final class RingworldDistantDepthUniforms {
    private final int active, sampler, inverse, viewport, camera;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    public RingworldDistantDepthUniforms(int program) {
        this(name -> OpenGlHelper.glGetUniformLocation(program, name));
    }

    RingworldDistantDepthUniforms(ToIntFunction<String> lookup) {
        active = uniform(lookup, "uSSDhDepthActive");
        sampler = uniform(lookup, "uSSDhDepth");
        inverse = uniform(lookup, "uSSDhInverseViewProjection");
        viewport = uniform(lookup, "uSSDhViewport");
        camera = uniform(lookup, "uSSDhCameraOffset");
    }

    public void upload() {
        GL20.glUniform1i(active, 0);
        var depth = DistantHorizonsDepthBridge.current();
        if (depth == null) return;
        matrix.clear();
        matrix.put(depth.inverseViewProjection()).flip();
        GL20.glUniformMatrix4(inverse, false, matrix);
        GL20.glUniform4f(viewport, depth.viewportX(), depth.viewportY(), depth.viewportWidth(), depth.viewportHeight());
        GL20.glUniform3f(camera, finite(depth.cameraOffsetX()), finite(depth.cameraOffsetY()), finite(depth.cameraOffsetZ()));
        GL20.glUniform1i(sampler, 3);
        GL20.glUniform1i(active, 1);
    }

    /** Holds the borrowed binding across all late own-media draws and restores the caller's unit. */
    static TextureBinding bindTexture() {
        var depth = DistantHorizonsDepthBridge.current();
        if (depth == null) return new TextureBinding(-1, -1, -1);
        if (!depth.frame().matchesViewport(depth.viewportX(), depth.viewportY(),
                depth.viewportWidth(), depth.viewportHeight())) {
            throw new IllegalStateException("DH depth viewport does not match the frozen optical frame");
        }
        int originalUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        int originalTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int originalSampler = SamplerBindings.get(3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth.textureId());
        GL33.glBindSampler(3, 0);
        GL13.glActiveTexture(originalUnit);
        return new TextureBinding(originalUnit, originalTexture, originalSampler);
    }

    static final class TextureBinding implements AutoCloseable {
        private final int originalUnit, originalTexture, originalSampler;
        private TextureBinding(int originalUnit, int originalTexture, int originalSampler) {
            this.originalUnit = originalUnit;
            this.originalTexture = originalTexture;
            this.originalSampler = originalSampler;
        }
        @Override public void close() {
            if (originalUnit < 0) return;
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, originalTexture);
            GL33.glBindSampler(3, originalSampler);
            GL13.glActiveTexture(originalUnit);
        }
    }

    private static int uniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("Distant-depth shader lacks uniform " + name);
        return location;
    }

    private static float finite(double value) {
        float result = (float) value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException("DH camera offset exceeds float range");
        return result;
    }
}
