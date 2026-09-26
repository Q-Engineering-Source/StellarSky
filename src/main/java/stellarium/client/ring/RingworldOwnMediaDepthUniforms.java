package stellarium.client.ring;

import java.nio.IntBuffer;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import stellarium.render.util.SamplerBindings;

/** Gives late air the actual media winner, including its exact raster/screen-door coverage. */
final class RingworldOwnMediaDepthUniforms {
    private static final int UNIT = 4;
    private final int active, sampler, pixelOffset;
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);

    RingworldOwnMediaDepthUniforms(int program) {
        this(name -> OpenGlHelper.glGetUniformLocation(program, name));
    }

    RingworldOwnMediaDepthUniforms(ToIntFunction<String> lookup) {
        active = uniform(lookup, "uSSOwnMediaDepthActive");
        sampler = uniform(lookup, "uSSOwnMediaDepth");
        pixelOffset = uniform(lookup, "uSSOwnMediaDepthPixelOffset");
    }

    void upload() {
        GL20.glUniform1i(active, 0);
        var media = RingworldOwnMediaOcclusion.current();
        if (media == null) return;
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        if (viewport.get(2) != media.viewportWidth() || viewport.get(3) != media.viewportHeight()) {
            throw new IllegalStateException("Air and own-media raster dimensions do not match");
        }
        publish(media, viewport);
    }

    /**
     * Fail-closed upload for the deferred ground colour pass.
     *
     * <p>That pass draws whether or not this optical pass produced a usable
     * own-media capture, so a missing attachment or a raster that does not match
     * the current viewport disables the own-media discard for this draw instead
     * of aborting a frame that can still render its ground.</p>
     */
    void uploadFailClosed() {
        GL20.glUniform1i(active, 0);
        var media = RingworldOwnMediaOcclusion.current();
        if (media == null) return;
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        if (viewport.get(2) != media.viewportWidth() || viewport.get(3) != media.viewportHeight()) return;
        publish(media, viewport);
    }

    /** Selects the exact attachment texel for every fragment of the current raster. */
    private void publish(RingworldOwnMediaOcclusion.Snapshot media, IntBuffer raster) {
        GL20.glUniform2i(pixelOffset, media.viewportX() - raster.get(0), media.viewportY() - raster.get(1));
        GL20.glUniform1i(sampler, UNIT);
        GL20.glUniform1i(active, 1);
    }

    /**
     * Fail-closed variant of {@link #bindTexture()}: no live attachment for this
     * frame and scope means no unit is touched and the shader's inactive sentinel
     * keeps the discard off.
     */
    static Binding bindTextureOrNull() {
        return RingworldOwnMediaOcclusion.current() == null ? null : bindTexture();
    }

    static Binding bindTexture() {
        var media = RingworldOwnMediaOcclusion.current();
        if (media == null) throw new IllegalStateException("Curved air is missing this frame's own-media capture");
        int originalUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int sampler = SamplerBindings.get(UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, media.textureId());
        GL33.glBindSampler(UNIT, 0);
        GL13.glActiveTexture(originalUnit);
        return new Binding(originalUnit, texture, sampler);
    }

    static final class Binding implements AutoCloseable {
        private final int originalUnit, texture, sampler;
        private Binding(int originalUnit, int texture, int sampler) {
            this.originalUnit = originalUnit; this.texture = texture; this.sampler = sampler;
        }
        @Override public void close() {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL33.glBindSampler(UNIT, sampler);
            GL13.glActiveTexture(originalUnit);
        }
    }

    private static int uniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("Own-media depth uniform missing: " + name);
        return location;
    }
}
