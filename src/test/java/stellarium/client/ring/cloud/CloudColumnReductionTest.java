package stellarium.client.ring.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Same canonical volume is reduced vertically; it is never re-sampled at new Y midpoints. */
public class CloudColumnReductionTest {
    @Test public void anyFineColumnSurvivesEveryVerticalProxyAndProjection() {
        CloudColumn column = new CloudColumn();
        column.argb[1] = 0xE0A0B0C0;
        int[] four = new int[4], two = new int[2], projected = new int[1];
        CloudWorldField.reduceInto(4, column, four); CloudWorldField.reduceInto(2, column, two); CloudWorldField.reduceInto(1, column, projected);
        assertTrue(any(column.argb)); assertTrue(any(four)); assertTrue(any(two)); assertTrue(any(projected));
    }
    @Test public void exactFineColoursAndActualOccupiedAverageArePreserved() {
        CloudColumn column = new CloudColumn(); column.argb[0] = 0xE0102030; column.argb[1] = 0xE0304050;
        int[] fine = new int[8], four = new int[4]; CloudWorldField.reduceInto(8, column, fine); CloudWorldField.reduceInto(4, column, four);
        assertEquals(column.argb[0], fine[0]); assertEquals(column.argb[1], fine[1]); assertEquals(0xE0203040, four[0]);
    }
    @Test public void realSmallCellFacadePreservesCanonicalUnionAcross8To1() {
        CloudFieldSettings settings = new CloudFieldSettings(0x5EED_F00DL, .6D, true, .6D);
        boolean witnessed = false;
        for (long z = -96; z < 96 && !witnessed; z++) for (long x = -96; x < 96 && !witnessed; x++) {
            boolean fine = false;
            for (int layer = 0; layer < 8; layer++) fine |= CloudWorldField.occupied(settings, x, layer, z, 8, 12.0D);
            boolean four = anyFacade(settings, x, z, 4), two = anyFacade(settings, x, z, 2), projected = anyFacade(settings, x, z, 1);
            assertEquals(fine, four); assertEquals(fine, two); assertEquals(fine, projected);
            witnessed = fine;
        }
        assertTrue(witnessed);
    }
    private static boolean anyFacade(CloudFieldSettings settings, long x, long z, int layers) { for (int layer = 0; layer < layers; layer++) if (CloudWorldField.occupied(settings, x, layer, z, layers, 12.0D)) return true; return false; }
    private static boolean any(int[] colors) { for (int color : colors) if ((color >>> 24 & 255) != 0) return true; return false; }
}
