package stellarium.world.ring;

import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import stellarapi.api.lib.config.INBTConfig;
import stellarium.common.ServerSettings;

/** Root-owned configuration state for the ringworld sunshade. */
public final class RingworldSettings implements INBTConfig {

    private static final int SCHEMA_VERSION = 3;
    private static final int DEFAULT_CYCLE_TICKS = ServerSettings.DEFAULT_DAY_LENGTH_TICKS;
    private static final double DEFAULT_SPACING_BLOCKS = 40_176_000.0;
    private static final double DEFAULT_SHADOW_WIDTH_BLOCKS = DEFAULT_SPACING_BLOCKS / 2.0;
    // Preserve the prototype's 1/64-cycle soft-edge ratio: 22.5 minutes at 20 TPS.
    private static final double DEFAULT_FEATHER_BLOCKS = DEFAULT_SPACING_BLOCKS / 64.0;
    // Tunable engineering starting point: about 0.25 degrees/minute overhead at 465 m/s.
    private static final int DEFAULT_HEIGHT_BLOCKS = 6_400_000;
    private static final double DEFAULT_THIN_ATMOSPHERE_FADE_START_Y = 192.0;
    private static final String ENABLED_KEY = "Enabled";
    private static final String SPACING_BLOCKS_KEY = "Spacing_Blocks";
    private static final String SHADOW_WIDTH_BLOCKS_KEY = "Shadow_Width_Blocks";
    private static final String CYCLE_TICKS_KEY = "Cycle_Ticks";
    private static final String PHASE_OFFSET_BLOCKS_KEY = "Phase_Offset_Blocks";
    private static final String HEADING_DEGREES_KEY = "Heading_Degrees";
    private static final String FEATHER_BLOCKS_KEY = "Feather_Blocks";
    private static final String SUNSHADE_HEIGHT_BLOCKS_KEY = "Sunshade_Height_Blocks";
    private static final String SUNSHADE_THICKNESS_BLOCKS_KEY = "Sunshade_Thickness_Blocks";
    private static final String THIN_ATMOSPHERE_FADE_START_Y_KEY = "Thin_Atmosphere_Fade_Start_Y";

    private volatile State state = State.defaults();

    public RingworldSunshade sunshade() {
        return state.sunshade();
    }

    public int sunshadeHeightBlocks() {
        return state.sunshadeHeightBlocks();
    }

    public int sunshadeThicknessBlocks() {
        return state.sunshadeThicknessBlocks();
    }

    public double thinAtmosphereFadeStartY() {
        return state.thinAtmosphereFadeStartY();
    }

    @Override
    public void setupConfig(Configuration config, String category) {
        config.setCategoryRequiresWorldRestart(category, true);
        property(config, category, ENABLED_KEY, false).setRequiresWorldRestart(true);
        property(config, category, SPACING_BLOCKS_KEY, DEFAULT_SPACING_BLOCKS).setRequiresWorldRestart(true);
        property(config, category, SHADOW_WIDTH_BLOCKS_KEY, DEFAULT_SHADOW_WIDTH_BLOCKS).setRequiresWorldRestart(true);
        property(config, category, CYCLE_TICKS_KEY, DEFAULT_CYCLE_TICKS).setRequiresWorldRestart(true);
        property(config, category, PHASE_OFFSET_BLOCKS_KEY, 0.0).setRequiresWorldRestart(true);
        property(config, category, HEADING_DEGREES_KEY, 0.0).setRequiresWorldRestart(true);
        property(config, category, FEATHER_BLOCKS_KEY, DEFAULT_FEATHER_BLOCKS).setRequiresWorldRestart(true);
        // Height is the cuboid's lower face, not a zero-thickness plane.
        property(config, category, SUNSHADE_HEIGHT_BLOCKS_KEY, DEFAULT_HEIGHT_BLOCKS).setRequiresWorldRestart(true);
        property(config, category, SUNSHADE_THICKNESS_BLOCKS_KEY, 8).setRequiresWorldRestart(true);
        property(config, category, THIN_ATMOSPHERE_FADE_START_Y_KEY, DEFAULT_THIN_ATMOSPHERE_FADE_START_Y)
                .setRequiresWorldRestart(true);
    }

