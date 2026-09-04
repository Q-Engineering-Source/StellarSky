package stellarium.world.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.block.BlockDaylightDetector;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import stellarium.stellars.StellarManager;
import stellarium.time.StellarSkyTime;

/**
 * Dedicated-server probe for the normal, world-owned R1 local-light path.
 * It does nothing unless the exact request marker is present in the server root.
 */
@Mod(modid = R1LocalLightProbeMod.MODID, name = "R1 Local Light Probe", version = "1",
        acceptableRemoteVersions = "*", serverSideOnly = true,
        dependencies = "required-after:stellarapi;required-after:stellarsky")
public final class R1LocalLightProbeMod {
    public static final String MODID = "r1locallightprobe";

    private static final Logger LOGGER = LogManager.getLogger(MODID);
    private static final Path REQUEST = Path.of("r1-local-light-probe.request");
    private static final Path INVALID_SETTINGS = Path.of("r1-invalid-ring-settings.cfg");
    private static final String REQUEST_CONTENT = "local-light-b1";
    private static final String EXPECTED_WORLD = "r1-local-light-20260904";
    private static final int PLATFORM_Y = 200;
    private static final int SAMPLE_Y = PLATFORM_Y + 1;
    private static final BlockPos SHADE = new BlockPos(0, SAMPLE_Y, 0);
    private static final BlockPos LIGHT = new BlockPos(32, SAMPLE_Y, 0);
    private static final BlockPos SHADE_BLOCK = new BlockPos(0, SAMPLE_Y, 8);
    private static final BlockPos SHADE_NORMAL_DETECTOR = new BlockPos(0, SAMPLE_Y, 16);
    private static final BlockPos LIGHT_NORMAL_DETECTOR = new BlockPos(32, SAMPLE_Y, 16);
    private static final BlockPos SHADE_INVERTED_DETECTOR = new BlockPos(0, SAMPLE_Y, 24);
    private static final BlockPos LIGHT_INVERTED_DETECTOR = new BlockPos(32, SAMPLE_Y, 24);
    private static final long SPAWN_RNG_SEED = 0L;
    // new Random(5120).nextFloat() is 0.00616163015365601, below the burn gate.
    private static final long BURN_RNG_SEED = 5120L;

    private MinecraftServer server;
    private WorldServer world;
    private StellarManager manager;
    private Baseline baseline;
    private final Map<BlockPos, IBlockState> replacedBlocks = new LinkedHashMap<>();
    private Stage stage = Stage.IDLE;
    private boolean finished;

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (!hasExactRequest()) {
            return;
        }
        try {
            // Consume this validated, test-only request before any world changes.
            // A subsequent server start must never rerun the destructive fixture.
            Files.delete(REQUEST);
            server = FMLCommonHandler.instance().getMinecraftServerInstance();
            world = server == null ? null : server.getWorld(0);
            require(world != null, "missing_overworld");
            require(EXPECTED_WORLD.equals(world.getWorldInfo().getWorldName()), "unexpected_world_name");
            require(world.playerEntities.isEmpty() && server.getPlayerList().getPlayers().isEmpty(),
                    "real_players_present");
            verifyInvalidSettingsFileLoad();

            manager = StellarManager.getManager(world);
            baseline = Baseline.capture(world, manager);
            prepareStableClearWeather();
            preparePlatforms();
            armTime(0L, Stage.WAIT_ZERO);
            LOGGER.info("[R1LL] START world={} targetTimes=0,50 marker={}",
                    world.getWorldInfo().getWorldName(), REQUEST);
        } catch (Throwable throwable) {
            failAndShutdown("startup_failed", throwable);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onWorldTickEndLowest(TickEvent.WorldTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END || event.world != world || stage == Stage.IDLE) {
            return;
        }
        try {
            require(world.playerEntities.isEmpty() && server.getPlayerList().getPlayers().isEmpty(),
                    "real_players_joined_during_probe");
            if (stage == Stage.WAIT_ZERO) {
                requirePublishedFrame(0L);
                runTimeZeroCases();
                armTime(50L, Stage.WAIT_FIFTY);
            } else if (stage == Stage.WAIT_FIFTY) {
                requirePublishedFrame(50L);
                runTimeFiftyCase();
                finishAndShutdown();
            }
        } catch (Throwable throwable) {
            failAndShutdown("case_failed_" + stage, throwable);
        }
    }

