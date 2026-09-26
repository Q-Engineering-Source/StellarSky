package stellarium.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
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
import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.optics.Wavelength;
import stellarapi.api.view.IAtmosphereEffect;
import stellarapi.api.world.worldset.WorldSet;
import stellarapi.api.world.worldset.WorldSets;
import stellarapi.reference.WorldSetReference;
import stellarium.CommonProxy;
import stellarium.IProxy;
import stellarium.StellarSky;
import stellarium.api.SkyRenderTypeSurface;
import stellarium.api.SkySetTypeDefault;
import stellarium.api.StellarSkyAPI;
import stellarium.common.ServerSettings;
import stellarium.display.DisplayCacheInfo;
import stellarium.stellars.StellarManager;
import stellarium.stellars.layer.CelestialManager;
import stellarium.stellars.layer.StellarCollection;
import stellarium.world.ring.RingworldDisplayGeometry;
import stellarium.world.ring.RingworldRenderObserver;

/** Regression coverage for rejected remote scene candidates mutating the active manager. */
public class StellarSceneCandidateTest {
    private static final double OLD_DAY_LENGTH = 24_000.0;
    private static final double INCOMING_DAY_LENGTH = 1_728_000.0;
    private static final double EPSILON = 1.0e-9;
    private static final Matrix3 RING_BACKGROUND_Q = new Matrix3(
            1.0, 0.0, 0.0,
            0.0, 0.0, 1.0,
            0.0, -1.0, 0.0);
    private static final Matrix3 SKY_RENDERER_GROUND_TO_WORLD = new Matrix3(
            -1.0, 0.0, 0.0,
            0.0, 0.0, 1.0,
            0.0, 1.0, 0.0);
    private static final IAtmosphereEffect NO_ATMOSPHERE = new IAtmosphereEffect() {
        @Override
        public void applyAtmRefraction(SpCoord pos) { }

        @Override
        public void disapplyAtmRefraction(SpCoord pos) { }

        @Override
        public float calculateAirmass(SpCoord pos) {
            return 1.0f;
        }

        @Override
        public float getExtinctionRate(Wavelength wavelength) {
            return 0.0f;
        }

        @Override
        public double getSeeing(Wavelength wavelength) {
            return 0.0;
        }

        @Override
        public float getAbsorptionFactor(float partialTicks) {
            return 0.0f;
        }

        @Override
        public float getDispersionFactor(Wavelength wavelength, float partialTicks) {
            return 0.0f;
        }

        @Override
        public float getLightPollutionFactor(Wavelength wavelength, float partialTicks) {
            return 0.0f;
        }

        @Override
        public float minimumSkyRenderBrightness() {
            return 0.0f;
        }
    };

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

    @Test
    public void ringworldBackgroundMapsTheEquatorialPoleToWorldZAcrossLocationsAndTimes() {
        for(double latitude : new double[] {-67.5, 0.0, 41.25}) {
            StellarCoordinates coordinate = newCoordinates(latitude, true);
            for(double year : new double[] {-0.375, 0.0, 0.125, 0.625}) {
                coordinate.update(year);

                Matrix3 background = StellarCoordinates.backgroundProjection(coordinate);
                Matrix3 expected = expectedRingBackground(coordinate, latitude);
                assertMatrixEquals("ring background formula at latitude " + latitude
                        + " and year " + year, expected, background);

                Vector3 groundPole = background.transform(new Vector3(0.0, 0.0, 1.0));
                assertVectorEquals("ring equatorial pole must be ground +N", new Vector3(0.0, 1.0, 0.0),
                        groundPole);
                Vector3 worldPole = SKY_RENDERER_GROUND_TO_WORLD.transform(groundPole);
                assertVectorEquals("SkyRenderer converts ring pole to world +Z", new Vector3(0.0, 0.0, 1.0),
                        worldPole);
                assertProperRotation(background);
            }
        }
    }

