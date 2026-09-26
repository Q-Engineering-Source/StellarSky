package stellarium.client.ring;

import java.nio.IntBuffer;
import java.util.function.ToIntFunction;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Cloud visibility consumes the actual selected mesh surface, not its ideal cylindrical shell. */
final class RingworldBoardDepthUniforms {
    private final int active, high, low, pixelOffset;
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);

    RingworldBoardDepthUniforms(ToIntFunction<String> lookup) {
        active = required(lookup, "uSSBoardMeshDepthActive");
        high = required(lookup, "uSSBoardMeshHigh");
        low = required(lookup, "uSSBoardMeshLow");
        pixelOffset = required(lookup, "uSSBoardMeshPixelOffset");
    }

    void upload() {
        GL20.glUniform1i(active, 0);
        var selection = RingworldBoardMeshDistance.current();
        if (selection == null) return;
        viewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        if (viewport.get(2) != selection.width() || viewport.get(3) != selection.height()) {
            throw new IllegalStateException("Cloud and selected board raster dimensions differ");
        }
        GL20.glUniform1i(high, 5);
        GL20.glUniform1i(low, 6);
        GL20.glUniform2i(pixelOffset, viewport.get(0), viewport.get(1));
        GL20.glUniform1i(active, 1);
    }

    private static int required(ToIntFunction<String> lookup, String name) {
        int location = lookup.applyAsInt(name);
        if (location < 0) throw new IllegalStateException("Selected board uniform missing: " + name);
        return location;
    }
}
