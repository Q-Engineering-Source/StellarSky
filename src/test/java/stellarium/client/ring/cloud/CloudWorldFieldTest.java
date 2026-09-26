package stellarium.client.ring.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Field contracts: final coverage is canonical-column union, never a selected middle layer. */
public class CloudWorldFieldTest {
    private static final CloudFieldSettings FIELD = new CloudFieldSettings(0x5EED_F00DL, .45D, false, .20D);

    @Test public void coverageExtremesRemainAbsolute() {
        CloudColumn empty = new CloudColumn(), full = new CloudColumn();
        CloudWorldField.sampler(new CloudFieldSettings(9L, 0.0D, false, 0.0D)).fillColumn(0, 0, empty);
        CloudWorldField.sampler(new CloudFieldSettings(9L, 1.0D, false, 0.0D)).fillColumn(0, 0, full);
        assertFalse(any(empty));
        for (int color : full.argb) assertTrue((color >>> 24 & 255) != 0);
    }

    @Test public void worldColumnsRemainNonPeriodicAcrossFormer768Metres() {
        CloudWorldField.Sampler sampler = CloudWorldField.sampler(FIELD);
        CloudColumn first = new CloudColumn(), shifted = new CloudColumn();
        boolean differs = false;
        for (int z = 0; z < 64 && !differs; z++) for (int x = 0; x < 64 && !differs; x++) {
            sampler.fillColumn(x * 12.0D, z * 12.0D, first);
            sampler.fillColumn((x * 12.0D) + 768.0D, z * 12.0D, shifted);
            differs = !java.util.Arrays.equals(first.argb, shifted.argb);
        }
        assertTrue(differs);
    }

    @Test public void finalCoveredMeansAnyCanonicalLayerNotMiddleLayer() {
        CloudWorldField.Sampler sampler = CloudWorldField.sampler(new CloudFieldSettings(0L, .6D, true, .6D));
        boolean witnessed = false;
        CloudColumn column = new CloudColumn();
        for (int z = 0; z < 192 && !witnessed; z++) for (int x = 0; x < 192 && !witnessed; x++) {
            sampler.fillColumn(x * 12.0D, z * 12.0D, column);
            if (any(column) && (column.argb[3] >>> 24 & 255) == 0) witnessed = true;
        }
        assertTrue("canonical union must not be aliased to a forced middle core", witnessed);
    }

    @Test public void filteredTailRemainsNontrivialAndDoesNotAlternateAtEachCell() {
        CloudWorldField.Sampler sampler = CloudWorldField.sampler(FIELD);
        CloudColumn column = new CloudColumn(); boolean[] values = new boolean[64 * 64];
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            sampler.fillFilteredColumn((x - 32L) * 49_152.0D, (z + 17L) * 49_152.0D, 49_152.0D, column);
            values[z * 64 + x] = any(column);
        }
        int count = 0, transitions = 0;
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            if (values[z * 64 + x]) count++;
            if (x < 63 && values[z * 64 + x] != values[z * 64 + x + 1]) transitions++;
            if (z < 63 && values[z * 64 + x] != values[z * 64 + x + 64]) transitions++;
        }
        assertTrue(count > 0 && count < values.length); assertTrue(transitions < 2 * 64 * 63 / 4);
    }

    @Test public void largestLegalTailFootprintStillHasABoundedFilteredSampler() {
        CloudColumn column = new CloudColumn();
        CloudWorldField.sampler(FIELD).fillFilteredColumn(0.0D, 0.0D, 64.0D * 4_096.0D, column);
        // A column may be empty, but the maximum legal footprint must not throw or fall back to periodic noise.
        assertTrue(column.argb.length == CloudColumn.LAYERS);
    }

    private static boolean any(CloudColumn column) { for (int color : column.argb) if ((color >>> 24 & 255) != 0) return true; return false; }
}
