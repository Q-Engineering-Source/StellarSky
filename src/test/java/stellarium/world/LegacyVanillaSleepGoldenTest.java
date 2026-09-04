package stellarium.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.base.Predicate;
import com.mojang.authlib.GameProfile;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayer.SleepResult;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;

public class LegacyVanillaSleepGoldenTest {
    private static final String FIXTURE = "/stellarium/legacy-golden/2f/vanilla-sleep.tsv";
    private static final BlockPos BED_POS = new BlockPos(8, 64, 8);

    @BeforeClass
    public static void bootstrapVanillaRegistries() {
        Bootstrap.register();
    }

    @Test
    public void returnsLegacyEligibilityResultsThroughTrySleep() throws Exception {
        for(EligibilityRow row : loadEligibilityRows()) {
            SleepWorld world = new SleepWorld(row.daytime(), row.surface());
            world.hostileNearby = row.hostile();
            ProbePlayer player = new ProbePlayer(world, row.id());
            player.setPosition(BED_POS.getX() + row.distanceX(), BED_POS.getY(), BED_POS.getZ());

            if("sleeping".equals(row.precondition()))
                assertEquals(SleepResult.OK, player.trySleep(BED_POS));
            else if("dead".equals(row.precondition()))
                player.setDead();

            assertEquals(row.id(), SleepResult.valueOf(row.expected()), player.trySleep(BED_POS));
        }
    }

    @Test
    public void becomesFullyAsleepAtExactlyOneHundredSleepTicks() {
        SleepWorld world = new SleepWorld(false, true);
        ProbePlayer player = new ProbePlayer(world, "sleep_timer");
        player.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());

        assertEquals(SleepResult.OK, player.trySleep(BED_POS));
        assertEquals(0, player.getSleepTimer());
        assertFalse(player.isPlayerFullyAsleep());

        for(int tick = 0; tick < 99; tick++)
            player.onUpdate();

