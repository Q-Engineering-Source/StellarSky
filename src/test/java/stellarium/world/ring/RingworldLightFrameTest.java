package stellarium.world.ring;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RingworldLightFrameTest {
    @Test
    public void shadowChangesEffectiveSkyAtEachPositionWithoutExtinguishingBlockLight() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0), 512, 8, 0L);

        assertEquals(0, frame.effectiveSkyLight(15, 0, 0.0, 200, 0.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 5.0, 200, 0.0));
        assertEquals(12, frame.combinedLight(15, 12, 0, 0.0, 200, 0.0));
    }

    @Test
    public void weatherAndShadeAttenuateTheRemainingDaylightTogether() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 2.0), 512, 8, 0L);

        // x=4 is the midpoint of the feather: one half of the light passes.
        assertEquals(6, frame.effectiveSkyLight(15, 3, 4.0, 200, 0.0));
        assertEquals(3, frame.effectiveSkyLight(15, 10, 4.0, 200, 0.0));
        assertEquals(5, frame.effectiveSkyLight(15, 10, 10.0, 200, 0.0));
        assertEquals(0, frame.effectiveSkyLight(2, 3, 4.0, 200, 0.0));
    }

    @Test
    public void renderPackingChangesOnlySkyAndDoesNotApplyGlobalDarkeningTwice() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0), 512, 8, 0L);

        assertEquals(0x000000D0, frame.shadedPackedLight(0x00F000D0, 0.0, 200, 0.0));
        assertEquals(0x00F000D0, frame.shadedPackedLight(0x00F000D0, 5.0, 200, 0.0));
    }

    @Test
    public void boardCuboidKeepsTheLowerFaceAndInteriorInTheLocalFieldUntilItsUpperFace() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0), 512, 8, 0L);

        assertEquals(0, frame.effectiveSkyLight(15, 0, 0.0, 511, 0.0));
        assertEquals(0, frame.effectiveSkyLight(15, 0, 0.0, 512, 0.0));
        assertEquals(0, frame.effectiveSkyLight(15, 0, 0.0, 519, 0.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 0.0, 520, 0.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 0.0, 900, 0.0));
        assertEquals(0x000000D0, frame.shadedPackedLight(0x00F000D0, 0.0, 519, 0.0));
        assertEquals(0x00F000D0, frame.shadedPackedLight(0x00F000D0, 0.0, 520, 0.0));
        assertEquals(0x00F000D0, frame.shadedPackedLight(0x00F000D0, 0.0, -1, 0.0));
    }

    @Test
    public void opaqueBoardMaterialBlocksSoftInteriorCompletelyWhileItsGapRetainsCallerWeather() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(20.0, 10.0, 100L, 0.0, 0.0, 2.0), 512, 8, 0L);

        // x=4 is the soft field's 50% point, but it remains board material.
        assertEquals(8, frame.effectiveSkyLight(15, 0, 4.0, 511, 0.0));
        assertEquals(0, frame.effectiveSkyLight(15, 10, 4.0, 512, 0.0));
        assertEquals(0, frame.effectiveSkyLight(15, 10, 4.0, 519, 0.0));
        assertEquals(5, frame.effectiveSkyLight(15, 10, 6.0, 512, 0.0));
        assertEquals(5, frame.effectiveSkyLight(15, 10, 4.0, 520, 0.0));
        assertEquals(0x000000D0, frame.shadedPackedLight(0x00F000D0, 4.0, 512, 0.0));
        assertEquals(0x00F000D0, frame.shadedPackedLight(0x00F000D0, 6.0, 512, 0.0));
    }

    @Test
    public void receiverClassificationDistinguishesBothSidesOfTheVanillaBuildCeiling() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(10.0, 4.0, 100L, 0.0, 0.0, 0.0), 248, 8, 0L);

        assertEquals(0, frame.effectiveSkyLight(15, 0, 0.0, 255, 0.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 0.0, 256, 0.0));
        // An upper receiver removes ring attenuation, not native roof or BLOCK semantics.
        assertEquals(7, frame.effectiveSkyLight(7, 0, 0.0, 256, 0.0));
        assertEquals(12, frame.combinedLight(7, 12, 0, 0.0, 256, 0.0));
    }

    @Test
    public void finiteStripLeavesExteriorReceiversUnshadedWithoutChangingBlockLight() {
        RingworldLightFrame frame = new RingworldLightFrame(
                new RingworldSunshade(10.0, 10.0, 100L, 0.0, 0.0, 0.0), 512, 8, 0L);

        assertEquals(0, frame.effectiveSkyLight(15, 0, 0.0, 200, 0.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 0.0, 200, 8_192.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 0.0, 512, 8_192.0));
        assertEquals(15, frame.effectiveSkyLight(15, 0, 0.0, 520, 8_192.0));
        assertEquals(12, frame.combinedLight(0, 12, 0, 0.0, 200, 8_192.0));
        assertEquals(0x00F000D0, frame.shadedPackedLight(0x00F000D0, 0.0, 200, 8_192.0));
    }
}
