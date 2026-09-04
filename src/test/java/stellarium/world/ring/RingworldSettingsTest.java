package stellarium.world.ring;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public class RingworldSettingsTest {

    @Test
    public void newEnabledConfigurationMovesAt465MetersPerSecondOverATwentyFourHourCycle() {
        Configuration config = new Configuration();
        RingworldSettings settings = new RingworldSettings();
        settings.setupConfig(config, "ringworld");
        config.get("ringworld", "Enabled", false).set(true);
        settings.loadFromConfig(config, "ringworld");
        NBTTagCompound saved = new NBTTagCompound();
        settings.writeToNBT(saved);
        assertEquals(1_728_000, saved.getInteger("cycleTicks"));
        assertEquals(40_176_000.0, saved.getDouble("spacingBlocks"), 0.0);
        assertEquals(23.25, settings.sunshade().phase(1L, 1L, 1.0).panelCenterBlocks(), 1.0e-9);
        assertEquals(1_674_000.0, settings.sunshade().phase(72_000L, 72_000L, 1.0).panelCenterBlocks(), 1.0e-8);
        assertEquals(0.0, settings.sunshade().phase(1_728_000L, 1_728_000L, 1.0).panelCenterBlocks(), 0.0);
    }

    @Test
    public void newBoardUsesTheSlowGroundViewEngineeringHeightWithoutChangingThickness() {
        RingworldSettings settings = new RingworldSettings();
        Configuration config = new Configuration();
        settings.setupConfig(config, "ringworld");
        settings.loadFromConfig(config, "ringworld");
        assertEquals(6_400_000, settings.sunshadeHeightBlocks());
        assertEquals(8, settings.sunshadeThicknessBlocks());
        // A legacy scene explicitly stored its prototype height and must keep it.
        settings.readFromNBT(schemaTwoTag(512, 8));
        assertEquals(512, settings.sunshadeHeightBlocks());
    }

    @Test
    public void configurationExposesTheThinAtmosphereFadeStartAsAWorldRestartSetting() {
        Configuration config = new Configuration();
        RingworldSettings settings = new RingworldSettings();
        settings.setupConfig(config, "ringworld");
        Property property = config.get("ringworld", "Thin_Atmosphere_Fade_Start_Y", 192.0);
        assertTrue(property.requiresWorldRestart());
        assertEquals(192.0, property.getDouble(), 0.0);

        property.set(200.0);
        settings.loadFromConfig(config, "ringworld");

        assertEquals(200.0, settings.thinAtmosphereFadeStartY(), 0.0);
        Configuration savedConfig = new Configuration();
        settings.setupConfig(savedConfig, "ringworld");
        settings.saveToConfig(savedConfig, "ringworld");
        assertEquals(200.0, savedConfig.get("ringworld", "Thin_Atmosphere_Fade_Start_Y", 192.0).getDouble(), 0.0);
    }

    @Test
    public void schemasOneAndTwoMigrateTheThinAtmosphereFadeStartWithoutChangingLegacyGeometry() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(enabledTag());
        assertEquals(512, settings.sunshadeHeightBlocks());
        assertEquals(192.0, settings.thinAtmosphereFadeStartY(), 0.0);

        settings.readFromNBT(schemaTwoTag(768, 12));
        assertEquals(768, settings.sunshadeHeightBlocks());
        assertEquals(192.0, settings.thinAtmosphereFadeStartY(), 0.0);

        NBTTagCompound saved = new NBTTagCompound();
        settings.writeToNBT(saved);
        assertEquals(3, saved.getInteger("schemaVersion"));
        assertTrue(saved.hasKey("thinAtmosphereFadeStartY", 6));
        assertEquals(192.0, saved.getDouble("thinAtmosphereFadeStartY"), 0.0);
    }

    @Test
    public void schemaThreeRejectsMissingWrongTypedNonFiniteAndOutOfRangeThinAtmosphereStateWithoutReplacement() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(schemaThreeTag(200.0));
        RingworldSunshade accepted = settings.sunshade();

        NBTTagCompound missing = schemaThreeTag(200.0);
        missing.removeTag("thinAtmosphereFadeStartY");
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(missing));

        NBTTagCompound wrongType = schemaThreeTag(200.0);
        wrongType.setInteger("thinAtmosphereFadeStartY", 200);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(wrongType));

        NBTTagCompound nonFinite = schemaThreeTag(200.0);
        nonFinite.setDouble("thinAtmosphereFadeStartY", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(nonFinite));

        NBTTagCompound outOfRange = schemaThreeTag(200.0);
        outOfRange.setDouble("thinAtmosphereFadeStartY", 256.0);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(outOfRange));

        assertSame(accepted, settings.sunshade());
        assertEquals(200.0, settings.thinAtmosphereFadeStartY(), 0.0);
    }

    @Test
    public void copyRetainsItsThinAtmosphereFadeStartAfterTheOriginalChanges() {
        RingworldSettings original = new RingworldSettings();
        original.readFromNBT(schemaThreeTag(200.0));
        RingworldSettings copy = original.copy();
        original.readFromNBT(schemaThreeTag(220.0));

        assertEquals(200.0, copy.thinAtmosphereFadeStartY(), 0.0);
        assertEquals(220.0, original.thinAtmosphereFadeStartY(), 0.0);
    }

    @Test
    public void defaultAndMissingNbtKeepRingworldDisabled() {
        RingworldSettings settings = new RingworldSettings();

        assertNull(settings.sunshade());
        settings.readFromNBT(new NBTTagCompound());
        assertNull(settings.sunshade());
    }

    @Test
    public void enabledSunshadeSurvivesNbtRoundTripWithItsSpatialBehavior() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(enabledTag());
        RingworldSunshade original = settings.sunshade();

        assertNotNull(original);
        assertEquals(0.0, original.transmittance(0L, 2.0, 0.0), 0.0);
        assertEquals(0.5, original.transmittance(0L, 6.0, 0.0), 0.0);

        NBTTagCompound saved = new NBTTagCompound();
        settings.writeToNBT(saved);
        RingworldSettings restored = new RingworldSettings();
        restored.readFromNBT(saved);

        assertNotNull(restored.sunshade());
        assertEquals(0.0, restored.sunshade().transmittance(0L, 2.0, 0.0), 0.0);
        assertEquals(0.5, restored.sunshade().transmittance(0L, 6.0, 0.0), 0.0);
    }

    @Test
    public void schemaOneMigratesToTheCuboidGeometryAndSchemaThreePersistsBothFaces() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(enabledTag());

        assertEquals(512, settings.sunshadeHeightBlocks());
        assertEquals(8, settings.sunshadeThicknessBlocks());

        NBTTagCompound schemaTwo = new NBTTagCompound();
        settings.writeToNBT(schemaTwo);
        assertEquals(3, schemaTwo.getInteger("schemaVersion"));
        assertTrue(schemaTwo.hasKey("sunshadeHeightBlocks", 3));
        assertEquals(512, schemaTwo.getInteger("sunshadeHeightBlocks"));
        assertTrue(schemaTwo.hasKey("sunshadeThicknessBlocks", 3));
        assertEquals(8, schemaTwo.getInteger("sunshadeThicknessBlocks"));
    }

    @Test
    public void schemaTwoRequiresBothGeometryFieldsWithoutReplacingTheAcceptedState() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(schemaTwoTag(768, 12));
        RingworldSunshade accepted = settings.sunshade();

        NBTTagCompound missingBase = schemaTwoTag(512, 8);
        missingBase.removeTag("sunshadeHeightBlocks");
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(missingBase));
        NBTTagCompound missingThickness = schemaTwoTag(512, 8);
        missingThickness.removeTag("sunshadeThicknessBlocks");
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(missingThickness));

        assertSame(accepted, settings.sunshade());
        assertEquals(768, settings.sunshadeHeightBlocks());
        assertEquals(12, settings.sunshadeThicknessBlocks());
    }

    @Test
    public void copiedSchemaTwoRetainsNonDefaultGeometryAfterTheOriginalChanges() {
        RingworldSettings original = new RingworldSettings();
        original.readFromNBT(schemaTwoTag(768, 12));
        RingworldSettings copy = original.copy();
        original.readFromNBT(schemaTwoTag(512, 8));

        NBTTagCompound saved = new NBTTagCompound();
        copy.writeToNBT(saved);
        RingworldSettings restored = new RingworldSettings();
        restored.readFromNBT(saved);
        assertEquals(768, restored.sunshadeHeightBlocks());
        assertEquals(12, restored.sunshadeThicknessBlocks());
    }

    @Test
    public void configEnablesTheCachedSunshadeWithWorldRestartProperties() {
        Configuration config = new Configuration();
        RingworldSettings settings = new RingworldSettings();
        settings.setupConfig(config, "ringworld");
        config.get("ringworld", "Enabled", false).set(true);
        config.get("ringworld", "Spacing_Blocks", 256.0).set(20.0);
        config.get("ringworld", "Shadow_Width_Blocks", 128.0).set(10.0);
        config.get("ringworld", "Cycle_Ticks", 24000).set(100);
        config.get("ringworld", "Phase_Offset_Blocks", 0.0).set(2.0);
        config.get("ringworld", "Heading_Degrees", 0.0).set(0.0);
        config.get("ringworld", "Feather_Blocks", 4.0).set(2.0);
        config.get("ringworld", "Sunshade_Height_Blocks", 512).set(768);
        config.get("ringworld", "Sunshade_Thickness_Blocks", 8).set(12);

        settings.loadFromConfig(config, "ringworld");

        assertNotNull(settings.sunshade());
        assertEquals(768, settings.sunshadeHeightBlocks());
        assertEquals(12, settings.sunshadeThicknessBlocks());
        assertEquals(0.0, settings.sunshade().transmittance(0L, 2.0, 0.0), 0.0);
        assertEquals(0.5, settings.sunshade().transmittance(0L, 6.0, 0.0), 0.0);
        assertEquals(true, config.getCategory("ringworld").requiresWorldRestart());
    }

    @Test
    public void saveToConfigWritesTheAcceptedStateWithoutPersistingTheConfiguration() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(enabledTag());
        Configuration config = new Configuration();
        settings.setupConfig(config, "ringworld");

        settings.saveToConfig(config, "ringworld");

        assertEquals(true, config.get("ringworld", "Enabled", false).getBoolean());
        assertEquals(20.0, config.get("ringworld", "Spacing_Blocks", 256.0).getDouble(), 0.0);
        assertEquals(10.0, config.get("ringworld", "Shadow_Width_Blocks", 128.0).getDouble(), 0.0);
        assertEquals(100, config.get("ringworld", "Cycle_Ticks", 24000).getInt());
        assertEquals(2.0, config.get("ringworld", "Phase_Offset_Blocks", 0.0).getDouble(), 0.0);
        assertEquals(0.0, config.get("ringworld", "Heading_Degrees", 1.0).getDouble(), 0.0);
        assertEquals(2.0, config.get("ringworld", "Feather_Blocks", 4.0).getDouble(), 0.0);
        assertEquals(512, config.get("ringworld", "Sunshade_Height_Blocks", 512).getInt());
        assertEquals(8, config.get("ringworld", "Sunshade_Thickness_Blocks", 8).getInt());
    }

    @Test
    public void malformedUnknownAndInvalidInputFailWithoutReplacingAcceptedState() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(enabledTag());
        RingworldSunshade accepted = settings.sunshade();

        NBTTagCompound unknownSchema = enabledTag();
        unknownSchema.setInteger("schemaVersion", 3);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(unknownSchema));
        assertSame(accepted, settings.sunshade());

        NBTTagCompound malformedType = enabledTag();
        malformedType.setInteger("headingDegrees", 0);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(malformedType));
        assertSame(accepted, settings.sunshade());

        NBTTagCompound invalidValues = enabledTag();
        invalidValues.setDouble("spacingBlocks", 0.0);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(invalidValues));
        assertSame(accepted, settings.sunshade());

        NBTTagCompound invalidHeightType = schemaTwoTag(768, 12);
        invalidHeightType.setDouble("sunshadeHeightBlocks", 768.0);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(invalidHeightType));
        assertSame(accepted, settings.sunshade());

        NBTTagCompound invalidLowerFace = schemaTwoTag(-1, 8);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(invalidLowerFace));
        assertSame(accepted, settings.sunshade());

        NBTTagCompound invalidThickness = schemaTwoTag(512, 0);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(invalidThickness));
        assertSame(accepted, settings.sunshade());

        NBTTagCompound overflowingUpperFace = schemaTwoTag(Integer.MAX_VALUE, 1);
        assertThrows(IllegalArgumentException.class, () -> settings.readFromNBT(overflowingUpperFace));
        assertSame(accepted, settings.sunshade());

        Configuration config = new Configuration();
        settings.setupConfig(config, "ringworld");
        config.get("ringworld", "Enabled", false).set(true);
        config.get("ringworld", "Spacing_Blocks", 256.0).set(0.0);
        assertThrows(IllegalArgumentException.class, () -> settings.loadFromConfig(config, "ringworld"));
        assertSame(accepted, settings.sunshade());

        Configuration malformedThicknessConfig = new Configuration();
        settings.setupConfig(malformedThicknessConfig, "ringworld");
        malformedThicknessConfig.getCategory("ringworld").put("Sunshade_Thickness_Blocks",
                new Property("Sunshade_Thickness_Blocks", "oops", Property.Type.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> settings.loadFromConfig(malformedThicknessConfig, "ringworld"));
        assertSame(accepted, settings.sunshade());

        Configuration malformedHeightConfig = new Configuration();
        settings.setupConfig(malformedHeightConfig, "ringworld");
        malformedHeightConfig.getCategory("ringworld").put("Sunshade_Height_Blocks",
                new Property("Sunshade_Height_Blocks", "oops", Property.Type.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> settings.loadFromConfig(malformedHeightConfig, "ringworld"));
        assertSame(accepted, settings.sunshade());

        Configuration malformedConfig = new Configuration();
        settings.setupConfig(malformedConfig, "ringworld");
        malformedConfig.getCategory("ringworld").put("Enabled",
                new Property("Enabled", "not-a-boolean", Property.Type.STRING));
        assertThrows(IllegalArgumentException.class, () -> settings.loadFromConfig(malformedConfig, "ringworld"));
        assertSame(accepted, settings.sunshade());

        Configuration malformedNumberConfig = new Configuration();
        settings.setupConfig(malformedNumberConfig, "ringworld");
        malformedNumberConfig.getCategory("ringworld").put("Shadow_Width_Blocks",
                new Property("Shadow_Width_Blocks", "oops", Property.Type.STRING));
        assertThrows(IllegalArgumentException.class,
                () -> settings.loadFromConfig(malformedNumberConfig, "ringworld"));
        assertSame(accepted, settings.sunshade());
    }

    @Test
    public void copyCanChangeItsOwnStateWithoutChangingTheOriginal() {
        RingworldSettings original = new RingworldSettings();
        original.readFromNBT(enabledTag());
        RingworldSettings copy = original.copy();

        assertSame(original.sunshade(), copy.sunshade());

        Configuration disabledConfig = new Configuration();
        copy.setupConfig(disabledConfig, "ringworld");
        copy.loadFromConfig(disabledConfig, "ringworld");

        assertNull(copy.sunshade());
        assertNotNull(original.sunshade());
    }

    @Test
    public void malformedTypedPropertyIsNotSilentlyReplacedWithForgeDefaults() {
        RingworldSettings settings = new RingworldSettings();
        settings.readFromNBT(enabledTag());
        RingworldSunshade accepted = settings.sunshade();
        Configuration config = new Configuration();
        settings.setupConfig(config, "ringworld");
        // This is the typed Property produced by a parsed D:... line, not a
        // wrong-type STRING fixture. Calling Configuration.get(double) heals it.
        config.getCategory("ringworld").put("Shadow_Width_Blocks",
                new Property("Shadow_Width_Blocks", "oops", Property.Type.DOUBLE));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            settings.setupConfig(config, "ringworld");
            settings.loadFromConfig(config, "ringworld");
        });

        assertTrue(failure.getCause() instanceof NumberFormatException);
        assertSame(accepted, settings.sunshade());
    }

    private static NBTTagCompound enabledTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schemaVersion", 1);
        tag.setByte("enabled", (byte) 1);
        tag.setDouble("spacingBlocks", 20.0);
        tag.setDouble("shadowWidthBlocks", 10.0);
        tag.setInteger("cycleTicks", 100);
        tag.setDouble("phaseOffsetBlocks", 2.0);
        tag.setDouble("headingDegrees", 0.0);
        tag.setDouble("featherBlocks", 2.0);
        return tag;
    }

    private static NBTTagCompound schemaTwoTag(int sunshadeHeightBlocks, int sunshadeThicknessBlocks) {
        NBTTagCompound tag = enabledTag();
        tag.setInteger("schemaVersion", 2);
        tag.setInteger("sunshadeHeightBlocks", sunshadeHeightBlocks);
        tag.setInteger("sunshadeThicknessBlocks", sunshadeThicknessBlocks);
        return tag;
    }

    private static NBTTagCompound schemaThreeTag(double thinAtmosphereFadeStartY) {
        NBTTagCompound tag = schemaTwoTag(768, 12);
        tag.setInteger("schemaVersion", 3);
        tag.setDouble("thinAtmosphereFadeStartY", thinAtmosphereFadeStartY);
        return tag;
    }
}