    @Override
    public void loadFromConfig(Configuration config, String category) {
        State candidate = State.create(
                readBoolean(property(config, category, ENABLED_KEY, false), ENABLED_KEY),
                readDouble(property(config, category, SPACING_BLOCKS_KEY, DEFAULT_SPACING_BLOCKS), SPACING_BLOCKS_KEY),
                readDouble(property(config, category, SHADOW_WIDTH_BLOCKS_KEY, DEFAULT_SHADOW_WIDTH_BLOCKS), SHADOW_WIDTH_BLOCKS_KEY),
                readInteger(property(config, category, CYCLE_TICKS_KEY, DEFAULT_CYCLE_TICKS), CYCLE_TICKS_KEY),
                readDouble(property(config, category, PHASE_OFFSET_BLOCKS_KEY, 0.0), PHASE_OFFSET_BLOCKS_KEY),
                readDouble(property(config, category, HEADING_DEGREES_KEY, 0.0), HEADING_DEGREES_KEY),
                readDouble(property(config, category, FEATHER_BLOCKS_KEY, DEFAULT_FEATHER_BLOCKS), FEATHER_BLOCKS_KEY),
                readInteger(property(config, category, SUNSHADE_HEIGHT_BLOCKS_KEY, DEFAULT_HEIGHT_BLOCKS), SUNSHADE_HEIGHT_BLOCKS_KEY),
                readInteger(property(config, category, SUNSHADE_THICKNESS_BLOCKS_KEY, 8), SUNSHADE_THICKNESS_BLOCKS_KEY),
                readDouble(property(config, category, THIN_ATMOSPHERE_FADE_START_Y_KEY,
                        DEFAULT_THIN_ATMOSPHERE_FADE_START_Y), THIN_ATMOSPHERE_FADE_START_Y_KEY));
        state = candidate;
    }

    @Override
    public void saveToConfig(Configuration config, String category) {
        State current = state;
        property(config, category, ENABLED_KEY, false).set(current.enabled());
        property(config, category, SPACING_BLOCKS_KEY, DEFAULT_SPACING_BLOCKS).set(current.spacingBlocks());
        property(config, category, SHADOW_WIDTH_BLOCKS_KEY, DEFAULT_SHADOW_WIDTH_BLOCKS).set(current.shadowWidthBlocks());
        property(config, category, CYCLE_TICKS_KEY, DEFAULT_CYCLE_TICKS).set(current.cycleTicks());
        property(config, category, PHASE_OFFSET_BLOCKS_KEY, 0.0).set(current.phaseOffsetBlocks());
        property(config, category, HEADING_DEGREES_KEY, 0.0).set(current.headingDegrees());
        property(config, category, FEATHER_BLOCKS_KEY, DEFAULT_FEATHER_BLOCKS).set(current.featherBlocks());
        property(config, category, SUNSHADE_HEIGHT_BLOCKS_KEY, DEFAULT_HEIGHT_BLOCKS).set(current.sunshadeHeightBlocks());
        property(config, category, SUNSHADE_THICKNESS_BLOCKS_KEY, 8).set(current.sunshadeThicknessBlocks());
        property(config, category, THIN_ATMOSPHERE_FADE_START_Y_KEY, DEFAULT_THIN_ATMOSPHERE_FADE_START_Y)
                .set(current.thinAtmosphereFadeStartY());
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        if (compound == null || compound.getKeySet().isEmpty()) {
            state = State.defaults();
            return;
        }
        state = readState(compound);
    }

