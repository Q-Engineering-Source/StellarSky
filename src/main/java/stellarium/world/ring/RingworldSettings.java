package stellarium.world.ring;

import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import stellarapi.api.lib.config.INBTConfig;
import stellarium.common.ServerSettings;

/** Root-owned configuration state for the ringworld sunshade. */
public final class RingworldSettings implements INBTConfig {

    private static final int SCHEMA_VERSION = 5;
    private static final int DEFAULT_CYCLE_TICKS = ServerSettings.DEFAULT_DAY_LENGTH_TICKS;
    private static final double DEFAULT_SPACING_BLOCKS = 40_176_000.0;
    private static final double DEFAULT_SHADOW_WIDTH_BLOCKS = DEFAULT_SPACING_BLOCKS / 2.0;
    // Preserve the prototype's 1/64-cycle soft-edge ratio: 22.5 minutes at 20 TPS.
    private static final double DEFAULT_FEATHER_BLOCKS = DEFAULT_SPACING_BLOCKS / 64.0;
    // First-release engineering trial: eight chunks of gradual light recovery at each finite Z side.
    private static final double DEFAULT_SIDE_FEATHER_BLOCKS = 128.0;
    // Tunable engineering starting point: about 0.25 degrees/minute overhead at 465 m/s.
    private static final int DEFAULT_HEIGHT_BLOCKS = 6_400_000;
    private static final double DEFAULT_THIN_ATMOSPHERE_FADE_START_Y = 384.0;
    private static final double DEFAULT_ATMOSPHERE_LOWER_Y = 0.0;
    private static final double DEFAULT_ATMOSPHERE_UPPER_Y = 512.0;
    // Schemas 1-4 did not persist air bounds, so their historical 0..256 volume
    // must not silently inherit the wider defaults above when a world is loaded.
    private static final double LEGACY_THIN_ATMOSPHERE_FADE_START_Y = 192.0;
    private static final double LEGACY_ATMOSPHERE_LOWER_Y = 0.0;
    private static final double LEGACY_ATMOSPHERE_UPPER_Y = 256.0;
    private static final RingworldAirProfile DEFAULT_ATMOSPHERE_PROFILE = new RingworldAirProfile(
            DEFAULT_ATMOSPHERE_LOWER_Y, DEFAULT_THIN_ATMOSPHERE_FADE_START_Y, DEFAULT_ATMOSPHERE_UPPER_Y);
    private static final String ENABLED_KEY = "Enabled";
    private static final String SPACING_BLOCKS_KEY = "Spacing_Blocks";
    private static final String SHADOW_WIDTH_BLOCKS_KEY = "Shadow_Width_Blocks";
    private static final String CYCLE_TICKS_KEY = "Cycle_Ticks";
    private static final String PHASE_OFFSET_BLOCKS_KEY = "Phase_Offset_Blocks";
    private static final String HEADING_DEGREES_KEY = "Heading_Degrees";
    private static final String FEATHER_BLOCKS_KEY = "Feather_Blocks";
    private static final String SIDE_FEATHER_BLOCKS_KEY = "Side_Feather_Blocks";
    private static final String SUNSHADE_HEIGHT_BLOCKS_KEY = "Sunshade_Height_Blocks";
    private static final String SUNSHADE_THICKNESS_BLOCKS_KEY = "Sunshade_Thickness_Blocks";
    private static final String THIN_ATMOSPHERE_FADE_START_Y_KEY = "Thin_Atmosphere_Fade_Start_Y";
    private static final String ATMOSPHERE_LOWER_Y_KEY = "Atmosphere_Lower_Y";
    private static final String ATMOSPHERE_UPPER_Y_KEY = "Atmosphere_Upper_Y";

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

    /** Frozen spatial-air definition; callers must not re-read mutable config per render pass. */
    public RingworldAirProfile atmosphereProfile() {
        return state.atmosphereProfile();
    }

    static RingworldAirProfile defaultAtmosphereProfile() {
        return DEFAULT_ATMOSPHERE_PROFILE;
    }

