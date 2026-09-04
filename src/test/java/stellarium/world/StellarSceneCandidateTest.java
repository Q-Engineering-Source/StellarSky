package stellarium.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.config.Configuration;
import stellarapi.api.world.worldset.WorldSet;
import stellarapi.api.world.worldset.WorldSets;
import stellarapi.reference.WorldSetReference;
import stellarium.CommonProxy;
import stellarium.IProxy;
import stellarium.StellarSky;
import stellarium.api.SkyRenderTypeSurface;
import stellarium.api.SkySetTypeDefault;
import stellarium.api.StellarSkyAPI;
import stellarium.stellars.StellarManager;
import stellarium.stellars.layer.CelestialManager;
import stellarium.stellars.layer.StellarCollection;

/** Regression coverage for rejected remote scene candidates mutating the active manager. */
public class StellarSceneCandidateTest {
    private static final double OLD_DAY_LENGTH = 24_000.0;
    private static final double INCOMING_DAY_LENGTH = 1_728_000.0;

    private StellarSky originalInstance;
    private IProxy originalProxy;

    @Before
    public void saveGlobalStellarSkyState() {
        originalInstance = StellarSky.INSTANCE;
        originalProxy = StellarSky.PROXY;
    }

    @After
    public void restoreGlobalStellarSkyState() {
        StellarSky.INSTANCE = originalInstance;
        StellarSky.PROXY = originalProxy;
    }

    @Test
    public void rejectedRemoteCandidateKeepsExistingManagerMetadata() {
        installHeadlessReferences();
        RemoteWorld world = new RemoteWorld(0);
        StellarManager manager = StellarManager.loadOrCreateManager(world);
        manager.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        manager.setTimeMultiplier(0, 2.0);

        WorldSet overworld = WorldSets.exactOverworld();
        StellarScene existingScene = new StellarScene(world, overworld, new PerDimensionSettings(overworld));
        NBTTagCompound serializedBefore = existingScene.serializeNBT();
        double skyYearBefore = manager.getSkyYear(12_345.0);

        NBTTagCompound rejectedCandidate = new NBTTagCompound();
        rejectedCandidate.setTag("main", managerDataWithDay(INCOMING_DAY_LENGTH));
        NBTTagCompound invalidRingworld = new NBTTagCompound();
        invalidRingworld.setInteger("schemaVersion", 17);
        rejectedCandidate.setTag("ringworld", invalidRingworld);

        StellarScene candidate = new StellarScene(world, overworld, new PerDimensionSettings(overworld));
        try {
            candidate.deserializeNBT(rejectedCandidate);
            fail("The malformed ringworld settings must reject this candidate");
        } catch(IllegalArgumentException expected) {
            assertEquals("Unknown ringworld settings schema version", expected.getMessage());
        }

        assertEquals("Rejected candidate must not replace the active manager day length",
                OLD_DAY_LENGTH, manager.getSettings().day, 0.0);
        assertEquals("Rejected candidate must not erase active dimension time state",
                2.0, manager.getTimeMultiplier(0), 0.0);
        assertEquals("Rejected candidate must not change active astronomical metadata",
                skyYearBefore, manager.getSkyYear(12_345.0), 0.0);
        assertEquals("Existing scene serialization must retain the active manager metadata",
                serializedBefore.getCompoundTag("main").getDouble("day"),
                existingScene.serializeNBT().getCompoundTag("main").getDouble("day"), 0.0);
        assertTrue("Existing scene serialization must retain dimension time state",
                existingScene.serializeNBT().getCompoundTag("main")
                        .getCompoundTag("DimensionTimeStates").hasKey("0", 10));
    }

