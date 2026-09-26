package stellarium.client.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

public class RingworldManagedDepthTest {
    @Test
    public void acceptsVanillaSingleSampleD24Renderbuffer() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 7, -1, -1);
        FakeQuery query = new FakeQuery(new RingworldManagedDepth.Attachment(GL30.GL_RENDERBUFFER, 7, -1));
        query.renderbuffer = new RingworldManagedDepth.RenderbufferFacts(GL14.GL_DEPTH_COMPONENT24, 1);

        RingworldManagedDepth.Result result = RingworldManagedDepth.inspect(owner, query);

        assertTrue(result.managedAttachment());
        assertTrue(result.supportedFormat());
        assertTrue(result.singleSample());
        assertTrue(result.accepted());
        assertEquals(7, result.objectName());
    }

    @Test
    public void acceptsProducerOwnedSingleSampleD24S8TextureWithSharedStencil() {
        FakeOwner owner = new FakeOwner(1920, 1009, true, 0, 31, 8);
        FakeQuery query = textureQuery(31, 0);
        query.stencil = new RingworldManagedDepth.Attachment(GL11.GL_TEXTURE, 31, 0);
        query.texture = new RingworldManagedDepth.TextureFacts(true, GL30.GL_DEPTH24_STENCIL8, 1920, 1009);

        RingworldManagedDepth.Result result = RingworldManagedDepth.inspect(owner, query);

        assertTrue(result.accepted());
        assertEquals(0, result.samples());
        assertEquals(GL30.GL_DEPTH24_STENCIL8, result.internalFormat());
    }

    @Test
    public void rejectsForeignOrZeroTextureNamesBeforeAnyTextureProbe() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 0, 31, 8);
        FakeQuery foreign = textureQuery(47, 0);
        assertFalse(RingworldManagedDepth.inspect(owner, foreign).managedAttachment());
        assertEquals(0, foreign.textureQueries);

        FakeQuery zero = textureQuery(0, 0);
        assertFalse(RingworldManagedDepth.inspect(owner, zero).managedAttachment());
        assertEquals(0, zero.textureQueries);
    }

    @Test
    public void acceptsProducerOwnedSizedD24TextureWithoutStencil() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 0, 31, 8);
        assertTrue(RingworldManagedDepth.inspect(owner, textureQuery(31, 0)).accepted());
    }

    @Test
    public void rejectsStaleKnownIdWhoseTextureObjectNoLongerExists() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 0, 31, 8);
        FakeQuery query = textureQuery(31, 0);
        query.texture = RingworldManagedDepth.TextureFacts.unknown();

        RingworldManagedDepth.Result result = RingworldManagedDepth.inspect(owner, query);

        assertFalse(result.managedAttachment());
        assertFalse(result.supportedFormat());
        assertFalse(result.accepted());
    }

    @Test
    public void rejectsWrongMipOrDimensionsEvenForTheCurrentProducerTexture() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 0, 31, 8);
        FakeQuery wrongMip = textureQuery(31, 1);
        RingworldManagedDepth.Result mipResult = RingworldManagedDepth.inspect(owner, wrongMip);
        assertTrue(mipResult.managedAttachment());
        assertFalse(mipResult.supportedFormat());
        assertEquals(0, wrongMip.textureQueries);

        FakeQuery wrongSize = textureQuery(31, 0);
        wrongSize.texture = new RingworldManagedDepth.TextureFacts(true, GL14.GL_DEPTH_COMPONENT24, 1919, 1009);
        RingworldManagedDepth.Result sizeResult = RingworldManagedDepth.inspect(owner, wrongSize);
        assertTrue(sizeResult.managedAttachment());
        assertFalse(sizeResult.supportedFormat());
    }

    @Test
    public void rejectsWrongFormatAndSplitStencilAttachment() {
        FakeOwner nonStencil = new FakeOwner(1920, 1009, false, 0, 31, 8);
        FakeQuery wrongFormat = textureQuery(31, 0);
        wrongFormat.texture = new RingworldManagedDepth.TextureFacts(true, GL30.GL_DEPTH24_STENCIL8, 1920, 1009);
        assertFalse(RingworldManagedDepth.inspect(nonStencil, wrongFormat).supportedFormat());

        FakeOwner stencil = new FakeOwner(1920, 1009, true, 0, 31, 8);
        FakeQuery splitStencil = textureQuery(31, 0);
        splitStencil.stencil = new RingworldManagedDepth.Attachment(GL11.GL_TEXTURE, 32, 0);
        splitStencil.texture = new RingworldManagedDepth.TextureFacts(true, GL30.GL_DEPTH24_STENCIL8, 1920, 1009);
        assertFalse(RingworldManagedDepth.inspect(stencil, splitStencil).supportedFormat());
    }

    @Test
    public void rejectsMultisampleAndChangingProducerVersion() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 0, 31, 8);
        FakeQuery multisample = textureQuery(31, 0);
        multisample.samples = 4;
        RingworldManagedDepth.Result multisampleResult = RingworldManagedDepth.inspect(owner, multisample);
        assertTrue(multisampleResult.managedAttachment());
        assertFalse(multisampleResult.singleSample());
        assertEquals(4, multisampleResult.samples());
        assertEquals(0, multisample.textureQueries);

        FakeQuery changingVersion = textureQuery(31, 0);
        changingVersion.afterTextureQuery = () -> owner.version = 9;
        RingworldManagedDepth.Result changedResult = RingworldManagedDepth.inspect(owner, changingVersion);
        assertFalse(changedResult.managedAttachment());
        assertFalse(changedResult.accepted());
    }

    private static FakeQuery textureQuery(int texture, int level) {
        FakeQuery query = new FakeQuery(new RingworldManagedDepth.Attachment(GL11.GL_TEXTURE, texture, level));
        query.samples = 0;
        query.texture = new RingworldManagedDepth.TextureFacts(true, GL14.GL_DEPTH_COMPONENT24, 1920, 1009);
        return query;
    }

    @Test
    public void rejectsProducerChangesDuringTheFinalStencilInspection() {
        FakeOwner owner = new FakeOwner(1920, 1009, true, 0, 31, 8);
        FakeQuery query = textureQuery(31, 0);
        query.stencil = new RingworldManagedDepth.Attachment(GL11.GL_TEXTURE, 31, 0);
        query.texture = new RingworldManagedDepth.TextureFacts(true, GL30.GL_DEPTH24_STENCIL8, 1920, 1009);
        query.afterStencilQuery = () -> owner.version++;
        assertFalse(RingworldManagedDepth.inspect(owner, query).accepted());
    }

    @Test
    public void rejectsAnIdChangeEvenWhenTheVersionStaysEqual() {
        FakeOwner owner = new FakeOwner(1920, 1009, false, 0, 31, 8);
        FakeQuery query = textureQuery(31, 0);
        query.afterTextureQuery = () -> owner.textureId = 32;
        assertFalse(RingworldManagedDepth.inspect(owner, query).accepted());
    }

    private static final class FakeOwner implements RingworldManagedDepth.Owner {
        private final int width, height, vanillaDepth;
        private int textureId;
        private final boolean stencil;
        private int version;

        private FakeOwner(int width, int height, boolean stencil, int vanillaDepth, int textureId, int version) {
            this.width = width;
            this.height = height;
            this.stencil = stencil;
            this.vanillaDepth = vanillaDepth;
            this.textureId = textureId;
            this.version = version;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public boolean stencilEnabled() { return stencil; }
        @Override public int vanillaDepthRenderbuffer() { return vanillaDepth; }
        @Override public int depthTextureId() { return textureId; }
        @Override public int depthBufferVersion() { return version; }
    }

    private static final class FakeQuery implements RingworldManagedDepth.Query {
        private final RingworldManagedDepth.Attachment depth;
        private RingworldManagedDepth.Attachment stencil = new RingworldManagedDepth.Attachment(-1, -1, -1);
        private RingworldManagedDepth.RenderbufferFacts renderbuffer = new RingworldManagedDepth.RenderbufferFacts(-1, -1);
        private RingworldManagedDepth.TextureFacts texture = RingworldManagedDepth.TextureFacts.unknown();
        private int samples = -1;
        private int textureQueries;
        private Runnable afterTextureQuery = () -> { };
        private Runnable afterStencilQuery = () -> { };

        private FakeQuery(RingworldManagedDepth.Attachment depth) {
            this.depth = depth;
        }

        @Override public RingworldManagedDepth.Attachment attachment(int attachment) {
            if (attachment == GL30.GL_DEPTH_ATTACHMENT) return depth;
            afterStencilQuery.run();
            return stencil;
        }
        @Override public RingworldManagedDepth.RenderbufferFacts renderbuffer(int name) { return renderbuffer; }
        @Override public int framebufferSamples() { return samples; }
        @Override public RingworldManagedDepth.TextureFacts texture2d(int name) {
            textureQueries++;
            afterTextureQuery.run();
            return texture;
        }
    }
}
