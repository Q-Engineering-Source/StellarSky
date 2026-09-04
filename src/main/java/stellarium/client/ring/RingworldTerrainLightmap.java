package stellarium.client.ring;

import com.cleanroommc.kirino.KirinoClientCore;
import com.cleanroommc.kirino.KirinoCommonCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.ARBShaderObjects;
import stellarium.world.StellarScene;
import stellarium.world.ring.RingworldDisplayLightField;
import stellarium.world.ring.RingworldTerrainLightGrid;

/**
 * Fixed-function vanilla terrain lightmap scope.
 *
 * <p>Chunk VBOs retain their raw SKY UVs. While a vanilla VBO/display-list
 * layer is rendered, the lightmap texture matrix receives one temporary
 * negative SKY-row translation per {@link RenderChunk}. GL_CLAMP_TO_EDGE then
 * realizes {@code max(rawSky - subtraction, 0)} without modifying BLOCK UVs.
 * Kirino's render delegate is deliberately unsupported here: it owns a
 * different terrain pipeline and must not inherit this fixed-function scope.</p>
 */
public final class RingworldTerrainLightmap {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final int LIGHTMAP_STEP = 16;

    private RingworldTerrainLightmap() {
    }

    /** Opens a scope only for the exact world/scene display field frozen by this render. */
    public static Scope openCurrentWorld() {
        if (kirinoRenderDelegateEnabled()) {
            return Scope.maskCurrent();
        }
        World world = Minecraft.getMinecraft().world;
        StellarScene scene = world == null ? null : StellarScene.getScene(world);
        RingworldDisplayLightField field = world == null || scene == null
                ? null : RingworldRenderSnapshots.currentDisplayLightFieldFor(world, scene);
        if (field == null || field.snapshot().phase() == null) {
            return Scope.maskCurrent();
        }
        if (currentProgram() != 0) {
            throw new IllegalStateException("Ringworld vanilla terrain lightmap requires fixed-function program 0");
        }
        return new Scope(field);
    }

    /** Applies the current scope's display-phase subtraction at this real RenderChunk origin. */
    public static void applyChunk(RenderChunk renderChunk) {
        Scope scope = CURRENT.get();
        if (scope == null || !scope.glScope) {
            return;
        }
        BlockPos origin = renderChunk.getPosition();
        scope.apply(RingworldTerrainLightGrid.sectionSkySubtraction(
                scope.field, origin.getX(), origin.getY(), origin.getZ()));
    }

    /** Pure UV algebra for the headless contract tests; inputs are vanilla packed-light nibbles. */
    public static TextureCoordinates terrainCoordinates(int rawBlock, int rawSky, int subtraction) {
        validateNibble("rawBlock", rawBlock);
        validateNibble("rawSky", rawSky);
        validateNibble("subtraction", subtraction);
        return new TextureCoordinates(LIGHTMAP_STEP * rawBlock,
                LIGHTMAP_STEP * Math.max(rawSky - subtraction, 0));
    }

    private static boolean kirinoRenderDelegateEnabled() {
        return KirinoCommonCore.KIRINO_CONFIG_HUB.isEnable()
                && KirinoCommonCore.KIRINO_CONFIG_HUB.isEnableRenderDelegate()
                && !KirinoClientCore.isRenderUnsupported();
    }

    private static int currentProgram() {
        if (!OpenGlHelper.shadersSupported) {
            return 0;
        }
        return OpenGlHelper.openGL21 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                : ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
    }

    private static void validateNibble(String name, int value) {
        if (value < 0 || value > 15) {
            throw new IllegalArgumentException(name + " must be within [0, 15]");
        }
    }

    public record TextureCoordinates(int blockCoordinate, int skyCoordinate) {
    }