    @Test
    public void rejectedRemoteCandidatePrepareKeepsExistingManagerMetadata() {
        installHeadlessReferences();
        RemoteWorld world = new RemoteWorld(0);
        StellarManager manager = StellarManager.loadOrCreateManager(world);
        manager.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        manager.setTimeMultiplier(0, 2.0);
        double skyYearBefore = manager.getSkyYear(12_345.0);

        WorldSet overworld = WorldSets.exactOverworld();
        StellarScene candidate = new StellarScene(world, overworld, new PerDimensionSettings(overworld));
        candidate.deserializeNBT(ringworldCandidateData(overworld));
        try {
            candidate.prepare();
            fail("A ringworld candidate without Patch_Provider must not prepare");
        } catch(IllegalArgumentException expected) {
            assertEquals("Ringworld requires Patch_Provider and a world with skylight", expected.getMessage());
        }

        assertManagerMetadata(manager, skyYearBefore);
    }

    @Test
    public void adoptsOnlyPreparedClientCandidateStateAndRebindsItsActualGraph() {
        installHeadlessReferences();
        RemoteWorld world = new RemoteWorld(0);
        StellarManager manager = StellarManager.loadOrCreateManager(world);
        manager.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        manager.setTimeMultiplier(0, 2.0);
        manager.setDirty(false);
        manager.markDirty();

        StellarManager candidate = manager.createClientCandidate();
        candidate.syncFromNBT(managerDataWithDay(INCOMING_DAY_LENGTH), true);
        candidate.setTimeMultiplier(0, 3.0);
        CelestialManager prepared = new CelestialManager(true);
        prepared.initializeCommon(candidate, candidate.getSettings());

        manager.adoptPreparedClientState(world, candidate, prepared);

        assertTrue("Client adoption keeps the saved-data dirty bit", manager.isDirty());
        assertTrue("Client adoption copies locked state", manager.isLocked());
        assertEquals(INCOMING_DAY_LENGTH, manager.getSettings().day, 0.0);
        assertEquals(3.0, manager.getTimeMultiplier(0), 0.0);
        assertSame("Prepared graph becomes the manager's published graph", prepared, manager.getCelestialManager());
        assertFalse("The real prepared graph must contain collections", prepared.getLayers().isEmpty());
        for(StellarCollection<?> collection : prepared.getLayers()) {
            assertSame("Adoption must rebind every published collection to committed authority",
                    manager, collection.getManager());
        }

        candidate.setTimeMultiplier(0, 7.0);
        candidate.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        assertEquals("Post-adoption candidate mutation must not alter committed time state",
                3.0, manager.getTimeMultiplier(0), 0.0);
        assertEquals("Post-adoption candidate mutation must not alter committed metadata",
                INCOMING_DAY_LENGTH, manager.getSettings().day, 0.0);
    }

    @Test
    public void rejectsGraphPreparedFromAnotherCandidateOfTheSameManager() {
        installHeadlessReferences();
        RemoteWorld world = new RemoteWorld(0);
        StellarManager manager = StellarManager.loadOrCreateManager(world);
        manager.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        NBTTagCompound before = manager.serializeNBT();
        StellarManager candidateA = manager.createClientCandidate();
        StellarManager candidateB = manager.createClientCandidate();
        candidateB.syncFromNBT(managerDataWithDay(INCOMING_DAY_LENGTH), true);
        CelestialManager graphFromA = new CelestialManager(true);
        graphFromA.initializeCommon(candidateA, candidateA.getSettings());

        try {
            manager.adoptPreparedClientState(world, candidateB, graphFromA);
            fail("Metadata and graph must belong to the same candidate, not merely the same source manager");
        } catch(IllegalArgumentException expected) {
            assertEquals("Prepared graph belongs to another client metadata candidate", expected.getMessage());
        }
        assertEquals("Rejected mixed-candidate adoption must not change committed metadata",
                before, manager.serializeNBT());
        for(StellarCollection<?> collection : graphFromA.getLayers()) {
            assertSame("Rejection must happen before rebinding the foreign graph", candidateA, collection.getManager());
        }
    }