    @Override
    public void setupConfig(Configuration config, String category) {
        config.setCategoryRequiresWorldRestart(category, true);
        boolean migrateLegacyAirBounds = hasLegacyRingworldSettingsWithoutAirBounds(config, category);
        property(config, category, ENABLED_KEY, false).setRequiresWorldRestart(true);
        property(config, category, SPACING_BLOCKS_KEY, DEFAULT_SPACING_BLOCKS).setRequiresWorldRestart(true);
        property(config, category, SHADOW_WIDTH_BLOCKS_KEY, DEFAULT_SHADOW_WIDTH_BLOCKS).setRequiresWorldRestart(true);
        property(config, category, CYCLE_TICKS_KEY, DEFAULT_CYCLE_TICKS).setRequiresWorldRestart(true);
        property(config, category, PHASE_OFFSET_BLOCKS_KEY, 0.0).setRequiresWorldRestart(true);
        property(config, category, HEADING_DEGREES_KEY, 0.0).setRequiresWorldRestart(true);
        Property feather = property(config, category, FEATHER_BLOCKS_KEY, DEFAULT_FEATHER_BLOCKS);
        feather.setComment("Day/night transition distance in blocks along the movement axis, from a material edge into full shadow. "
                + "0 gives a hard edge. At 465 blocks/second, 9300 blocks gives a 20-second transition at normal time. "
                + "Maximum: half the smaller of Shadow_Width_Blocks and the gap (Spacing_Blocks - Shadow_Width_Blocks). "
                + "Server-authoritative; reload the world/restart the server to apply. Independent of board height.");
        feather.setRequiresWorldRestart(true);
        Property sideFeather = property(config, category, SIDE_FEATHER_BLOCKS_KEY, DEFAULT_SIDE_FEATHER_BLOCKS);
        sideFeather.setComment("Finite-strip side transition distance in blocks, fading inward from each Z edge. "
                + "0 preserves hard strip edges; maximum is 8192. This changes only the skylight field below the board, "
                + "not physical board occupancy. Server-authoritative; reload the world/restart the server to apply.");
        sideFeather.setRequiresWorldRestart(true);
        // Height is the cuboid's lower face, not a zero-thickness plane.
        property(config, category, SUNSHADE_HEIGHT_BLOCKS_KEY, DEFAULT_HEIGHT_BLOCKS).setRequiresWorldRestart(true);
        property(config, category, SUNSHADE_THICKNESS_BLOCKS_KEY, 32).setRequiresWorldRestart(true);
        Property atmosphereFadeStart = property(config, category, THIN_ATMOSPHERE_FADE_START_Y_KEY,
                DEFAULT_THIN_ATMOSPHERE_FADE_START_Y);
        atmosphereFadeStart.setComment("Physical Y at which the configured ringworld atmosphere starts fading. "
                + "Must be no lower than Atmosphere_Lower_Y and below Atmosphere_Upper_Y. "
                + "Server-authoritative; reload the world/restart the server to apply.");
        atmosphereFadeStart.setRequiresWorldRestart(true);
        Property atmosphereLower = property(config, category, ATMOSPHERE_LOWER_Y_KEY,
                migrateLegacyAirBounds ? LEGACY_ATMOSPHERE_LOWER_Y : DEFAULT_ATMOSPHERE_LOWER_Y);
        atmosphereLower.setComment("Inclusive physical Y floor of the ringworld atmosphere. "
                + "Must not exceed Thin_Atmosphere_Fade_Start_Y. Server-authoritative; reload the world/restart the server to apply.");
        atmosphereLower.setRequiresWorldRestart(true);
        Property atmosphereUpper = property(config, category, ATMOSPHERE_UPPER_Y_KEY,
                migrateLegacyAirBounds ? LEGACY_ATMOSPHERE_UPPER_Y : DEFAULT_ATMOSPHERE_UPPER_Y);
        atmosphereUpper.setComment("Exclusive physical Y ceiling of the ringworld atmosphere. "
                + "Must be above Thin_Atmosphere_Fade_Start_Y. Server-authoritative; reload the world/restart the server to apply.");
        atmosphereUpper.setRequiresWorldRestart(true);
    }

