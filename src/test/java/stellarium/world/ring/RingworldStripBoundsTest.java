package stellarium.world.ring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RingworldStripBoundsTest {
    @Test
    public void boardCoordinatesUseTheConfirmedHalfOpenZInterval() {
        assertTrue(RingworldStripBounds.insideBoard(-8_192.0));
        assertTrue(RingworldStripBounds.insideBoard(8_191.999_999));
        assertFalse(RingworldStripBounds.insideBoard(-8_192.000_001));
        assertFalse(RingworldStripBounds.insideBoard(8_192.0));
    }

    @Test
    public void blockClassificationSeparatesBoardTwoChunkWallsAndExteriorVoid() {
        assertEquals(RingworldStripBounds.StripRegion.VOID,
                RingworldStripBounds.classifyBlockZ(-8_225));
        assertEquals(RingworldStripBounds.StripRegion.NEGATIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyBlockZ(-8_224));
        assertEquals(RingworldStripBounds.StripRegion.NEGATIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyBlockZ(-8_193));
        assertEquals(RingworldStripBounds.StripRegion.BOARD,
                RingworldStripBounds.classifyBlockZ(-8_192));
        assertEquals(RingworldStripBounds.StripRegion.BOARD,
                RingworldStripBounds.classifyBlockZ(8_191));
        assertEquals(RingworldStripBounds.StripRegion.POSITIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyBlockZ(8_192));
        assertEquals(RingworldStripBounds.StripRegion.POSITIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyBlockZ(8_223));
        assertEquals(RingworldStripBounds.StripRegion.VOID,
                RingworldStripBounds.classifyBlockZ(8_224));
    }

    @Test
    public void chunkClassificationKeepsTheSameAlignedIntervals() {
        assertEquals(RingworldStripBounds.StripRegion.VOID,
                RingworldStripBounds.classifyChunkZ(-515));
        assertEquals(RingworldStripBounds.StripRegion.NEGATIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyChunkZ(-514));
        assertEquals(RingworldStripBounds.StripRegion.NEGATIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyChunkZ(-513));
        assertEquals(RingworldStripBounds.StripRegion.BOARD,
                RingworldStripBounds.classifyChunkZ(-512));
        assertEquals(RingworldStripBounds.StripRegion.BOARD,
                RingworldStripBounds.classifyChunkZ(511));
        assertEquals(RingworldStripBounds.StripRegion.POSITIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyChunkZ(512));
        assertEquals(RingworldStripBounds.StripRegion.POSITIVE_BOUNDARY_BAND,
                RingworldStripBounds.classifyChunkZ(513));
        assertEquals(RingworldStripBounds.StripRegion.VOID,
                RingworldStripBounds.classifyChunkZ(514));
    }
}
