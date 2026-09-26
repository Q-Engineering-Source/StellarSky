package stellarium.client.ring;

import java.util.Objects;

import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Non-owning receipt for the exact depth attachment of Minecraft's managed framebuffer. */
public final class RingworldManagedDepth {
    private RingworldManagedDepth() {
    }

    /**
     * Inspects only the attachment already on the currently bound managed FBO.
     * It never rebinds, clears, attaches, or configures a framebuffer resource.
     */
    static Result inspect(Framebuffer framebuffer) {
        if (framebuffer == null) return rejected();
        return inspect(new MinecraftOwner(framebuffer), new NativeQuery());
    }

    static Result inspect(Owner owner, Query query) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(query, "query");
        Attachment depth = query.attachment(GL30.GL_DEPTH_ATTACHMENT);
        if (depth.objectName <= 0) return rejected(depth);
        if (depth.objectType == GL30.GL_RENDERBUFFER) {
            boolean managed = depth.objectName == owner.vanillaDepthRenderbuffer();
            if (!managed) return rejected(depth);
            RenderbufferFacts facts = query.renderbuffer(depth.objectName);
            boolean supported = exactDepthFormat(facts.internalFormat, owner.stencilEnabled())
                    && (!owner.stencilEnabled() || sameAttachment(depth, query.attachment(GL30.GL_STENCIL_ATTACHMENT)));
            return new Result(depth.objectType, depth.objectName, facts.internalFormat, facts.samples,
                    true, supported, facts.samples == 0 || facts.samples == 1);
        }
        if (depth.objectType != GL11.GL_TEXTURE) return rejected(depth);

        int expectedTexture = owner.depthTextureId();
        int versionBefore = owner.depthBufferVersion();
        boolean knownTexture = expectedTexture > 0 && depth.objectName == expectedTexture;
        if (!knownTexture) return rejected(depth);

        // GL_SAMPLES is defined only for a complete current framebuffer.  The
        // native implementation checks completion without rebinding the FBO.
        int samples = query.framebufferSamples();
        if (samples != 0) {
            boolean stable = expectedTexture == owner.depthTextureId() && versionBefore == owner.depthBufferVersion();
            return new Result(depth.objectType, depth.objectName, -1, samples, stable, false, false);
        }
        if (depth.textureLevel != 0) {
            boolean stable = expectedTexture == owner.depthTextureId() && versionBefore == owner.depthBufferVersion();
            return new Result(depth.objectType, depth.objectName, -1, 0, stable, false, true);
        }
        TextureFacts facts = query.texture2d(depth.objectName);
        boolean supported = facts.knownTexture
                && facts.width == owner.width() && facts.height == owner.height()
                && exactDepthFormat(facts.internalFormat, owner.stencilEnabled())
                && (!owner.stencilEnabled() || sameAttachment(depth, query.attachment(GL30.GL_STENCIL_ATTACHMENT)));
        boolean stable = expectedTexture == owner.depthTextureId() && versionBefore == owner.depthBufferVersion();
        return new Result(depth.objectType, depth.objectName, facts.internalFormat, 0,
                stable && facts.knownTexture, supported, true);
    }

    private static boolean sameAttachment(Attachment depth, Attachment stencil) {
        return stencil.objectType == depth.objectType && stencil.objectName == depth.objectName
                && stencil.textureLevel == depth.textureLevel;
    }

    private static boolean exactDepthFormat(int format, boolean stencil) {
        return stencil ? format == GL30.GL_DEPTH24_STENCIL8 : format == GL14.GL_DEPTH_COMPONENT24;
    }

    private static Result rejected() {
        return new Result(-1, -1, -1, -1, false, false, false);
    }

    private static Result rejected(Attachment attachment) {
        return new Result(attachment.objectType, attachment.objectName, -1, -1, false, false, false);
    }

    /** The public consumer contract: all three predicates must hold. */
    static record Result(int objectType, int objectName, int internalFormat, int samples,
                         boolean managedAttachment, boolean supportedFormat, boolean singleSample) {
        boolean accepted() {
            return managedAttachment && supportedFormat && singleSample;
        }
    }

    interface Owner {
        int width();
        int height();
        boolean stencilEnabled();
        int vanillaDepthRenderbuffer();
        int depthTextureId();
        int depthBufferVersion();
    }

    interface Query {
        Attachment attachment(int attachment);
        RenderbufferFacts renderbuffer(int name);
        int framebufferSamples();
        TextureFacts texture2d(int name);
    }

    record Attachment(int objectType, int objectName, int textureLevel) {
    }

    record RenderbufferFacts(int internalFormat, int samples) {
    }

    record TextureFacts(boolean knownTexture, int internalFormat, int width, int height) {
        static TextureFacts unknown() { return new TextureFacts(false, -1, -1, -1); }
    }

    private record MinecraftOwner(Framebuffer framebuffer, IRenderTargetExt iris) implements Owner {
        private MinecraftOwner(Framebuffer framebuffer) {
            this(framebuffer, framebuffer instanceof IRenderTargetExt extension ? extension : null);
        }

        @Override public int width() { return framebuffer.framebufferWidth; }
        @Override public int height() { return framebuffer.framebufferHeight; }
        @Override public boolean stencilEnabled() { return framebuffer.isStencilEnabled(); }
        @Override public int vanillaDepthRenderbuffer() { return framebuffer.depthBuffer; }
        @Override public int depthTextureId() { return iris == null ? -1 : iris.iris$getDepthTextureId(); }
        @Override public int depthBufferVersion() { return iris == null ? -1 : iris.iris$getDepthBufferVersion(); }
    }

    private static final class NativeQuery implements Query {
        @Override
        public Attachment attachment(int attachment) {
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int level = type == GL11.GL_TEXTURE
                    ? GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) : -1;
            return new Attachment(type, name, level);
        }

        @Override
        public RenderbufferFacts renderbuffer(int name) {
            return withBindings(() -> {
                if (!GL30.glIsRenderbuffer(name)) return new RenderbufferFacts(-1, -1);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
                return new RenderbufferFacts(
                        GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT),
                        GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES));
            });
        }

        @Override
        public int framebufferSamples() {
            // Do not query GL_SAMPLES from an incomplete target; this method
            // deliberately observes the existing binding and never changes it.
            return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
                    ? GL11.glGetInteger(GL13.GL_SAMPLES) : -1;
        }

        @Override
        public TextureFacts texture2d(int name) {
            return withBindings(() -> {
                // glBindTexture creates a name when it is absent, so check
                // first. The policy calls this only for the producer-owned id.
                if (!GL11.glIsTexture(name)) return TextureFacts.unknown();
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
                return new TextureFacts(true,
                        GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT),
                        GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH),
                        GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT));
            });
        }

        private static <T> T withBindings(QueryOperation<T> operation) {
            int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int texture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            try {
                return operation.query();
            } finally {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture2d);
                GL13.glActiveTexture(activeTexture);
            }
        }
    }

    @FunctionalInterface
    private interface QueryOperation<T> {
        T query();
    }
}
