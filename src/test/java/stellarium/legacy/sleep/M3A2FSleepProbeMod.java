package stellarium.legacy.sleep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.authlib.GameProfile;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayer.SleepResult;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import stellarapi.api.SAPIReferences;
import stellarium.stellars.StellarManager;

@Mod(modid = M3A2FSleepProbeMod.MODID, name = "M3A-2F Sleep Probe", version = "1",
        acceptableRemoteVersions = "*", serverSideOnly = true,
        dependencies = "required-after:stellarapi;required-after:stellarsky")
public final class M3A2FSleepProbeMod {
    public static final String MODID = "m3a2fsleepprobe";
    private static final Logger LOGGER = LogManager.getLogger(MODID);
    private static final Path REQUEST = Path.of("m3a2f-sleep-probe.request");

    private final List<Scenario> scenarios = List.of(
            new Scenario("daylight_m1", true, true, 1.0),
            new Scenario("daylight_m0", true, true, 0.0),
            new Scenario("daylight_mneg1", true, true, -1.0),
            new Scenario("no_daylight_m1", false, true, 1.0),
            new Scenario("no_weather_reset", true, false, 1.0));

    private MinecraftServer server;
    private WorldServer world;
    private StellarManager manager;
    private String phase;
    private Baseline baseline;
    private int scenarioIndex;
    private ActiveScenario activeScenario;
    private boolean active;
    private boolean finished;

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if(!Files.isRegularFile(REQUEST))
            return;