    @Test
    public void ringworldBackgroundQuarterCycleAndReverseTimeRotateOnlyAroundWorldZ() {
        StellarCoordinates coordinate = newCoordinates(37.0, true);
        double quarterRotation = 1.0 / (4.0 * (365.25 + 1.0));

        coordinate.update(0.0);
        Matrix3 before = worldProjection(StellarCoordinates.backgroundProjection(coordinate));
        coordinate.update(quarterRotation);
        Matrix3 forward = worldProjection(StellarCoordinates.backgroundProjection(coordinate));
        coordinate.update(-quarterRotation);
        Matrix3 reverse = worldProjection(StellarCoordinates.backgroundProjection(coordinate));

        Matrix3 forwardDelta = new Matrix3(forward).postMult(new Matrix3(before).transpose());
        Matrix3 reverseDelta = new Matrix3(reverse).postMult(new Matrix3(before).transpose());
        assertWorldZRotation("quarter-cycle forward delta", forwardDelta);
        assertWorldZRotation("quarter-cycle reverse delta", reverseDelta);
        assertVectorEquals("forward time must carry world +Y toward +X around the +Z axis",
                new Vector3(1.0, 0.0, 0.0), forwardDelta.transform(new Vector3(0.0, 1.0, 0.0)));
        assertVectorEquals("reverse time must carry world +Y toward -X around the +Z axis",
                new Vector3(-1.0, 0.0, 0.0), reverseDelta.transform(new Vector3(0.0, 1.0, 0.0)));
        assertVectorEquals("forward and reverse time deltas must cancel",
                new Vector3(0.0, 1.0, 0.0),
                new Matrix3(forwardDelta).postMult(reverseDelta)
                        .transform(new Vector3(0.0, 1.0, 0.0)));
        assertTrue("quarter-cycle must be a real quarter turn rather than a frozen background",
                Math.abs(forwardDelta.transform(new Vector3(0.0, 1.0, 0.0)).getX()) > 1.0 - EPSILON);
    }

    @Test
    public void ringworldBackgroundQuarterCycleStaysOnWorldZWithAxialTiltAndLongitude() {
        StellarCoordinates coordinate = newCoordinates(53.0, true, 23.439, 127.5);
        double quarterRotation = 1.0 / (4.0 * (365.25 + 1.0));

        coordinate.update(0.31);
        Matrix3 before = worldProjection(StellarCoordinates.backgroundProjection(coordinate));
        coordinate.update(0.31 + quarterRotation);
        Matrix3 after = worldProjection(StellarCoordinates.backgroundProjection(coordinate));

        Matrix3 delta = new Matrix3(after).postMult(new Matrix3(before).transpose());
        assertWorldZRotation("tilted, non-zero-longitude quarter-cycle delta", delta);
        assertVectorEquals("tilt and longitude preserve the requested relative Z rotation direction",
                new Vector3(1.0, 0.0, 0.0), delta.transform(new Vector3(0.0, 1.0, 0.0)));
        assertTrue("axial tilt must not turn the ring background's quarter-cycle into a smaller rotation",
                Math.abs(delta.transform(new Vector3(0.0, 1.0, 0.0)).getX()) > 1.0 - EPSILON);
    }

    @Test
    public void ringworldBackgroundTurnsOppositeToPositiveXTravelAroundTheDisplayedRing() {
        StellarCoordinates coordinate = newCoordinates(0.0, true);
        coordinate.update(0.0);
        Matrix3 before = worldProjection(StellarCoordinates.backgroundProjection(coordinate));
        coordinate.update(1.0 / (4.0 * (365.25 + 1.0)));
        Matrix3 delta = worldProjection(StellarCoordinates.backgroundProjection(coordinate))
                .postMult(new Matrix3(before).transpose());
        Vector3 turnedBackground = delta.transform(new Vector3(0.0, -1.0, 0.0));

        double radius = RingworldDisplayGeometry.CURVATURE_ACCEPTANCE_RADIUS_METERS;
        var ring = new RingworldDisplayGeometry(radius);
        var quarterPoint = ring.cameraRelative(new RingworldRenderObserver(0.0, 0.0, 0.0),
                new RingworldDisplayGeometry.Point(radius * Math.PI / 2.0, 0.0, 0.0));
        Vector3 ringRadial = new Vector3(quarterPoint.x() / radius, (quarterPoint.y() - radius) / radius,
                quarterPoint.z() / radius);
        assertVectorEquals("positive ring X travel advances the bottom radius toward world +X",
                new Vector3(1.0, 0.0, 0.0), ringRadial);
        assertEquals("background and ring must take opposite quarter turns about the same Z axis",
                -1.0, dot(turnedBackground, ringRadial), EPSILON);
    }