    @Test
    public void rejectsWrongSourceAndUnpreparedGraphWithoutChangingExistingManagerMetadata() {
        installHeadlessReferences();
        RemoteWorld world = new RemoteWorld(0);
        StellarManager manager = StellarManager.loadOrCreateManager(world);
        manager.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        manager.setTimeMultiplier(0, 2.0);
        double skyYearBefore = manager.getSkyYear(12_345.0);

        StellarManager unrelated = new StellarManager("unrelated");
        unrelated.syncFromNBT(managerDataWithDay(OLD_DAY_LENGTH), true);
        StellarManager wrongSourceCandidate = unrelated.createClientCandidate();
        CelestialManager prepared = new CelestialManager(true);
        prepared.initializeCommon(manager.createClientCandidate(), manager.getSettings());
        try {
            manager.adoptPreparedClientState(world, wrongSourceCandidate, prepared);
            fail("A candidate from another manager must be rejected");
        } catch(IllegalArgumentException expected) {
            assertEquals("Client metadata candidate belongs to another manager", expected.getMessage());
        }
        assertManagerMetadata(manager, skyYearBefore);

        try {
            manager.adoptPreparedClientState(world, manager.createClientCandidate(), new CelestialManager(true));
            fail("An unprepared graph must be rejected");
        } catch(IllegalStateException expected) {
            assertEquals("A prepared client graph must belong to its active world manager", expected.getMessage());
        }
        assertManagerMetadata(manager, skyYearBefore);
    }

    private static void installHeadlessReferences() {
        StellarSky.INSTANCE = new TestStellarSky();
        StellarSky.PROXY = new TestCommonProxy();

        WorldSetReference reference = new WorldSetReference();
        reference.initialize();
        Configuration configuration = new Configuration();
        reference.setupConfig(configuration, "worldsets");
        reference.loadFromConfig(configuration, "worldsets");
        WorldSets.putReference(reference);

        WorldSet overworld = WorldSets.exactOverworld();
        StellarSkyAPI.registerSkyType(overworld, SkySetTypeDefault.INSTANCE);
        StellarSkyAPI.registerDefaultRenderer(overworld, SkyRenderTypeSurface.INSTANCE);
        // WorldSets exposes no getter for its global reference. This fixture installs a
        // fresh real reference per test but cannot restore an arbitrary previous one
        // without reflection. clientSceneCandidateTest runs this entire class in its
        // own JVM; the standard test task excludes it and depends on that isolated task.
    }

    private static NBTTagCompound ringworldCandidateData(WorldSet overworld) {
        NBTTagCompound data = new NBTTagCompound();
        data.setTag("main", managerDataWithDay(INCOMING_DAY_LENGTH));
        PerDimensionSettings settings = new PerDimensionSettings(overworld);
        settings.writeToNBT(data);
        data.setBoolean("patchProvider", false);
        data.getCompoundTag("ringworld").setByte("enabled", (byte) 1);
        return data;
    }

    private static void assertManagerMetadata(StellarManager manager, double expectedSkyYear) {
        assertEquals("Rejected candidate must not replace the active manager day length",
                OLD_DAY_LENGTH, manager.getSettings().day, 0.0);
        assertEquals("Rejected candidate must not erase active dimension time state",
                2.0, manager.getTimeMultiplier(0), 0.0);
        assertEquals("Rejected candidate must not change active astronomical metadata",
                expectedSkyYear, manager.getSkyYear(12_345.0), 0.0);
    }

    private static NBTTagCompound managerDataWithDay(double dayLength) {
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("locked", true);
        data.setDouble("day", dayLength);
        return data;
    }

    private static final class TestStellarSky extends StellarSky {
        @Override
        public Logger getLogger() {
            return LogManager.getLogger(StellarSceneCandidateTest.class);
        }
    }

    private static final class TestCommonProxy extends CommonProxy {
        @Override
        public CelestialManager getClientCelestialManager() {
            return new CelestialManager(true);
        }
    }

    private static final class RemoteWorld extends World {
        private RemoteWorld(int dimension) {
            super(null,
                    new WorldInfo(new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                            "candidate-regression"),
                    new RemoteWorldProvider(dimension),
                    new Profiler(),
                    true);
            this.mapStorage = new MapStorage(null);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return false;
        }
    }

    private static final class RemoteWorldProvider extends WorldProvider {
        private RemoteWorldProvider(int dimension) {
            this.setDimension(dimension);
        }

        @Override
        public DimensionType getDimensionType() {
            return DimensionType.OVERWORLD;
        }
    }
}