    private boolean hasExactRequest() {
        if (!Files.isRegularFile(REQUEST)) {
            return false;
        }
        try {
            return REQUEST_CONTENT.equals(Files.readString(REQUEST, StandardCharsets.UTF_8).strip());
        } catch (IOException exception) {
            LOGGER.error("[R1LL] REQUEST_READ_FAILED file={}", REQUEST, exception);
            return false;
        }
    }

    private void verifyInvalidSettingsFileLoad() {
        require(Files.isRegularFile(INVALID_SETTINGS), "missing_invalid_settings_fixture");
        Configuration config = new Configuration(INVALID_SETTINGS.toFile());
        config.load();
        RingworldSettings settings = new RingworldSettings();
        settings.setupConfig(config, "ringworld");
        Property raw = config.getCategory("ringworld").get("Spacing_Blocks");
        require(raw != null && "oops".equals(raw.getString()), "invalid_file_raw_value_changed_before_load");
        boolean rejected = false;
        try {
            settings.loadFromConfig(config, "ringworld");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "invalid_file_settings_were_accepted");
        require("oops".equals(raw.getString()), "invalid_file_raw_value_was_healed");
        LOGGER.info("[R1LL] CASE id=config_file_invalid_typed_double result=PASS raw={}", raw.getString());
    }

    private void prepareStableClearWeather() {
        WorldInfo info = world.getWorldInfo();
        GameRules rules = world.getGameRules();
        // The merged dev JAR exposes client-only strength setters; a real
        // dedicated server strips them. Use an already-clear test world and
        // retain its normal server weather interpolation instead of injecting it.
        require(!info.isRaining() && !info.isThundering()
                        && world.getRainStrength(1.0F) == 0.0F
                        && world.getThunderStrength(1.0F) == 0.0F,
                "probe_requires_initially_clear_weather");
        rules.setOrCreateGameRule("doDaylightCycle", "true");
        rules.setOrCreateGameRule("doWeatherCycle", "false");
        info.setRaining(false);
        info.setThundering(false);
        info.setRainTime(0);
        info.setThunderTime(0);
        manager.setSystemTimeSyncEnabled(0, false);
        manager.setTimeMultiplier(0, 0.0);
        StellarSkyTime.resetSystemTimeCorrection(world);
    }

    private void armTime(long time, Stage nextStage) {
        world.setWorldTime(time);
        world.setSkylightSubtracted(world.calculateSkylightSubtracted(1.0F));
        stage = nextStage;
    }

    private void requirePublishedFrame(long expectedTime) {
        require(manager.getTimeMultiplier(0) == 0.0, "multiplier_not_zero");
        require(!manager.isSystemTimeSyncEnabled(0), "system_time_sync_enabled");
        require(!world.getWorldInfo().isRaining() && !world.getWorldInfo().isThundering(), "weather_not_clear");
        require(world.getWorldTime() == expectedTime, "world_time_drifted_" + world.getWorldTime());
        RingworldLightFrame frame = RingworldLighting.frame(world);
        require(frame != null, "production_frame_missing");
        require(frame.worldTime() == expectedTime, "frame_time_mismatch_" + frame.worldTime());
        LOGGER.info("[R1LL] CASE id=production_frame_time_{} phase=END result=PASS frameTime={}",
                expectedTime, frame.worldTime());
    }

    private void runTimeZeroCases() {
        assertRawAndEffective("t0", SHADE, 15, 0);
        assertRawAndEffective("t0", LIGHT, 15, 15);
        runBlockLightCase();
        runDaylightDetectorCase();
        runSpawnCase();
        runUndeadBurnCases();
    }

