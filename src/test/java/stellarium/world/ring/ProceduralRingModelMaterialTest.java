package stellarium.world.ring;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import org.junit.Test;

/** Headless contracts for the cached procedural far-ring material inputs. */
public class ProceduralRingModelMaterialTest {
    private static final ProceduralRingModelMaterial.Settings FIXTURE =
            new ProceduralRingModelMaterial.Settings(724_091L, 0.45D, 0.20D, 64, 16);

    @Test
    public void generationIsDeterministicAndSettingsAreTheCompleteCacheKey() {
        var first = ProceduralRingModelMaterial.generate(FIXTURE);
        var second = ProceduralRingModelMaterial.generate(FIXTURE);
        assertEquals(FIXTURE, first.settings());
        assertArrayEquals(first.surfaceRgba8(), second.surfaceRgba8());
        assertArrayEquals(first.cloudRgba8(), second.cloudRgba8());

        var changedSeed = ProceduralRingModelMaterial.generate(
                new ProceduralRingModelMaterial.Settings(FIXTURE.seed() + 1L, 0.45D, 0.20D, 64, 16));
        assertFalse(java.util.Arrays.equals(first.surfaceRgba8(), changedSeed.surfaceRgba8()));
        assertFalse(java.util.Arrays.equals(first.cloudRgba8(), changedSeed.cloudRgba8()));
    }

    @Test
    public void longitudeSamplingIsPeriodicAtTheSeamAndForFractionalMovement() {
        var assets = ProceduralRingModelMaterial.generate(FIXTURE);
        for (double v : new double[] {0.0D, 0.08D, 0.5D, 0.9375D, 1.0D}) {
            assertEquals(assets.surfaceAt(0.0D, v), assets.surfaceAt(1.0D, v));
            assertEquals(assets.cloudAt(0.0D, v), assets.cloudAt(1.0D, v));
        }
        for (double u : new double[] {-2.625D, -0.125D, 0.013D, 0.497D, 1.875D}) {
            assertEquals(assets.surfaceAt(u, 0.37D), assets.surfaceAt(u + 1.0D, 0.37D));
            assertEquals(assets.cloudAt(u, 0.37D), assets.cloudAt(u + 3.0D, 0.37D));
        }
    }

    @Test
    public void clearAndOvercastEndpointsHaveExplicitCoverageSemantics() {
        var clear = ProceduralRingModelMaterial.generate(
                new ProceduralRingModelMaterial.Settings(7L, 0.0D, 0.75D, 64, 16));
        for (byte value : clear.cloudRgba8()) assertEquals(0, value & 0xFF);

        var overcast = ProceduralRingModelMaterial.generate(
                new ProceduralRingModelMaterial.Settings(7L, 1.0D, 0.75D, 64, 16));
        byte[] rgba = overcast.cloudRgba8();
        for (int offset = 3; offset < rgba.length; offset += ProceduralRingModelMaterial.CHANNELS) {
            assertEquals(255, rgba[offset] & 0xFF);
        }
        assertNotEquals(new ProceduralRingModelMaterial.Rgba(0, 0, 0, 0), overcast.cloudAt(0.33D, 0.66D));
    }

    @Test
    public void assetsDoNotExposeMutableCachedArraysAndBuffersAreReadOnlyCopies() {
        var assets = ProceduralRingModelMaterial.generate(FIXTURE);
        byte[] changed = assets.surfaceRgba8();
        int original = changed[0] & 0xFF;
        changed[0] = (byte) (original ^ 0xFF);
        assertEquals(original, assets.surfaceRgba8()[0] & 0xFF);

        ByteBuffer view = assets.cloudRgba8Buffer();
        assertTrue(view.isReadOnly());
        assertEquals(assets.cloudRgba8().length, view.remaining());
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> view.put(0, (byte) 1));
        assertEquals(assets.byteSize(), assets.surfaceRgba8().length + assets.cloudRgba8().length);
    }

    @Test
    public void dimensionsAndBudgetAreFailFastRatherThanSilentlyDownscaled() {
        assertEquals(1_048_576, ProceduralRingModelMaterial.MAX_TEXTURE_BYTES);
        assertEquals(1_048_576, ProceduralRingModelMaterial.DEFAULT.width()
                * ProceduralRingModelMaterial.DEFAULT.height() * ProceduralRingModelMaterial.CHANNELS);
        assertThrows(IllegalArgumentException.class,
                () -> new ProceduralRingModelMaterial.Settings(0L, 0.4D, 0.2D, 1, 128));
        assertThrows(IllegalArgumentException.class,
                () -> new ProceduralRingModelMaterial.Settings(0L, 0.4D, 0.2D, 2_049, 128));
        assertThrows(IllegalArgumentException.class,
                () -> new ProceduralRingModelMaterial.Settings(0L, Double.NaN, 0.2D, 64, 16));
        assertThrows(NullPointerException.class,
                () -> ProceduralRingModelMaterial.generate(null));
    }

    @Test
    public void interruptedGenerationCancelsBeforeTheFirstTextureRowWithoutClearingTheInterrupt() {
        Thread testThread = Thread.currentThread();
        boolean originallyInterrupted = testThread.isInterrupted();
        testThread.interrupt();
        try {
            CancellationException exception = assertThrows(CancellationException.class,
                    () -> ProceduralRingModelMaterial.generate(FIXTURE));
            assertTrue(exception.getMessage().contains("interrupted before row 0"));
            assertTrue(testThread.isInterrupted());
        } finally {
            // Restore the JUnit worker to the exact incoming interrupt state.
            Thread.interrupted();
            if (originallyInterrupted) testThread.interrupt();
        }
    }
}
