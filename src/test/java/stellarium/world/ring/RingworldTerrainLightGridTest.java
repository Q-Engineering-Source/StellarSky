package stellarium.world.ring;

import static org.junit.Assert.assertEquals;

import java.util.UUID;
import org.junit.Test;

/** The terrain path samples one frozen display field at the actual 16-cube section centre. */
public class RingworldTerrainLightGridTest {
    @Test
    public void sectionCentreFollowsTheFrozenPhaseRatherThanAnyWorldClock() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0);
        RingworldTerrainLightGrid before = new RingworldTerrainLightGrid(field(sunshade,
                sunshade.phase(0L, 0L, 0.0)), -8, 503, -8);
        RingworldTerrainLightGrid after = new RingworldTerrainLightGrid(field(sunshade,
                sunshade.phase(50L, 50L, 0.0)), -8, 503, -8);

        assertEquals(0.0, before.centerX(), 0.0);
        assertEquals(511.0, before.centerY(), 0.0);
        assertEquals(0.0, before.centerZ(), 0.0);
        assertEquals(15, before.sectionSkySubtraction());
        assertEquals(0, after.sectionSkySubtraction());
    }

    @Test
    public void sectionCentreUsesDoubleCoordinatesAtNegativeAndHighHeights() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);
        RingworldDisplayLightField field = field(sunshade, sunshade.phase(0L, 0L, 0.0));

        assertEquals(15, new RingworldTerrainLightGrid(field, -8, -8, -8).sectionSkySubtraction());
        assertEquals(0, new RingworldTerrainLightGrid(field, -8, 512, -8).sectionSkySubtraction());
    }

    @Test
    public void largeSectionOriginsDoNotOverflowBeforeBecomingDoubleCentres() {
        RingworldSunshade sunshade = new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0);
        RingworldTerrainLightGrid grid = new RingworldTerrainLightGrid(field(sunshade,
                sunshade.phase(0L, 0L, 0.0)), Integer.MAX_VALUE, Integer.MIN_VALUE, 0);

        assertEquals((double) Integer.MAX_VALUE + 8.0, grid.centerX(), 0.0);
        assertEquals((double) Integer.MIN_VALUE + 8.0, grid.centerY(), 0.0);
        assertEquals(8.0, grid.centerZ(), 0.0);
        assertEquals(15, grid.sectionSkySubtraction());
    }

    private static RingworldDisplayLightField field(RingworldSunshade sunshade, RingworldSunshade.Phase phase) {
        UUID generation = UUID.randomUUID();
        RingworldClockMirror.DisplayTime displayTime = new RingworldClockMirror.DisplayTime(
                new RingworldClockSample(0, generation, 1L, phase.previousTime(), true),
                new RingworldClockSample(0, generation, 2L, phase.currentTime(), false), phase.fraction());
        return new RingworldDisplayLightField(new RingworldDisplaySnapshot(new Object(), new Object(), displayTime,
                sunshade, phase, 512, 8, new RingworldRenderObserver(0.0, 64.0, 0.0), 1.0, 0.0));
    }
}