    private void runTimeFiftyCase() {
        assertRawAndEffective("t50", SHADE, 15, 15);
        assertRawAndEffective("t50", LIGHT, 15, 0);
    }

    private void assertRawAndEffective(String timeId, BlockPos pos, int expectedRawSky, int expectedEffective) {
        int rawSky = world.getLightFor(EnumSkyBlock.SKY, pos);
        int effective = world.getLight(pos, true);
        require(rawSky == expectedRawSky,
                timeId + "_raw_sky_" + pos + "_expected_" + expectedRawSky + "_actual_" + rawSky);
        require(effective == expectedEffective,
                timeId + "_effective_light_" + pos + "_expected_" + expectedEffective + "_actual_" + effective);
        LOGGER.info("[R1LL] CASE id=raw_effective_{}_{} result=PASS rawSky={} effective={}",
                timeId, pos.getX(), rawSky, effective);
    }

    private void runBlockLightCase() {
        preparePlatform(SHADE_BLOCK);
        BlockPos glowstone = SHADE_BLOCK.east();
        requireAir(glowstone);
        replace(glowstone, Blocks.GLOWSTONE.getDefaultState());
        world.checkLight(SHADE_BLOCK);
        int glowstoneBlock = world.getLightFor(EnumSkyBlock.BLOCK, SHADE_BLOCK);
        int glowstoneEffective = world.getLight(SHADE_BLOCK, true);
        require(glowstoneBlock > 0 && glowstoneEffective == glowstoneBlock,
                "glowstone_block_light_not_preserved_" + glowstoneBlock + "_" + glowstoneEffective);

        replace(glowstone, Blocks.TORCH.getDefaultState());
        world.checkLight(SHADE_BLOCK);
        int torchBlock = world.getLightFor(EnumSkyBlock.BLOCK, SHADE_BLOCK);
        int torchEffective = world.getLight(SHADE_BLOCK, true);
        require(torchBlock > 0 && torchEffective == torchBlock,
                "torch_block_light_not_preserved_" + torchBlock + "_" + torchEffective);
        replace(glowstone, Blocks.AIR.getDefaultState());
        world.checkLight(glowstone);
        world.checkLight(SHADE_BLOCK);
        require(world.getLightFor(EnumSkyBlock.BLOCK, SHADE_BLOCK) == 0,
                "block_light_fixture_not_cleared");
        LOGGER.info("[R1LL] CASE id=block_light_preserved result=PASS glowstone={} torch={}",
                glowstoneBlock, torchBlock);
    }

    private void runDaylightDetectorCase() {
        preparePlatform(SHADE_NORMAL_DETECTOR);
        preparePlatform(LIGHT_NORMAL_DETECTOR);
        preparePlatform(SHADE_INVERTED_DETECTOR);
        preparePlatform(LIGHT_INVERTED_DETECTOR);
        replace(SHADE_NORMAL_DETECTOR, Blocks.DAYLIGHT_DETECTOR.getDefaultState());
        replace(LIGHT_NORMAL_DETECTOR, Blocks.DAYLIGHT_DETECTOR.getDefaultState());
        replace(SHADE_INVERTED_DETECTOR, Blocks.DAYLIGHT_DETECTOR_INVERTED.getDefaultState());
        replace(LIGHT_INVERTED_DETECTOR, Blocks.DAYLIGHT_DETECTOR_INVERTED.getDefaultState());

        ((BlockDaylightDetector) Blocks.DAYLIGHT_DETECTOR).updatePower(world, SHADE_NORMAL_DETECTOR);
        ((BlockDaylightDetector) Blocks.DAYLIGHT_DETECTOR).updatePower(world, LIGHT_NORMAL_DETECTOR);
        ((BlockDaylightDetector) Blocks.DAYLIGHT_DETECTOR_INVERTED).updatePower(world, SHADE_INVERTED_DETECTOR);
        ((BlockDaylightDetector) Blocks.DAYLIGHT_DETECTOR_INVERTED).updatePower(world, LIGHT_INVERTED_DETECTOR);

        int normalShade = detectorPower(SHADE_NORMAL_DETECTOR);
        int normalLight = detectorPower(LIGHT_NORMAL_DETECTOR);
        int invertedShade = detectorPower(SHADE_INVERTED_DETECTOR);
        int invertedLight = detectorPower(LIGHT_INVERTED_DETECTOR);
        require(normalShade == 0 && normalLight == 15,
                "normal_detector_power_" + normalShade + "_" + normalLight);
        require(invertedShade == 15 && invertedLight == 0,
                "inverted_detector_power_" + invertedShade + "_" + invertedLight);
        LOGGER.info("[R1LL] CASE id=daylight_detectors result=PASS normal={}/{} inverted={}/{}",
                normalShade, normalLight, invertedShade, invertedLight);
    }

