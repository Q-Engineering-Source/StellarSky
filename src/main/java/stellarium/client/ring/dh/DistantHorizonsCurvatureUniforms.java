package stellarium.client.ring.dh;

import stellarium.client.ring.RingworldCurvatureFrame;
import stellarium.client.ring.RingworldCurvedRayUniforms;
import stellarium.client.ring.RingworldOwnMediaOcclusion;
import stellarium.client.ring.RingworldRenderSnapshots;
import net.minecraft.client.renderer.OpenGlHelper;
import java.nio.IntBuffer;
import java.util.function.ToIntFunction;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/** Uniforms bound to one linked DH terrain program. */
public final class DistantHorizonsCurvatureUniforms {
    private final RingworldCurvedRayUniforms curvedRay;
    private final int dhCameraOffset;
    private final int ownMediaEnabled;
    private final int ownMediaDistance;
    private final int ownMediaPixelOffset;
    private final int localLightMode;
    private final int[] localLight = new int[6];
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);

    public DistantHorizonsCurvatureUniforms(int programId) {
        this(lookupForProgram(programId));
    }

    public DistantHorizonsCurvatureUniforms(ToIntFunction<String> lookup) {
        curvedRay = new RingworldCurvedRayUniforms(lookup);
        dhCameraOffset = uniform(lookup, "uSSDhCameraOffset");
        ownMediaEnabled = uniform(lookup, "uSSOwnMediaEnabled");
        ownMediaDistance = uniform(lookup, "uSSOwnMediaDistance");
        ownMediaPixelOffset = uniform(lookup, "uSSOwnMediaPixelOffset");
        localLightMode = uniform(lookup, "uSSDhLightMode");
        String[] lightNames = {"BandHigh", "BandLow", "BoundsHigh", "BoundsLow", "DirectionHigh", "DirectionLow"};
        for (int i = 0; i < lightNames.length; i++) localLight[i] = uniform(lookup, "uSSDh" + lightNames[i]);
    }

    private static ToIntFunction<String> lookupForProgram(int programId) {
        if (programId == 0) throw new IllegalArgumentException("Curved-ray uniform program must be non-zero");
        return name -> OpenGlHelper.glGetUniformLocation(programId, name);
    }

    /**
     * Publishes the unit-seven own-media distance contract only for the exact admitted frame.
     * The caller binds the texture around DH's terrain draw and retains ownership of that binding.
     */
    public void uploadOwnMedia(RingworldCurvatureFrame frame, RingworldOwnMediaOcclusion.Snapshot media) {
        GL20.glUniform1i(ownMediaEnabled, 0);
        if (frame == null) return;
        if (media == null || media.frame() != frame) {
            throw new IllegalStateException("Curved Distant Horizons draw lacks its matching own-media occlusion frame");
        }
        if (media.textureId() <= 0 || media.textureWidth() <= 0 || media.textureHeight() <= 0
                || media.viewportWidth() <= 0 || media.viewportHeight() <= 0) {
            throw new IllegalStateException("Own-media occlusion texture metadata is invalid");
        }
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int nativeX = viewport.get(0);
        int nativeY = viewport.get(1);
        int nativeWidth = viewport.get(2);
        int nativeHeight = viewport.get(3);
        if (nativeWidth != media.viewportWidth() || nativeHeight != media.viewportHeight()) {
            throw new IllegalStateException("Distant Horizons viewport does not match the own-media frame");
        }
        int offsetX = Math.subtractExact(media.viewportX(), nativeX);
        int offsetY = Math.subtractExact(media.viewportY(), nativeY);
        if (media.viewportX() < 0 || media.viewportY() < 0
                || media.viewportX() + media.viewportWidth() > media.textureWidth()
                || media.viewportY() + media.viewportHeight() > media.textureHeight()) {
            throw new IllegalStateException("Own-media viewport is outside its distance texture");
        }
        // The wrapper owns the unit-seven texture binding. Keep the sampler's unit explicit so a
        // caller's active unit cannot silently redirect texelFetch to an unrelated texture.
        GL20.glUniform1i(ownMediaDistance, 7);
        GL20.glUniform2i(ownMediaPixelOffset, offsetX, offsetY);
        GL20.glUniform1i(ownMediaEnabled, 1);
    }

    /**
     * DH's vertex buffers are relative to its exact camera, which is not assumed to equal SS's
     * optical eye in third-person views.  Disable first, upload the extra bridge coordinate, then
     * let the shared binder publish its four-frame contract and enabled bit last.
     */
    public void upload(RingworldCurvatureFrame frame, double cameraOffsetX, double cameraOffsetY,
                       double cameraOffsetZ) {
        curvedRay.upload(null);
        GL20.glUniform1i(localLightMode, 0);
        if (frame == null) return;
        var snapshot = RingworldRenderSnapshots.current();
        if (snapshot == null || !frame.belongsTo(snapshot.world(), snapshot.scene())) {
            throw new IllegalStateException("DH local lighting lacks its frozen display snapshot");
        }
        var light = DistantHorizonsLocalLight.from(snapshot, frame.renderOrigin());
        uploadSplit(0, light.band());
        uploadSplit(2, light.bounds());
        uploadSplit(4, light.direction());
        float x = finiteFloat(cameraOffsetX, "DH camera offset X");
        float y = finiteFloat(cameraOffsetY, "DH camera offset Y");
        float z = finiteFloat(cameraOffsetZ, "DH camera offset Z");
        GL20.glUniform3f(dhCameraOffset, x, y, z);
        curvedRay.upload(frame);
        GL20.glUniform1i(localLightMode, light.mode());
    }

    private void uploadSplit(int index, double[] values) {
        float[] high = new float[4];
        float[] low = new float[4];
        for (int i = 0; i < 4; i++) {
            high[i] = finiteFloat(values[i], "DH light parameter");
            low[i] = finiteFloat(values[i] - high[i], "DH light residual");
        }
        GL20.glUniform4f(localLight[index], high[0], high[1], high[2], high[3]);
        GL20.glUniform4f(localLight[index + 1], low[0], low[1], low[2], low[3]);
    }

    private static int uniform(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("Distant Horizons curvature shader lacks uniform " + name);
        return location;
    }

    private static float finiteFloat(double value, String name) {
        float result = (float) value;
        if (!Double.isFinite(value) || !Float.isFinite(result)) {
            throw new IllegalArgumentException(name + " must be finite and representable");
        }
        return result;
    }
}
