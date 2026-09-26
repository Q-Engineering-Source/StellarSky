package stellarium.client.ring.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Exhaustive occupancy proof for the same-column reduction, not a text/implementation test. */
public class CloudColumnReductionPropertiesTest {
    @Test
    public void everyCanonicalMaskPreservesPresenceWithoutInventingAnUnsupportedGroup() {
        CloudColumn column = new CloudColumn();
        int[] reduced = new int[8];
        for (int mask = 0; mask < 256; mask++) {
            for (int i = 0; i < 8; i++) {
                column.argb[i] = (mask & (1 << i)) == 0 ? 0
                        : 0xE0000000 | (40 + i * 11) << 16 | (70 + i * 7) << 8 | (90 + i * 5);
            }
            for (int layers : new int[]{8, 4, 2, 1}) {
                CloudWorldField.reduceInto(layers, column, reduced);
                boolean hasOutput = false;
                for (int group = 0; group < layers; group++) {
                    if (reduced[group] == 0) continue;
                    hasOutput = true;
                    int sourceMask = ((1 << (8 / layers)) - 1) << (group * 8 / layers);
                    assertTrue("unsupported group mask=" + mask + ", layers=" + layers,
                            (mask & sourceMask) != 0);
                    assertTrue((reduced[group] >>> 24) >= 128);
                }
                assertEquals("presence mask=" + mask + ", layers=" + layers, mask != 0, hasOutput);
                if (layers == 8) assertArrayEquals(column.argb, reduced);
            }
        }
    }

    @Test
    public void aWeakSampleDoesNotOrFillEveryCoarseGroup() {
        CloudColumn column = new CloudColumn();
        column.argb[0] = 0xE0808080;
        column.argb[7] = 0xE0A0A0A0;
        int[] reduced = new int[2];
        CloudWorldField.reduceInto(2, column, reduced);
        // Neither group passes 50%; the stronger one retains column presence.
        assertEquals(0, reduced[0]);
        assertEquals(0xE0A0A0A0, reduced[1]);
    }

    @Test
    public void emptyInputClearsReusedOutputScratch() {
        CloudColumn column = new CloudColumn();
        int[] reduced = {0xE0FFFFFF, 0xE0FFFFFF};
        CloudWorldField.reduceInto(2, column, reduced);
        assertArrayEquals(new int[2], reduced);
    }

    @Test
    public void invalidReductionSizesFailBeforeOutputCanBeUsed() {
        CloudColumn column = new CloudColumn();
        assertThrows(IllegalArgumentException.class, () -> CloudWorldField.reduceInto(0, column, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> CloudWorldField.reduceInto(3, column, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> CloudWorldField.reduceInto(4, column, new int[2]));
    }
}