    @Test
    public void nonRingProjectionAndPeriodStayUntouchedWhileRingBackgroundIsSeparate() {
        StellarCoordinates nonRing = newCoordinates(-18.0, false);
        StellarCoordinates ring = newCoordinates(-18.0, true);
        nonRing.update(0.42);
        ring.update(0.42);

        Matrix3 nonRingOriginal = new Matrix3(nonRing.getProjectionToGround());
        assertMatrixEquals("ring background selection must not change the physical solar projection",
                nonRingOriginal, ring.getProjectionToGround());
        Matrix3 nonRingBackground = StellarCoordinates.backgroundProjection(nonRing);
        assertMatrixEquals("non-ring fallback must preserve the physical projection", nonRingOriginal,
                nonRingBackground);
        assertMatrixEquals("non-ring fallback must not mutate the physical projection", nonRingOriginal,
                nonRing.getProjectionToGround());
        assertEquals("ring selection must not alter the celestial day period", nonRing.getPeriod().getPeriodLength(),
                ring.getPeriod().getPeriodLength(), 0.0);
        assertEquals("ring selection must not alter the celestial day phase", nonRing.getPeriod().getZerotimeOffset(),
                ring.getPeriod().getZerotimeOffset(), 0.0);
        assertNotSame("ring background must be its own matrix, not the mutable physical projection",
                ring.getProjectionToGround(), StellarCoordinates.backgroundProjection(ring));
    }