    private void runSpawnCase() {
        require(world.getDifficulty() != EnumDifficulty.PEACEFUL, "peaceful_difficulty_blocks_spawn_probe");
        EntityZombie shadeZombie = zombieAt(SHADE);
        EntityZombie lightZombie = zombieAt(LIGHT);
        shadeZombie.getRNG().setSeed(SPAWN_RNG_SEED);
        lightZombie.getRNG().setSeed(SPAWN_RNG_SEED);
        boolean shadeCanSpawn = shadeZombie.getCanSpawnHere();
        boolean lightCanSpawn = lightZombie.getCanSpawnHere();
        require(shadeCanSpawn && !lightCanSpawn,
                "zombie_spawn_local_light_" + shadeCanSpawn + "_" + lightCanSpawn);
        LOGGER.info("[R1LL] CASE id=zombie_spawn_local_light result=PASS shade={} light={}",
                shadeCanSpawn, lightCanSpawn);
    }

    private void runUndeadBurnCases() {
        assertBurn("zombie_shade", zombieAt(SHADE), false);
        assertBurn("skeleton_shade", skeletonAt(SHADE), false);
        assertBurn("zombie_light", zombieAt(LIGHT), true);
        assertBurn("skeleton_light", skeletonAt(LIGHT), true);

        BlockPos roof = LIGHT.up(2);
        requireAir(roof);
        replace(roof, Blocks.STONE.getDefaultState());
        require(!world.canSeeSky(LIGHT), "roof_fixture_does_not_cover_sky");
        assertBurn("zombie_roof", zombieAt(LIGHT), false);
        replace(roof, Blocks.AIR.getDefaultState());

        EntityZombie helmetZombie = zombieAt(LIGHT);
        helmetZombie.setItemStackToSlot(EntityEquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        assertBurn("zombie_helmet", helmetZombie, false);

        preparePlatform(SHADE_BLOCK);
        requireAir(SHADE_BLOCK.east());
        replace(SHADE_BLOCK.east(), Blocks.TORCH.getDefaultState());
        world.checkLight(SHADE_BLOCK);
        require(world.getLightFor(EnumSkyBlock.BLOCK, SHADE_BLOCK) > 0, "torch_fixture_did_not_propagate");
        assertBurn("zombie_torch_under_shade", zombieAt(SHADE_BLOCK), false);
        replace(SHADE_BLOCK.east(), Blocks.AIR.getDefaultState());
        world.checkLight(SHADE_BLOCK.east());
        world.checkLight(SHADE_BLOCK);
        require(world.getLightFor(EnumSkyBlock.BLOCK, SHADE_BLOCK) == 0,
                "undead_torch_fixture_not_cleared");
        LOGGER.info("[R1LL] CASE id=undead_sunlight_consumers result=PASS");
    }

    private void assertBurn(String id, EntityLivingBase undead, boolean expectedBurning) {
        undead.extinguish();
        undead.getRNG().setSeed(BURN_RNG_SEED);
        undead.onLivingUpdate();
        boolean burning = undead.isBurning();
        require(burning == expectedBurning, id + "_burning_" + burning + "_expected_" + expectedBurning);
        LOGGER.info("[R1LL] CASE id={} result=PASS burning={}", id, burning);
    }

    private int detectorPower(BlockPos pos) {
        return world.getBlockState(pos).getValue(BlockDaylightDetector.POWER);
    }

    private EntityZombie zombieAt(BlockPos pos) {
        EntityZombie zombie = new EntityZombie(world);
        position(zombie, pos);
        return zombie;
    }

    private EntitySkeleton skeletonAt(BlockPos pos) {
        EntitySkeleton skeleton = new EntitySkeleton(world);
        position(skeleton, pos);
        return skeleton;
    }

    private static void position(EntityLiving undead, BlockPos pos) {
        undead.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        // Keep navigation/AI out of a direct sunlight-consumer probe. Vanilla
        // Zombie/AbstractSkeleton still run their sunlight branch with NoAI.
        undead.setNoAI(true);
    }

    private void preparePlatforms() {
        // Vanilla propagation refuses updates without a loaded neighborhood.
        // Load the fixture and its 18-block halo through the normal chunk API.
        for (int chunkX = -2; chunkX <= 3; chunkX++) {
            for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
        require(world.isAreaLoaded(SHADE_BLOCK, 18, false)
                        && world.isAreaLoaded(LIGHT_INVERTED_DETECTOR, 18, false),
                "light_propagation_fixture_halo_not_loaded");
        preparePlatform(SHADE);
        preparePlatform(LIGHT);
        preparePlatform(SHADE_BLOCK);
    }

    private void preparePlatform(BlockPos sample) {
        requireAir(sample);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                replace(new BlockPos(sample.getX() + x, PLATFORM_Y, sample.getZ() + z), Blocks.STONE.getDefaultState());
            }
        }
    }