        try {
            this.phase = Files.readString(REQUEST, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        } catch(IOException exception) {
            failAndShutdown("request_read_failed", exception);
            return;
        }
        if(!"sapi".equals(this.phase) && !"vanilla".equals(this.phase) && !"matrix".equals(this.phase)) {
            failAndShutdown("invalid_phase_" + this.phase, null);
            return;
        }

        this.server = FMLCommonHandler.instance().getMinecraftServerInstance();
        this.world = this.server.getWorld(0);
        if(this.world == null) {
            failAndShutdown("missing_overworld", null);
            return;
        }
        if(!players(this.world).isEmpty() || !this.server.getPlayerList().getPlayers().isEmpty()) {
            failAndShutdown("real_players_present", null);
            return;
        }

        boolean wakeEnabled = SAPIReferences.getSleepWakeManager().isEnabled();
        if(!"matrix".equals(this.phase) && wakeEnabled != "sapi".equals(this.phase)) {
            failAndShutdown("wake_config_mismatch_expected_" + this.phase + "_actual_" + wakeEnabled, null);
            return;
        }

        this.manager = StellarManager.getManager(this.world);
        this.baseline = Baseline.capture(this.world, this.manager);
        try {
            runEligibilityMatrix();
            runQuorumMatrix();
        } catch(RuntimeException exception) {
            failAndShutdown("preflight_failed", exception);
            return;
        }

        if("matrix".equals(this.phase)) {
            this.baseline.restore(this.world, this.manager);
            this.finished = true;
            LOGGER.info("[M3A2F] RESULT phase=matrix result=PASS restored=true");
            this.server.initiateShutdown();
            return;
        }

        this.active = true;
        LOGGER.info("[M3A2F] START phase={} wakeEnabled={} scenarios={} baselineTime={}",
                this.phase, wakeEnabled, this.scenarios.size(), this.baseline.worldTime);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldTickStartHighest(TickEvent.WorldTickEvent event) {
        if(!isTarget(event, TickEvent.Phase.START) || this.activeScenario != null)
            return;
        if(this.scenarioIndex >= this.scenarios.size())
            return;

        try {
            Scenario scenario = this.scenarios.get(this.scenarioIndex);
            this.activeScenario = ActiveScenario.prepare(this.world, this.manager, scenario);
            LOGGER.info("[M3A2F] ARMED phase={} id={} startTime={} multiplier={} daylight={} weather={}",
                    this.phase, scenario.id, this.activeScenario.startTime, scenario.multiplier,
                    scenario.daylightCycle, scenario.weatherCycle);
        } catch(RuntimeException exception) {
            failAndShutdown("scenario_prepare_failed_" + this.scenarioIndex, exception);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onWorldTickStartLowest(TickEvent.WorldTickEvent event) {
        if(!isTarget(event, TickEvent.Phase.START) || this.activeScenario == null)
            return;
        this.activeScenario.captureAfterStart(this.world);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onWorldTickEnd(TickEvent.WorldTickEvent event) {
        if(!isTarget(event, TickEvent.Phase.END) || this.activeScenario == null)
            return;
        try {
            this.activeScenario.captureAfterEnd(this.world);
            boolean pass = this.activeScenario.validate(this.phase);
            LOGGER.info("[M3A2F] CASE phase={} id={} start={} afterStart={} afterEnd={} "
                            + "startSleeping={} endSleeping={} wakeCalls={} spawnCommitted={} "
                            + "rainStart={} rainAfterStart={} rainAfterEnd={} result={}",
                    this.phase, this.activeScenario.scenario.id,
                    this.activeScenario.startTime, this.activeScenario.afterStartTime,
                    this.activeScenario.afterEndTime, this.activeScenario.sleepingAfterStart,
                    this.activeScenario.sleepingAfterEnd, this.activeScenario.player.wakeCalls,
                    this.activeScenario.spawnCommitted(), this.activeScenario.rainingAtStart,
                    this.activeScenario.rainingAfterStart, this.activeScenario.rainingAfterEnd,
                    pass ? "PASS" : "FAIL");
            this.activeScenario.cleanup(this.world);
            this.activeScenario = null;
            this.scenarioIndex++;
            if(!pass) {
                failAndShutdown("scenario_assertion_failed_" + (this.scenarioIndex - 1), null);
                return;
            }
            if(this.scenarioIndex == this.scenarios.size())
                finishAndShutdown();
        } catch(RuntimeException exception) {
            failAndShutdown("scenario_evaluate_failed_" + this.scenarioIndex, exception);
        }
    }

    private boolean isTarget(TickEvent.WorldTickEvent event, TickEvent.Phase expectedPhase) {
        return this.active && !this.finished && event.phase == expectedPhase && event.world == this.world;
    }

    private void runEligibilityMatrix() {
        long originalTime = this.world.getWorldTime();
        int originalSkylight = this.world.getSkylightSubtracted();
        BedFixture bed = BedFixture.place(this.world);
        try {
            this.world.setWorldTime(14000L);
            this.world.setSkylightSubtracted(this.world.calculateSkylightSubtracted(1.0f));
            ProbePlayer valid = new ProbePlayer(this.world, "eligibility_valid", false, false, false);
            valid.setPosition(bed.position.getX(), bed.position.getY(), bed.position.getZ());
            SleepResult night = valid.trySleep(bed.position);
            valid.wakeUpPlayer(true, true, false);

            this.world.setWorldTime(1000L);
            this.world.setSkylightSubtracted(this.world.calculateSkylightSubtracted(1.0f));
            ProbePlayer day = new ProbePlayer(this.world, "eligibility_day", false, false, false);
            day.setPosition(bed.position.getX(), bed.position.getY(), bed.position.getZ());
            SleepResult daytime = day.trySleep(bed.position);

            ProbePlayer far = new ProbePlayer(this.world, "eligibility_far", false, false, false);
            far.setPosition(bed.position.getX() + 4.0, bed.position.getY(), bed.position.getZ());
            this.world.setWorldTime(14000L);
            this.world.setSkylightSubtracted(this.world.calculateSkylightSubtracted(1.0f));
            SleepResult distance = far.trySleep(bed.position);

            LOGGER.info("[M3A2F] ELIGIBILITY night={} day={} far={} result={}",
                    night, daytime, distance,
                    night == SleepResult.OK && distance == SleepResult.TOO_FAR_AWAY ? "PASS" : "FAIL");
        } finally {
            bed.restore(this.world);
            this.world.setWorldTime(originalTime);
            this.world.setSkylightSubtracted(originalSkylight);
        }
    }

    private void runQuorumMatrix() {
        List<EntityPlayer> original = new ArrayList<>(players(this.world));
        WorldServer end = this.server.getWorld(1);
        if(end == null)
            throw new IllegalStateException("End world is not loaded");
        List<EntityPlayer> originalEnd = new ArrayList<>(players(end));
        try {
            boolean empty = quorum();
            boolean oneNotFull = quorum(new ProbePlayer(this.world, "q_not_full", true, false, false));
            boolean oneFull = quorum(new ProbePlayer(this.world, "q_full", true, true, false));
            boolean fullAndAwake = quorum(
                    new ProbePlayer(this.world, "q_full_2", true, true, false),
                    new ProbePlayer(this.world, "q_awake", false, false, false));
            boolean fullAndSpectator = quorum(
                    new ProbePlayer(this.world, "q_full_3", true, true, false),
                    new ProbePlayer(this.world, "q_spectator", false, false, true));
            boolean onlySpectator = quorum(new ProbePlayer(this.world, "q_only_spectator", false, false, true));
            boolean pass = !empty && !oneNotFull && oneFull && !fullAndAwake
                    && fullAndSpectator && !onlySpectator;
            LOGGER.info("[M3A2F] QUORUM empty={} oneNotFull={} oneFull={} fullAndAwake={} "
                            + "fullAndSpectator={} onlySpectator={} result={}",
                    empty, oneNotFull, oneFull, fullAndAwake, fullAndSpectator, onlySpectator,
                    pass ? "PASS" : "FAIL");
            if(!pass)
                throw new IllegalStateException("quorum matrix mismatch");

            ProbePlayer overworldSleeper = new ProbePlayer(this.world, "q_multidim_overworld", true, true, false);
            boolean overworldOnly = quorum(this.world, overworldSleeper) && !quorum(end);
            ProbePlayer endSleeper = new ProbePlayer(end, "q_multidim_end", true, true, false);
            boolean endOnly = !quorum(this.world) && quorum(end, endSleeper);
            boolean multiPass = overworldOnly && endOnly;
            LOGGER.info("[M3A2F] MULTIDIM overworldOnly={} endOnly={} result={}",
                    overworldOnly, endOnly, multiPass ? "PASS" : "FAIL");
            if(!multiPass)
                throw new IllegalStateException("multi-dimension quorum mismatch");
        } finally {
            players(this.world).clear();
            players(this.world).addAll(original);
            this.world.updateAllPlayersSleepingFlag();
            players(end).clear();
            players(end).addAll(originalEnd);
            end.updateAllPlayersSleepingFlag();
        }
    }

    private boolean quorum(ProbePlayer... players) {
        return quorum(this.world, players);
    }

    private static boolean quorum(WorldServer world, ProbePlayer... probePlayers) {
        players(world).clear();
        players(world).addAll(List.of(probePlayers));
        world.updateAllPlayersSleepingFlag();
        return world.areAllPlayersAsleep();
    }

    private void finishAndShutdown() {
        this.finished = true;
        this.active = false;
        try {
            if(this.activeScenario != null) {
                this.activeScenario.cleanup(this.world);
                this.activeScenario = null;
            }
            this.baseline.restore(this.world, this.manager);
            LOGGER.info("[M3A2F] RESULT phase={} result=PASS restored=true", this.phase);
        } finally {
            this.server.initiateShutdown();
        }
    }

    private void failAndShutdown(String reason, Throwable throwable) {
        if(this.finished)
            return;
        this.finished = true;
        this.active = false;
        if(throwable == null)
            LOGGER.error("[M3A2F] RESULT phase={} result=FAIL reason={}", this.phase, reason);
        else
            LOGGER.error("[M3A2F] RESULT phase={} result=FAIL reason={}", this.phase, reason, throwable);
        try {
            if(this.world != null && this.activeScenario != null)
                this.activeScenario.cleanup(this.world);
            if(this.world != null && this.manager != null && this.baseline != null)
                this.baseline.restore(this.world, this.manager);
        } finally {
            MinecraftServer current = this.server != null ? this.server
                    : FMLCommonHandler.instance().getMinecraftServerInstance();
            if(current != null)
                current.initiateShutdown();
        }
    }

    private static List<EntityPlayer> players(WorldServer world) {
        return ((World)world).playerEntities;
    }

    private record Scenario(String id, boolean daylightCycle, boolean weatherCycle, double multiplier) {
    }

    private static final class ActiveScenario {
        private final Scenario scenario;
        private final BedFixture bed;
        private final ProbePlayer player;
        private final long startTime;
        private final boolean rainingAtStart;
        private long afterStartTime;
        private long afterEndTime;
        private boolean sleepingAfterStart;
        private boolean sleepingAfterEnd;
        private boolean rainingAfterStart;
        private boolean rainingAfterEnd;

        private ActiveScenario(Scenario scenario, BedFixture bed, ProbePlayer player,
                long startTime, boolean rainingAtStart) {
            this.scenario = scenario;
            this.bed = bed;
            this.player = player;
            this.startTime = startTime;
            this.rainingAtStart = rainingAtStart;
        }

        private static ActiveScenario prepare(WorldServer world, StellarManager manager, Scenario scenario) {
            if(!players(world).isEmpty())
                throw new IllegalStateException("player list not empty before scenario");
            manager.setSystemTimeSyncEnabled(0, false);
            manager.setTimeMultiplier(0, scenario.multiplier);
            world.getGameRules().setOrCreateGameRule("doDaylightCycle", Boolean.toString(scenario.daylightCycle));
            world.getGameRules().setOrCreateGameRule("doWeatherCycle", Boolean.toString(scenario.weatherCycle));
            world.setWorldTime(14000L);
            world.setSkylightSubtracted(world.calculateSkylightSubtracted(1.0f));
            WorldInfo info = world.getWorldInfo();
            info.setRaining(true);
            info.setThundering(true);
            info.setRainTime(6000);
            info.setThunderTime(6000);
            BedFixture bed = BedFixture.place(world);
            ProbePlayer player = new ProbePlayer(world, "scenario_" + scenario.id, true, true, false);
            player.bedLocation = bed.position;
            player.setPosition(bed.position.getX() + 0.5, bed.position.getY() + 0.6875,
                    bed.position.getZ() + 0.5);
            players(world).add(player);
            world.updateAllPlayersSleepingFlag();
            if(!world.areAllPlayersAsleep())
                throw new IllegalStateException("scenario did not establish quorum");
            return new ActiveScenario(scenario, bed, player, world.getWorldTime(), info.isRaining());
        }

        private void captureAfterStart(WorldServer world) {
            this.afterStartTime = world.getWorldTime();
            this.sleepingAfterStart = this.player.isPlayerSleeping();
            this.rainingAfterStart = world.getWorldInfo().isRaining();
        }

        private void captureAfterEnd(WorldServer world) {
            this.afterEndTime = world.getWorldTime();
            this.sleepingAfterEnd = this.player.isPlayerSleeping();
            this.rainingAfterEnd = world.getWorldInfo().isRaining();
        }

        private boolean spawnCommitted() {
            return this.bed.position.equals(this.player.getBedLocation());
        }

        private boolean validate(String phase) {
            boolean sapi = "sapi".equals(phase);
            boolean startWakeTiming = sapi ? !this.sleepingAfterStart : this.sleepingAfterStart;
            boolean endWake = !this.sleepingAfterEnd && this.player.wakeCalls == 1;
            boolean timeBehavior;
            if(!this.scenario.daylightCycle)
                timeBehavior = this.afterStartTime == this.startTime && this.afterEndTime == this.startTime;
            else if(sapi)
                timeBehavior = this.afterStartTime != this.startTime;
            else
                timeBehavior = this.afterStartTime == this.startTime
                        && this.afterEndTime >= 23999L && this.afterEndTime <= 24001L;
            boolean weatherBehavior = this.scenario.weatherCycle
                    ? !this.rainingAfterEnd : this.rainingAfterEnd;
            return startWakeTiming && endWake && timeBehavior && weatherBehavior && spawnCommitted();
        }

        private void cleanup(WorldServer world) {
            players(world).remove(this.player);
            world.updateAllPlayersSleepingFlag();
            this.bed.restore(world);
        }
    }

    private static final class ProbePlayer extends EntityPlayer {
        private boolean controlledSleeping;
        private boolean controlledFullyAsleep;
        private final boolean spectator;
        private int wakeCalls;
        private String lastMessage;

        private ProbePlayer(WorldServer world, String name, boolean sleeping, boolean fullyAsleep,
                boolean spectator) {
            super(world, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
            this.controlledSleeping = sleeping;
            this.controlledFullyAsleep = fullyAsleep;
            this.spectator = spectator;
        }

        @Override
        public boolean isPlayerSleeping() {
            return this.controlledSleeping || super.isPlayerSleeping();
        }

        @Override
        public boolean isPlayerFullyAsleep() {
            return this.controlledSleeping && this.controlledFullyAsleep;
        }

        @Override
        public void wakeUpPlayer(boolean immediately, boolean updateWorldFlag, boolean setSpawn) {
            this.wakeCalls++;
            this.controlledSleeping = false;
            this.controlledFullyAsleep = false;
            super.wakeUpPlayer(immediately, updateWorldFlag, setSpawn);
        }

        @Override
        public boolean isSpectator() {
            return this.spectator;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        @Override
        public void sendStatusMessage(ITextComponent component, boolean actionBar) {
            this.lastMessage = component.getUnformattedComponentText();
        }
    }

    private static final class BedFixture {
        private final BlockPos position;
        private final IBlockState original;

        private BedFixture(BlockPos position, IBlockState original) {
            this.position = position;
            this.original = original;
        }

        private static BedFixture place(WorldServer world) {
            BlockPos column = world.getSpawnPoint().add(10, 0, 10);
            BlockPos position = world.getTopSolidOrLiquidBlock(column).up();
            IBlockState original = world.getBlockState(position);
            IBlockState bed = Blocks.BED.getDefaultState()
                    .withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD)
                    .withProperty(BlockBed.FACING, EnumFacing.NORTH)
                    .withProperty(BlockBed.OCCUPIED, true);
            if(!world.setBlockState(position, bed, 3))
                throw new IllegalStateException("unable to place diagnostic bed at " + position);
            return new BedFixture(position, original);
        }

        private void restore(WorldServer world) {
            if(!world.setBlockState(this.position, this.original, 3))
                throw new IllegalStateException("unable to restore block at " + this.position);
        }
    }

    private static final class Baseline {
        private final long worldTime;
        private final int skylightSubtracted;
        private final boolean daylightCycle;
        private final boolean weatherCycle;
        private final boolean raining;
        private final boolean thundering;
        private final int rainTime;
        private final int thunderTime;
        private final double multiplier;
        private final boolean systemSync;

        private Baseline(long worldTime, int skylightSubtracted, boolean daylightCycle,
                boolean weatherCycle, boolean raining, boolean thundering, int rainTime,
                int thunderTime, double multiplier, boolean systemSync) {
            this.worldTime = worldTime;
            this.skylightSubtracted = skylightSubtracted;
            this.daylightCycle = daylightCycle;
            this.weatherCycle = weatherCycle;
            this.raining = raining;
            this.thundering = thundering;
            this.rainTime = rainTime;
            this.thunderTime = thunderTime;
            this.multiplier = multiplier;
            this.systemSync = systemSync;
        }

        private static Baseline capture(WorldServer world, StellarManager manager) {
            WorldInfo info = world.getWorldInfo();
            GameRules rules = world.getGameRules();
            return new Baseline(world.getWorldTime(), world.getSkylightSubtracted(),
                    rules.getBoolean("doDaylightCycle"), rules.getBoolean("doWeatherCycle"),
                    info.isRaining(), info.isThundering(), info.getRainTime(), info.getThunderTime(),
                    manager.getTimeMultiplier(0), manager.isSystemTimeSyncEnabled(0));
        }

        private void restore(WorldServer world, StellarManager manager) {
            world.getGameRules().setOrCreateGameRule("doDaylightCycle", Boolean.toString(this.daylightCycle));
            world.getGameRules().setOrCreateGameRule("doWeatherCycle", Boolean.toString(this.weatherCycle));
            world.setWorldTime(this.worldTime);
            world.setSkylightSubtracted(this.skylightSubtracted);
            WorldInfo info = world.getWorldInfo();
            info.setRaining(this.raining);
            info.setThundering(this.thundering);
            info.setRainTime(this.rainTime);
            info.setThunderTime(this.thunderTime);
            manager.setTimeMultiplier(0, this.multiplier);
            manager.setSystemTimeSyncEnabled(0, this.systemSync);
        }
    }
}