    @Override
    public void writeToNBT(NBTTagCompound compound) {
        Objects.requireNonNull(compound, "compound");
        State current = state;
        compound.setInteger("schemaVersion", SCHEMA_VERSION);
        compound.setByte("enabled", (byte) (current.enabled() ? 1 : 0));
        compound.setDouble("spacingBlocks", current.spacingBlocks());
        compound.setDouble("shadowWidthBlocks", current.shadowWidthBlocks());
        compound.setInteger("cycleTicks", current.cycleTicks());
        compound.setDouble("phaseOffsetBlocks", current.phaseOffsetBlocks());
        compound.setDouble("headingDegrees", current.headingDegrees());
        compound.setDouble("featherBlocks", current.featherBlocks());
        compound.setInteger("sunshadeHeightBlocks", current.sunshadeHeightBlocks());
        compound.setInteger("sunshadeThicknessBlocks", current.sunshadeThicknessBlocks());
        compound.setDouble("thinAtmosphereFadeStartY", current.thinAtmosphereFadeStartY());
    }

    @Override
    public RingworldSettings copy() {
        RingworldSettings copy = new RingworldSettings();
        copy.state = state;
        return copy;
    }

    private static State readState(NBTTagCompound compound) {
        requireType(compound, "schemaVersion", 3);
        int schemaVersion = compound.getInteger("schemaVersion");
        if (schemaVersion != 1 && schemaVersion != 2 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unknown ringworld settings schema version");
        }
        requireType(compound, "enabled", 1);
        byte enabledValue = compound.getByte("enabled");
        if (enabledValue != 0 && enabledValue != 1) {
            throw new IllegalArgumentException("enabled must be encoded as byte 0 or 1");
        }
        requireType(compound, "spacingBlocks", 6);
        requireType(compound, "shadowWidthBlocks", 6);
        requireType(compound, "cycleTicks", 3);
        requireType(compound, "phaseOffsetBlocks", 6);
        requireType(compound, "headingDegrees", 6);
        requireType(compound, "featherBlocks", 6);
        int sunshadeHeightBlocks = schemaVersion == 1 ? 512 : readSunshadeHeight(compound);
        int sunshadeThicknessBlocks = schemaVersion == 1 ? 8 : readSunshadeThickness(compound);
        double thinAtmosphereFadeStartY = schemaVersion == SCHEMA_VERSION
                ? readThinAtmosphereFadeStartY(compound)
                : DEFAULT_THIN_ATMOSPHERE_FADE_START_Y;
        return State.create(enabledValue == 1,
                compound.getDouble("spacingBlocks"),
                compound.getDouble("shadowWidthBlocks"),
                compound.getInteger("cycleTicks"),
                compound.getDouble("phaseOffsetBlocks"),
                compound.getDouble("headingDegrees"),
                compound.getDouble("featherBlocks"), sunshadeHeightBlocks, sunshadeThicknessBlocks,
                thinAtmosphereFadeStartY);
    }

    private static int readSunshadeHeight(NBTTagCompound compound) {
        requireType(compound, "sunshadeHeightBlocks", 3);
        return compound.getInteger("sunshadeHeightBlocks");
    }

    private static int readSunshadeThickness(NBTTagCompound compound) {
        requireType(compound, "sunshadeThicknessBlocks", 3);
        return compound.getInteger("sunshadeThicknessBlocks");
    }

    private static double readThinAtmosphereFadeStartY(NBTTagCompound compound) {
        requireType(compound, "thinAtmosphereFadeStartY", 6);
        return compound.getDouble("thinAtmosphereFadeStartY");
    }

    private static void requireType(NBTTagCompound compound, String key, int type) {
        if (!compound.hasKey(key, type)) {
            throw new IllegalArgumentException("Missing or invalid NBT field: " + key);
        }
    }

    private static Property property(Configuration config, String category, String key, boolean defaultValue) {
        Property existing = config.getCategory(category).get(key);
        return existing == null ? config.get(category, key, defaultValue) : existing;
    }

    private static Property property(Configuration config, String category, String key, int defaultValue) {
        Property existing = config.getCategory(category).get(key);
        return existing == null ? config.get(category, key, defaultValue) : existing;
    }

