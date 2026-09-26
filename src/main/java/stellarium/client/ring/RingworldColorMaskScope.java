package stellarium.client.ring;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Restores indexed MRT masks after Actinium's glPopAttrib reapplies a global color mask. */
final class RingworldColorMaskScope implements AutoCloseable {
    interface Access {
        int count();
        int read(int index);
        void write(int index, int mask);
    }

    private final Access access;
    private final int[] masks;
    private boolean closed;

    private RingworldColorMaskScope(Access access) {
        this.access = access;
        int count = access.count();
        if (count <= 0) throw new IllegalStateException("No MRT color masks available");
        masks = new int[count];
        for (int index = 0; index < count; index++) masks[index] = access.read(index);
    }

    static RingworldColorMaskScope capture() { return capture(new OpenGlAccess()); }
    static RingworldColorMaskScope capture(Access access) { return new RingworldColorMaskScope(access); }

    @Override public void close() {
        if (closed) return;
        for (int index = 0; index < masks.length; index++) access.write(index, masks[index]);
        closed = true;
    }

    private static final class OpenGlAccess implements Access {
        private final ByteBuffer components = BufferUtils.createByteBuffer(4);
        @Override public int count() { return GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS); }
        @Override public int read(int index) {
            components.clear();
            org.lwjglx.opengl.GL30.glGetBoolean(GL11.GL_COLOR_WRITEMASK, index, components);
            int result = 0;
            for (int channel = 0; channel < 4; channel++) if (components.get(channel) != 0) result |= 1 << channel;
            return result;
        }
        @Override public void write(int index, int mask) {
            GL30.glColorMaski(index, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
        }
    }
}