    /** State is deliberately per nested render invocation and restored even when its layer throws. */
    public static final class Scope implements AutoCloseable {
        private final RingworldDisplayLightField field;
        private final Scope previous;
        private int activeTexture;
        private int matrixMode;
        private int lightmapTexture;
        private int wrapT;
        private boolean callerStateCaptured;
        private boolean matrixPushed;
        private boolean wrapChanged;
        private boolean glScope;
        private boolean open;
        private int currentSubtraction;
        private int suspendedPreviousSubtraction = -1;

        private Scope() {
            field = null;
            previous = CURRENT.get();
            try {
                suspendPrevious();
                open = true;
                CURRENT.set(this);
            } catch (RuntimeException | Error failure) {
                resumePreviousAfterFailure(failure);
                throw failure;
            }
        }

        private static Scope maskCurrent() {
            return new Scope();
        }

        private Scope(RingworldDisplayLightField field) {
            this.field = field;
            previous = CURRENT.get();
            try {
                suspendPrevious();
                activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
                matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
                callerStateCaptured = true;
                GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                lightmapTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                wrapT = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T);
                GlStateManager.matrixMode(GL11.GL_TEXTURE);
                GlStateManager.pushMatrix();
                matrixPushed = true;
                GlStateManager.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12Compat.GL_CLAMP_TO_EDGE);
                wrapChanged = true;
                glScope = true;
                open = true;
                CURRENT.set(this);
            } catch (RuntimeException | Error failure) {
                try {
                    if (matrixPushed || wrapChanged) {
                        restoreGlState();
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                resumePreviousAfterFailure(failure);
                throw failure;
            } finally {
                if (callerStateCaptured) {
                    GlStateManager.matrixMode(matrixMode);
                    GlStateManager.setActiveTexture(activeTexture);
                }
            }
        }

        private void apply(int subtraction) {
            if (!glScope) {
                return;
            }
            int callerActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int callerMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            try {
                if (GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != lightmapTexture) {
                    throw new IllegalStateException("Ringworld terrain lightmap binding changed inside its render scope");
                }
                GlStateManager.matrixMode(GL11.GL_TEXTURE);
                // Re-establish the single outer baseline before every RenderChunk.
                GlStateManager.popMatrix();
                GlStateManager.pushMatrix();
                GlStateManager.translate(0.0, -LIGHTMAP_STEP * (double) subtraction, 0.0);
                currentSubtraction = subtraction;
            } finally {
                GlStateManager.matrixMode(callerMatrixMode);
                GlStateManager.setActiveTexture(callerActiveTexture);
            }
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            try {
                if (glScope || matrixPushed || wrapChanged) {
                    restoreGlState();
                }
            } finally {
                open = false;
                glScope = false;
                try {
                    resumePrevious();
                } finally {
                    if (previous == null) {
                        CURRENT.remove();
                    } else {
                        CURRENT.set(previous);
                    }
                }
            }
        }

        private void suspendPrevious() {
            if (previous != null && previous.glScope) {
                suspendedPreviousSubtraction = previous.currentSubtraction;
                previous.apply(0);
            }
        }

        private void resumePrevious() {
            if (suspendedPreviousSubtraction >= 0) {
                int subtraction = suspendedPreviousSubtraction;
                suspendedPreviousSubtraction = -1;
                previous.apply(subtraction);
            }
        }

        private void resumePreviousAfterFailure(Throwable failure) {
            try {
                resumePrevious();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private void restoreGlState() {
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            try {
                GlStateManager.bindTexture(lightmapTexture);
                if (wrapChanged) {
                    GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrapT);
                    wrapChanged = false;
                }
                if (matrixPushed) {
                    GlStateManager.matrixMode(GL11.GL_TEXTURE);
                    GlStateManager.popMatrix();
                    matrixPushed = false;
                }
            } finally {
                GlStateManager.matrixMode(matrixMode);
                GlStateManager.setActiveTexture(activeTexture);
            }
        }
    }

    /** LWJGL 2's GL12 class is optional in old compile classpaths, but the token is stable OpenGL 1.2. */
    private static final class GL12Compat {
        private static final int GL_CLAMP_TO_EDGE = 33071;

        private GL12Compat() {
        }
    }
}