    private static Property property(Configuration config, String category, String key, double defaultValue) {
        // Forge's typed getter replaces malformed existing values with defaults.
        // Preserve the original literal so strict parsing can reject it.
        Property existing = config.getCategory(category).get(key);
        return existing == null ? config.get(category, key, defaultValue) : existing;
    }

    private static boolean readBoolean(Property property, String key) {
        requirePropertyType(property, key, Property.Type.BOOLEAN);
        String value = property.getString();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid boolean configuration value: " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static int readInteger(Property property, String key) {
        requirePropertyType(property, key, Property.Type.INTEGER);
        try {
            return Integer.parseInt(property.getString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer configuration value: " + key, exception);
        }
    }

    private static double readDouble(Property property, String key) {
        requirePropertyType(property, key, Property.Type.DOUBLE);
        try {
            double value = Double.parseDouble(property.getString());
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Configuration value must be finite: " + key);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid double configuration value: " + key, exception);
        }
    }

    private static void requirePropertyType(Property property, String key, Property.Type expectedType) {
        if (property.getType() != expectedType) {
            throw new IllegalArgumentException("Invalid configuration type for " + key);
        }
    }

    private record State(boolean enabled,
                         double spacingBlocks,
                         double shadowWidthBlocks,
                         int cycleTicks,
                         double phaseOffsetBlocks,
                         double headingDegrees,
                         double featherBlocks,
                         int sunshadeHeightBlocks,
                         int sunshadeThicknessBlocks,
                         double thinAtmosphereFadeStartY,
                         RingworldSunshade sunshade) {
        private static State defaults() {
            return create(false, DEFAULT_SPACING_BLOCKS, DEFAULT_SHADOW_WIDTH_BLOCKS, DEFAULT_CYCLE_TICKS,
                    0.0, 0.0, DEFAULT_FEATHER_BLOCKS, DEFAULT_HEIGHT_BLOCKS, 8,
                    DEFAULT_THIN_ATMOSPHERE_FADE_START_Y);
        }

        private static State create(boolean enabled,
                                    double spacingBlocks,
                                    double shadowWidthBlocks,
                                    int cycleTicks,
                                    double phaseOffsetBlocks,
                                    double headingDegrees,
                                    double featherBlocks,
                                    int sunshadeHeightBlocks,
                                    int sunshadeThicknessBlocks,
                                    double thinAtmosphereFadeStartY) {
            validateSunshadeGeometry(sunshadeHeightBlocks, sunshadeThicknessBlocks);
            validateThinAtmosphereFadeStartY(thinAtmosphereFadeStartY);
            RingworldSunshade validatedSunshade = new RingworldSunshade(
                    spacingBlocks, shadowWidthBlocks, cycleTicks,
                    phaseOffsetBlocks, headingDegrees, featherBlocks);
            return new State(enabled, spacingBlocks, shadowWidthBlocks, cycleTicks,
                    phaseOffsetBlocks, headingDegrees, featherBlocks, sunshadeHeightBlocks, sunshadeThicknessBlocks,
                    thinAtmosphereFadeStartY, enabled ? validatedSunshade : null);
        }

        private static void validateSunshadeGeometry(int sunshadeHeightBlocks, int sunshadeThicknessBlocks) {
            if (sunshadeHeightBlocks < 0) {
                throw new IllegalArgumentException("sunshadeHeightBlocks must be non-negative");
            }
            if (sunshadeThicknessBlocks <= 0) {
                throw new IllegalArgumentException("sunshadeThicknessBlocks must be greater than zero");
            }
            try {
                Math.addExact(sunshadeHeightBlocks, sunshadeThicknessBlocks);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("sunshade upper face exceeds integer coordinates", exception);
            }
        }

        private static void validateThinAtmosphereFadeStartY(double thinAtmosphereFadeStartY) {
            if (!Double.isFinite(thinAtmosphereFadeStartY)
                    || thinAtmosphereFadeStartY < 0.0
                    || thinAtmosphereFadeStartY >= 256.0) {
                throw new IllegalArgumentException("thinAtmosphereFadeStartY must be finite and in [0, 256)");
            }
        }
    }
}