    private void requireAir(BlockPos pos) {
        require(world.getBlockState(pos).getBlock() == Blocks.AIR, "fixture_expected_air_" + pos);
        require(world.getTileEntity(pos) == null, "fixture_expected_no_tile_entity_" + pos);
    }

    private void replace(BlockPos pos, IBlockState state) {
        if (!replacedBlocks.containsKey(pos)) {
            require(world.getTileEntity(pos) == null, "fixture_replaces_tile_entity_" + pos);
            replacedBlocks.put(pos, world.getBlockState(pos));
        }
        if (world.getBlockState(pos).equals(state)) {
            return;
        }
        require(world.setBlockState(pos, state, 3), "set_block_failed_" + pos);
    }

    private void restoreBlocks() {
        List<Map.Entry<BlockPos, IBlockState>> entries = new ArrayList<>(replacedBlocks.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<BlockPos, IBlockState> entry) -> entry.getKey().getY()).reversed());
        for (Map.Entry<BlockPos, IBlockState> entry : entries) {
            if (!world.getBlockState(entry.getKey()).equals(entry.getValue())) {
                require(world.setBlockState(entry.getKey(), entry.getValue(), 3),
                        "restore_block_write_failed_" + entry.getKey());
            }
            require(world.getBlockState(entry.getKey()).equals(entry.getValue()),
                    "restore_block_state_mismatch_" + entry.getKey());
        }
        replacedBlocks.clear();
    }

    private void finishAndShutdown() {
        if (finished) {
            return;
        }
        try {
            restoreFixture();
        } catch (Throwable throwable) {
            finishWithFailure("restore_failed", throwable);
            return;
        }
        finished = true;
        LOGGER.info("[R1LL] RESULT result=PASS restored=true observationPhase=END");
        server.initiateShutdown();
    }

    private void failAndShutdown(String reason, Throwable throwable) {
        if (finished) {
            return;
        }
        try {
            restoreFixture();
        } catch (Throwable restoreFailure) {
            if (throwable != null) {
                throwable.addSuppressed(restoreFailure);
            } else {
                throwable = restoreFailure;
                reason = reason + "_and_restore_failed";
            }
        }
        finishWithFailure(reason, throwable);
    }

    private void finishWithFailure(String reason, Throwable throwable) {
        finished = true;
        if (throwable == null) {
            LOGGER.error("[R1LL] RESULT result=FAIL reason={}", reason);
        } else {
            LOGGER.error("[R1LL] RESULT result=FAIL reason={}", reason, throwable);
        }
        MinecraftServer current = server != null ? server : FMLCommonHandler.instance().getMinecraftServerInstance();
        if (current != null) {
            current.initiateShutdown();
        }
    }

    private void restoreFixture() {
        try {
            if (world != null) {
                restoreBlocks();
            }
        } finally {
            if (world != null && manager != null && baseline != null) {
                baseline.restore(world, manager);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private enum Stage {
        IDLE,
        WAIT_ZERO,
        WAIT_FIFTY
    }

    private static final class Baseline {
        private final long worldTime;
        private final long totalWorldTime;
        private final int skylightSubtracted;
        private final boolean daylightCycle;
        private final boolean weatherCycle;
        private final boolean raining;
        private final boolean thundering;
        private final int rainTime;
        private final int thunderTime;
        private final float rainStrength;
        private final float thunderStrength;
        private final double multiplier;
        private final boolean systemSync;

        private Baseline(long worldTime, long totalWorldTime, int skylightSubtracted,
                         boolean daylightCycle, boolean weatherCycle, boolean raining, boolean thundering,
                         int rainTime, int thunderTime, float rainStrength, float thunderStrength,
                         double multiplier, boolean systemSync) {
            this.worldTime = worldTime;
            this.totalWorldTime = totalWorldTime;
            this.skylightSubtracted = skylightSubtracted;
            this.daylightCycle = daylightCycle;
            this.weatherCycle = weatherCycle;
            this.raining = raining;
            this.thundering = thundering;
            this.rainTime = rainTime;
            this.thunderTime = thunderTime;
            this.rainStrength = rainStrength;
            this.thunderStrength = thunderStrength;
            this.multiplier = multiplier;
            this.systemSync = systemSync;
        }

        private static Baseline capture(WorldServer world, StellarManager manager) {
            WorldInfo info = world.getWorldInfo();
            GameRules rules = world.getGameRules();
            return new Baseline(world.getWorldTime(), world.getTotalWorldTime(), world.getSkylightSubtracted(),
                    rules.getBoolean("doDaylightCycle"), rules.getBoolean("doWeatherCycle"),
                    info.isRaining(), info.isThundering(), info.getRainTime(), info.getThunderTime(),
                    world.getRainStrength(1.0F), world.getThunderStrength(1.0F),
                    manager.getTimeMultiplier(0), manager.isSystemTimeSyncEnabled(0));
        }

        private void restore(WorldServer world, StellarManager manager) {
            world.getGameRules().setOrCreateGameRule("doDaylightCycle", Boolean.toString(daylightCycle));
            world.getGameRules().setOrCreateGameRule("doWeatherCycle", Boolean.toString(weatherCycle));
            world.setWorldTime(worldTime);
            world.getWorldInfo().setWorldTotalTime(totalWorldTime);
            world.setSkylightSubtracted(skylightSubtracted);
            WorldInfo info = world.getWorldInfo();
            info.setRaining(raining);
            info.setThundering(thundering);
            info.setRainTime(rainTime);
            info.setThunderTime(thunderTime);
            manager.setTimeMultiplier(0, multiplier);
            manager.setSystemTimeSyncEnabled(0, systemSync);
            StellarSkyTime.resetSystemTimeCorrection(world);
            require(world.getRainStrength(1.0F) == rainStrength
                            && world.getThunderStrength(1.0F) == thunderStrength,
                    "restore_weather_strength_mismatch");
        }
    }
}