        assertEquals(99, player.getSleepTimer());
        assertFalse(player.isPlayerFullyAsleep());
        player.onUpdate();
        assertEquals(100, player.getSleepTimer());
        assertTrue(player.isPlayerFullyAsleep());
    }

    @Test
    public void naturalDaylightWakeCommitsSpawnAndUsesTheTenTickExitTimer() {
        SleepWorld world = new SleepWorld(false, true);
        ProbePlayer player = new ProbePlayer(world, "natural_wake");
        player.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertEquals(SleepResult.OK, player.trySleep(BED_POS));

        world.daytime = true;
        player.onUpdate();

        assertFalse(player.isPlayerSleeping());
        assertEquals(100, player.getSleepTimer());
        assertEquals(BED_POS, player.getBedLocation());
        for(int tick = 0; tick < 10; tick++)
            player.onUpdate();
        assertEquals(0, player.getSleepTimer());
    }

    @Test
    public void invalidBedWakesImmediatelyWithoutCommittingSpawn() {
        SleepWorld world = new SleepWorld(false, true);
        ProbePlayer player = new ProbePlayer(world, "invalid_bed");
        player.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertEquals(SleepResult.OK, player.trySleep(BED_POS));

        world.states.remove(BED_POS);
        player.onUpdate();

        assertFalse(player.isPlayerSleeping());
        assertEquals(0, player.getSleepTimer());
        assertNull(player.getBedLocation());
    }

    @Test
    public void externalWakeFlagsControlExitTimerAndSpawnCommitIndependently() {
        SleepWorld world = new SleepWorld(false, true);
        ProbePlayer noSpawn = new ProbePlayer(world, "external_no_spawn");
        noSpawn.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertEquals(SleepResult.OK, noSpawn.trySleep(BED_POS));
        noSpawn.wakeUpPlayer(false, true, false);
        assertEquals(100, noSpawn.getSleepTimer());
        assertNull(noSpawn.getBedLocation());

        ProbePlayer immediateSpawn = new ProbePlayer(world, "external_immediate_spawn");
        immediateSpawn.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertEquals(SleepResult.OK, immediateSpawn.trySleep(BED_POS));
        immediateSpawn.wakeUpPlayer(true, true, true);
        assertEquals(0, immediateSpawn.getSleepTimer());
        assertEquals(BED_POS, immediateSpawn.getBedLocation());
    }

    @Test
    public void occupiedBedBlocksASecondSleeperButStaleOccupiedFlagIsCleared() {
        SleepWorld world = new SleepWorld(false, true);
        ProbePlayer first = new ProbePlayer(world, "first");
        first.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertEquals(SleepResult.OK, first.trySleep(BED_POS));
        world.playerEntities.add(first);
        world.setOccupied(true);

        ProbePlayer second = new ProbePlayer(world, "second");
        second.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertTrue(Blocks.BED.onBlockActivated(world, BED_POS, world.getBlockState(BED_POS), second,
                EnumHand.MAIN_HAND, EnumFacing.UP, 0.0f, 0.0f, 0.0f));
        assertFalse(second.isPlayerSleeping());
        assertEquals("tile.bed.occupied", second.lastMessageKey());

        world.playerEntities.clear();
        ProbePlayer staleFlagSleeper = new ProbePlayer(world, "stale_flag");
        staleFlagSleeper.setPosition(BED_POS.getX(), BED_POS.getY(), BED_POS.getZ());
        assertTrue(Blocks.BED.onBlockActivated(world, BED_POS, world.getBlockState(BED_POS), staleFlagSleeper,
                EnumHand.MAIN_HAND, EnumFacing.UP, 0.0f, 0.0f, 0.0f));
        assertTrue(staleFlagSleeper.isPlayerSleeping());
        assertTrue(world.getBlockState(BED_POS).getValue(BlockBed.OCCUPIED));
    }

    private static List<EligibilityRow> loadEligibilityRows() throws Exception {
        InputStream stream = LegacyVanillaSleepGoldenTest.class.getResourceAsStream(FIXTURE);
        if(stream == null)
            throw new AssertionError("Missing " + FIXTURE);

        List<EligibilityRow> rows = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                if(line.isBlank() || line.startsWith("#") || line.startsWith("id\t"))
                    continue;
                String[] fields = line.split("\t", -1);
                assertEquals("Invalid row: " + line, 8, fields.length);
                rows.add(new EligibilityRow(fields[0], Boolean.parseBoolean(fields[1]),
                        Boolean.parseBoolean(fields[2]), Integer.parseInt(fields[3]),
                        Boolean.parseBoolean(fields[4]), fields[5], fields[6], fields[7]));
            }
        }
        return rows;
    }

    private record EligibilityRow(String id, boolean daytime, boolean surface, int distanceX,
            boolean hostile, String precondition, String expected, String classification) {
    }

    private static final class ProbePlayer extends EntityPlayer {
        private ITextComponent lastMessage;

        private ProbePlayer(World world, String name) {
            super(world, new GameProfile(java.util.UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        @Override
        public void onLivingUpdate() {
            // The seam under test is EntityPlayer.onUpdate's sleep state prefix.
            // Entity movement/collision needs a real chunk provider and is unrelated here.
        }

        @Override
        public void sendStatusMessage(ITextComponent component, boolean actionBar) {
            this.lastMessage = component;
        }

        private String lastMessageKey() {
            return this.lastMessage instanceof TextComponentTranslation translation
                    ? translation.getKey() : null;
        }
    }

    private static final class SleepWorld extends World {
        private final Map<BlockPos, IBlockState> states = new HashMap<>();
        private boolean daytime;
        private boolean hostileNearby;
        private int sleepingFlagUpdates;

        private SleepWorld(boolean daytime, boolean surface) {
            super(null,
                    new WorldInfo(new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                            "m3a2-vanilla-sleep-golden"),
                    new SleepWorldProvider(surface), new Profiler(), false);
            this.daytime = daytime;
            this.states.put(BED_POS, Blocks.BED.getDefaultState()
                    .withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD)
                    .withProperty(BlockBed.FACING, EnumFacing.NORTH)
                    .withProperty(BlockBed.OCCUPIED, false));
        }

        private void setOccupied(boolean occupied) {
            this.states.put(BED_POS, this.states.get(BED_POS).withProperty(BlockBed.OCCUPIED, occupied));
        }

        @Override
        public boolean isDaytime() {
            return this.daytime;
        }

        @Override
        public BlockPos getSpawnPoint() {
            return BlockPos.ORIGIN;
        }

        @Override
        public boolean isBlockLoaded(BlockPos pos) {
            return true;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return this.states.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }

        @Override
        public boolean setBlockState(BlockPos pos, IBlockState newState, int flags) {
            this.states.put(pos, newState);
            return true;
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return Biomes.PLAINS;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Entity> List<T> getEntitiesWithinAABB(Class<? extends T> entityClass,
                AxisAlignedBB aabb, Predicate<? super T> filter) {
            if(this.hostileNearby && EntityMob.class.isAssignableFrom(entityClass))
                return (List<T>)(List<?>)List.of(new EntityZombie(this));
            return List.of();
        }

        @Override
        public void updateAllPlayersSleepingFlag() {
            this.sleepingFlagUpdates++;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return true;
        }
    }

    private static final class SleepWorldProvider extends WorldProvider {
        private final boolean surface;

        private SleepWorldProvider(boolean surface) {
            this.surface = surface;
        }

        @Override
        public boolean isSurfaceWorld() {
            return this.surface;
        }

        @Override
        public boolean canRespawnHere() {
            return true;
        }

        @Override
        public WorldSleepResult canSleepAt(EntityPlayer player, BlockPos pos) {
            return WorldSleepResult.ALLOW;
        }

        @Override
        public DimensionType getDimensionType() {
            return DimensionType.OVERWORLD;
        }
    }
}