    @Override
    public void loadFromConfig(Configuration config, String category) {
        boolean migrateLegacyAirBounds = hasLegacyRingworldSettingsWithoutAirBounds(config, category);
        State candidate = State.create(
                readBoolean(property(config, category, ENABLED_KEY, false), ENABLED_KEY),
                readDouble(property(config, category, SPACING_BLOCKS_KEY, DEFAULT_SPACING_BLOCKS), SPACING_BLOCKS_KEY),
                readDouble(property(config, category, SHADOW_WIDTH_BLOCKS_KEY, DEFAULT_SHADOW_WIDTH_BLOCKS), SHADOW_WIDTH_BLOCKS_KEY),
                readInteger(property(config, category, CYCLE_TICKS_KEY, DEFAULT_CYCLE_TICKS), CYCLE_TICKS_KEY),
                readDouble(property(config, category, PHASE_OFFSET_BLOCKS_KEY, 0.0), PHASE_OFFSET_BLOCKS_KEY),
                readDouble(property(config, category, HEADING_DEGREES_KEY, 0.0), HEADING_DEGREES_KEY),
                readDouble(property(config, category, FEATHER_BLOCKS_KEY, DEFAULT_FEATHER_BLOCKS), FEATHER_BLOCKS_KEY),
                readDouble(property(config, category, SIDE_FEATHER_BLOCKS_KEY, DEFAULT_SIDE_FEATHER_BLOCKS),
                        SIDE_FEATHER_BLOCKS_KEY),
                readInteger(property(config, category, SUNSHADE_HEIGHT_BLOCKS_KEY, DEFAULT_HEIGHT_BLOCKS), SUNSHADE_HEIGHT_BLOCKS_KEY),
                readInteger(property(config, category, SUNSHADE_THICKNESS_BLOCKS_KEY, 32), SUNSHADE_THICKNESS_BLOCKS_KEY),
                readDouble(property(config, category, THIN_ATMOSPHERE_FADE_START_Y_KEY,
                        DEFAULT_THIN_ATMOSPHERE_FADE_START_Y), THIN_ATMOSPHERE_FADE_START_Y_KEY),
                readDouble(property(config, category, ATMOSPHERE_LOWER_Y_KEY,
                        migrateLegacyAirBounds ? LEGACY_ATMOSPHERE_LOWER_Y : DEFAULT_ATMOSPHERE_LOWER_Y),
                        ATMOSPHERE_LOWER_Y_KEY),
                readDouble(property(config, category, ATMOSPHERE_UPPER_Y_KEY,
                        migrateLegacyAirBounds ? LEGACY_ATMOSPHERE_UPPER_Y : DEFAULT_ATMOSPHERE_UPPER_Y),
                        ATMOSPHERE_UPPER_Y_KEY));
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
        property(config, category, SIDE_FEATHER_BLOCKS_KEY, DEFAULT_SIDE_FEATHER_BLOCKS).set(current.sideFeatherBlocks());
        property(config, category, SUNSHADE_HEIGHT_BLOCKS_KEY, DEFAULT_HEIGHT_BLOCKS).set(current.sunshadeHeightBlocks());
        property(config, category, SUNSHADE_THICKNESS_BLOCKS_KEY, 32).set(current.sunshadeThicknessBlocks());
        property(config, category, THIN_ATMOSPHERE_FADE_START_Y_KEY, DEFAULT_THIN_ATMOSPHERE_FADE_START_Y)
                .set(current.thinAtmosphereFadeStartY());
        property(config, category, ATMOSPHERE_LOWER_Y_KEY, DEFAULT_ATMOSPHERE_LOWER_Y)
                .set(current.atmosphereProfile().lowerY());
        property(config, category, ATMOSPHERE_UPPER_Y_KEY, DEFAULT_ATMOSPHERE_UPPER_Y)
                .set(current.atmosphereProfile().upperY());
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
        compound.setDouble("sideFeatherBlocks", current.sideFeatherBlocks());
        compound.setInteger("sunshadeHeightBlocks", current.sunshadeHeightBlocks());
        compound.setInteger("sunshadeThicknessBlocks", current.sunshadeThicknessBlocks());
        compound.setDouble("thinAtmosphereFadeStartY", current.thinAtmosphereFadeStartY());
        compound.setDouble("atmosphereLowerY", current.atmosphereProfile().lowerY());
        compound.setDouble("atmosphereUpperY", current.atmosphereProfile().upperY());
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
        if (schemaVersion != 1 && schemaVersion != 2 && schemaVersion != 3 && schemaVersion != 4
                && schemaVersion != SCHEMA_VERSION) {
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
        double thinAtmosphereFadeStartY = schemaVersion >= 3
                ? readThinAtmosphereFadeStartY(compound)
                : LEGACY_THIN_ATMOSPHERE_FADE_START_Y;
        double sideFeatherBlocks = schemaVersion >= 4
                ? readSideFeatherBlocks(compound)
                : 0.0;
        double atmosphereLowerY = schemaVersion == SCHEMA_VERSION
                ? readAtmosphereLowerY(compound) : LEGACY_ATMOSPHERE_LOWER_Y;
        double atmosphereUpperY = schemaVersion == SCHEMA_VERSION
                ? readAtmosphereUpperY(compound) : LEGACY_ATMOSPHERE_UPPER_Y;
        return State.create(enabledValue == 1,
                compound.getDouble("spacingBlocks"),
                compound.getDouble("shadowWidthBlocks"),
                compound.getInteger("cycleTicks"),
                compound.getDouble("phaseOffsetBlocks"),
                compound.getDouble("headingDegrees"),
                compound.getDouble("featherBlocks"), sideFeatherBlocks, sunshadeHeightBlocks, sunshadeThicknessBlocks,
                thinAtmosphereFadeStartY, atmosphereLowerY, atmosphereUpperY);
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

    private static double readSideFeatherBlocks(NBTTagCompound compound) {
        requireType(compound, "sideFeatherBlocks", 6);
        return compound.getDouble("sideFeatherBlocks");
    }

    private static double readAtmosphereLowerY(NBTTagCompound compound) {
        requireType(compound, "atmosphereLowerY", 6);
        return compound.getDouble("atmosphereLowerY");
    }

    private static double readAtmosphereUpperY(NBTTagCompound compound) {
        requireType(compound, "atmosphereUpperY", 6);
        return compound.getDouble("atmosphereUpperY");
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

    /**
     * Old config categories predate both persisted air bounds. Only that fully
     * missing pair is migrated: a partially configured pair preserves its
     * explicit side and receives the current default for its missing side.
     */
    private static boolean hasLegacyRingworldSettingsWithoutAirBounds(Configuration config, String category) {
        if (config.getCategory(category).containsKey(ATMOSPHERE_LOWER_Y_KEY)
                || config.getCategory(category).containsKey(ATMOSPHERE_UPPER_Y_KEY)) {
            return false;
        }
        return config.getCategory(category).containsKey(ENABLED_KEY)
                || config.getCategory(category).containsKey(SPACING_BLOCKS_KEY)
                || config.getCategory(category).containsKey(SHADOW_WIDTH_BLOCKS_KEY)
                || config.getCategory(category).containsKey(CYCLE_TICKS_KEY)
                || config.getCategory(category).containsKey(PHASE_OFFSET_BLOCKS_KEY)
                || config.getCategory(category).containsKey(HEADING_DEGREES_KEY)
                || config.getCategory(category).containsKey(FEATHER_BLOCKS_KEY)
                || config.getCategory(category).containsKey(SIDE_FEATHER_BLOCKS_KEY)
                || config.getCategory(category).containsKey(SUNSHADE_HEIGHT_BLOCKS_KEY)
                || config.getCategory(category).containsKey(SUNSHADE_THICKNESS_BLOCKS_KEY)
                || config.getCategory(category).containsKey(THIN_ATMOSPHERE_FADE_START_Y_KEY);
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
                         double sideFeatherBlocks,
                         int sunshadeHeightBlocks,
                         int sunshadeThicknessBlocks,
                         double thinAtmosphereFadeStartY,
                         RingworldAirProfile atmosphereProfile,
                         RingworldSunshade sunshade) {
        private static State defaults() {
            return create(false, DEFAULT_SPACING_BLOCKS, DEFAULT_SHADOW_WIDTH_BLOCKS, DEFAULT_CYCLE_TICKS,
                    0.0, 0.0, DEFAULT_FEATHER_BLOCKS, DEFAULT_SIDE_FEATHER_BLOCKS, DEFAULT_HEIGHT_BLOCKS, 32,
                    DEFAULT_THIN_ATMOSPHERE_FADE_START_Y, DEFAULT_ATMOSPHERE_LOWER_Y, DEFAULT_ATMOSPHERE_UPPER_Y);
        }

        private static State create(boolean enabled,
                                    double spacingBlocks,
                                    double shadowWidthBlocks,
                                    int cycleTicks,
                                    double phaseOffsetBlocks,
                                    double headingDegrees,
                                    double featherBlocks,
                                    double sideFeatherBlocks,
                                    int sunshadeHeightBlocks,
                                    int sunshadeThicknessBlocks,
                                    double thinAtmosphereFadeStartY,
                                    double atmosphereLowerY,
                                    double atmosphereUpperY) {
            validateSunshadeGeometry(sunshadeHeightBlocks, sunshadeThicknessBlocks);
            RingworldAirProfile atmosphereProfile = new RingworldAirProfile(atmosphereLowerY,
                    thinAtmosphereFadeStartY, atmosphereUpperY);
            RingworldSunshade validatedSunshade = new RingworldSunshade(
                    spacingBlocks, shadowWidthBlocks, cycleTicks,
                    phaseOffsetBlocks, headingDegrees, featherBlocks, sideFeatherBlocks);
            return new State(enabled, spacingBlocks, shadowWidthBlocks, cycleTicks,
                    phaseOffsetBlocks, headingDegrees, featherBlocks, sideFeatherBlocks,
                    sunshadeHeightBlocks, sunshadeThicknessBlocks,
                    thinAtmosphereFadeStartY, atmosphereProfile, enabled ? validatedSunshade : null);
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

    }
}
