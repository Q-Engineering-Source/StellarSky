package stellarium.render.util;

import java.util.Objects;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import stellarium.util.OpenGlUtil;

/** Internal ownership scope for framebuffer allocation/deletion, not a world-render target switch. */
final class FramebufferBindings implements AutoCloseable {
    interface Access {
        int readFramebuffer();
        int drawFramebuffer();
        void restore(int readFramebuffer, int drawFramebuffer);
    }

    private final Access access;
    private int readFramebuffer;
    private int drawFramebuffer;
    private boolean closed;

    private FramebufferBindings(Access access) {
        this.access = Objects.requireNonNull(access, "access");
        readFramebuffer = access.readFramebuffer();
        drawFramebuffer = access.drawFramebuffer();
    }

    static FramebufferBindings capture() {
        return capture(new OpenGlAccess());
    }

    /** The same ownership logic is exercised by the GL adapter and headless state-contract tests. */
    static FramebufferBindings capture(Access access) {
        return new FramebufferBindings(access);
    }

    /** A deleted name cannot be rebound: only saved sides owning that exact name fall back to 0. */
    void deleted(int framebuffer) {
        if (closed) throw new IllegalStateException("Framebuffer binding scope is closed");
        if (framebuffer <= 0) return;
        if (readFramebuffer == framebuffer) readFramebuffer = 0;
        if (drawFramebuffer == framebuffer) drawFramebuffer = 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        access.restore(readFramebuffer, drawFramebuffer);
    }

    private static final class OpenGlAccess implements Access {
        private final boolean separateTargets;

        private OpenGlAccess() {
            if (!OpenGlUtil.FRAMEBUFFER_SUPPORTED) {
                throw new IllegalStateException("Framebuffer allocation requires framebuffer support");
            }
            // Match OpenGlUtil's CORE -> ARB -> EXT selection, including legacy EXT-only mode.
            ContextCapabilities capabilities = GLContext.getCapabilities();
            separateTargets = capabilities.OpenGL30 || capabilities.GL_ARB_framebuffer_object;
        }

        @Override
        public int readFramebuffer() {
            return GL11.glGetInteger(separateTargets ? GL30.GL_READ_FRAMEBUFFER_BINDING
                    : OpenGlUtil.FRAMEBUFFER_BINDING);
        }

        @Override
        public int drawFramebuffer() {
            return GL11.glGetInteger(OpenGlUtil.FRAMEBUFFER_BINDING);
        }

        @Override
        public void restore(int readFramebuffer, int drawFramebuffer) {
            if (separateTargets) {
                OpenGlUtil.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
                OpenGlUtil.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            } else {
                OpenGlUtil.bindFramebuffer(OpenGlUtil.FRAMEBUFFER_GL, drawFramebuffer);
            }
        }
    }
}