    @Test
    public void displayCacheFreezesItsRingBackgroundProjectionWithoutConstructingViewerInfo() {
        StellarCoordinates coordinate = newCoordinates(12.0, true);
        coordinate.update(0.15);
        Matrix3 sourceAtConstruction = StellarCoordinates.backgroundProjection(coordinate);
        DisplayCacheInfo display = new DisplayCacheInfo(coordinate, NO_ATMOSPHERE);

        assertNotSame("display cache must own a defensive background-projection snapshot",
                sourceAtConstruction, display.backgroundProjectionToGround);
        assertMatrixEquals("display cache background snapshot", sourceAtConstruction,
                display.backgroundProjectionToGround);
        coordinate.update(0.70);
        assertMatrixEquals("later coordinate updates must not overwrite the display snapshot",
                sourceAtConstruction, display.backgroundProjectionToGround);
        assertMatrixEquals("display's physical projection remains available for horizontal overlays",
                coordinate.getProjectionToGround(), display.projectionToGround);
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

    private static StellarCoordinates newCoordinates(double latitude, boolean ringworld) {
        return newCoordinates(latitude, ringworld, 0.0, 0.0);
    }

    private static StellarCoordinates newCoordinates(double latitude, boolean ringworld,
            double axialTiltDegrees, double longitude) {
        installHeadlessReferences();
        PerDimensionSettings settings = new PerDimensionSettings(WorldSets.exactOverworld());
        settings.latitude = latitude;
        settings.longitude = longitude;
        if(ringworld) {
            NBTTagCompound ringworldState = new NBTTagCompound();
            settings.getRingworldSettings().writeToNBT(ringworldState);
            ringworldState.setByte("enabled", (byte) 1);
            settings.getRingworldSettings().readFromNBT(ringworldState);
        }

        ServerSettings common = new ServerSettings();
        common.day = 24_000.0;
        common.year = 365.25;
        common.yearOffset = 0;
        common.dayOffset = 0;
        common.tickOffset = 0.0;
        common.propAxialTilt.setDouble(axialTiltDegrees);
        common.propPrecession.setDouble(0.0);
        return new StellarCoordinates(common, settings);
    }

    private static Matrix3 expectedRingBackground(StellarCoordinates coordinate, double latitudeDegrees) {
        return new Matrix3(RING_BACKGROUND_Q)
                .postMult(new Matrix3().setAsRotation(1.0, 0.0, 0.0,
                        Math.toRadians(latitudeDegrees) - Math.PI / 2.0))
                .postMult(coordinate.getProjectionToGround());
    }

    private static Matrix3 worldProjection(Matrix3 groundProjection) {
        return new Matrix3(SKY_RENDERER_GROUND_TO_WORLD).postMult(groundProjection);
    }

    private static void assertWorldZRotation(String message, Matrix3 rotation) {
        assertProperRotation(rotation);
        assertVectorEquals(message + " fixes world +Z", new Vector3(0.0, 0.0, 1.0),
                rotation.transform(new Vector3(0.0, 0.0, 1.0)));
        Vector3 rotatedX = rotation.transform(new Vector3(1.0, 0.0, 0.0));
        Vector3 rotatedY = rotation.transform(new Vector3(0.0, 1.0, 0.0));
        assertEquals(message + " must not rotate world X into Z", 0.0, rotatedX.getZ(), EPSILON);
        assertEquals(message + " must not rotate world Y into Z", 0.0, rotatedY.getZ(), EPSILON);
    }

    private static void assertProperRotation(Matrix3 matrix) {
        Vector3 x = matrix.transform(new Vector3(1.0, 0.0, 0.0));
        Vector3 y = matrix.transform(new Vector3(0.0, 1.0, 0.0));
        Vector3 z = matrix.transform(new Vector3(0.0, 0.0, 1.0));
        assertEquals("rotation preserves X length", 1.0, x.size(), EPSILON);
        assertEquals("rotation preserves Y length", 1.0, y.size(), EPSILON);
        assertEquals("rotation preserves Z length", 1.0, z.size(), EPSILON);
        assertEquals("rotation keeps X/Y orthogonal", 0.0, dot(x, y), EPSILON);
        assertEquals("rotation keeps X/Z orthogonal", 0.0, dot(x, z), EPSILON);
        assertEquals("rotation keeps Y/Z orthogonal", 0.0, dot(y, z), EPSILON);
        assertEquals("rotation is proper", 1.0, determinant(matrix), EPSILON);
    }

    private static void assertMatrixEquals(String message, Matrix3 expected, Matrix3 actual) {
        for(int row = 0; row < 3; row++) {
            for(int column = 0; column < 3; column++) {
                assertEquals(message + "[" + row + "," + column + "]",
                        expected.getElement(row, column), actual.getElement(row, column), EPSILON);
            }
        }
    }

    private static void assertVectorEquals(String message, Vector3 expected, Vector3 actual) {
        assertEquals(message + " x", expected.getX(), actual.getX(), EPSILON);
        assertEquals(message + " y", expected.getY(), actual.getY(), EPSILON);
        assertEquals(message + " z", expected.getZ(), actual.getZ(), EPSILON);
    }

    private static double dot(Vector3 left, Vector3 right) {
        return left.getX() * right.getX() + left.getY() * right.getY() + left.getZ() * right.getZ();
    }

    private static double determinant(Matrix3 matrix) {
        return matrix.getElement(0, 0) * (matrix.getElement(1, 1) * matrix.getElement(2, 2)
                - matrix.getElement(1, 2) * matrix.getElement(2, 1))
                - matrix.getElement(0, 1) * (matrix.getElement(1, 0) * matrix.getElement(2, 2)
                        - matrix.getElement(1, 2) * matrix.getElement(2, 0))
                + matrix.getElement(0, 2) * (matrix.getElement(1, 0) * matrix.getElement(2, 1)
                        - matrix.getElement(1, 1) * matrix.getElement(2, 0));
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
