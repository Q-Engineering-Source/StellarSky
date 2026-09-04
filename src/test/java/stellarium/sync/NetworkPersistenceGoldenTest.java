package stellarium.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import stellarium.CommonProxy;
import stellarium.IProxy;
import stellarium.StellarSky;
import stellarium.stellars.StellarManager;

public class NetworkPersistenceGoldenTest {
    private static final String WIRE_FIXTURE = "/stellarium/legacy-golden/2g/time-wire.tsv";
    private static final String NBT_FIXTURE = "/stellarium/legacy-golden/2g/time-state-nbt.tsv";

    @Test
    public void timeStatePacketMatchesExactWireFixtureAndRoundTripsByteExactly() throws Exception {
        for(WireRow row : loadWireRows()) {
            ByteBuf encoded = Unpooled.buffer();
            ByteBuf expected = null;
            ByteBuf reencoded = Unpooled.buffer();
            try {
                new MessageTimeMultiplierSync(row.dimension(), row.multiplier(), row.systemSync(),
                        row.offsetMinutes(), row.intervalSeconds()).toBytes(encoded);
                assertEquals(row.id(), row.expectedHex(), ByteBufUtil.hexDump(encoded));
                assertEquals(row.id(), 21, encoded.readableBytes());
                expected = encoded.copy();

                MessageTimeMultiplierSync decoded = new MessageTimeMultiplierSync();
                decoded.fromBytes(encoded);
                decoded.toBytes(reencoded);
                assertEquals(row.id(), expected, reencoded);
            } finally {
                encoded.release();
                if(expected != null)
                    expected.release();
                reencoded.release();
            }
        }
    }

    @Test
    public void publicSettersNormalizeValuesAndCurrentSchemaReadsAllFields() throws Exception {
        Map<String, NbtRow> rows = loadNbtRows();
        StellarManager manager = managerWithDefaults();

        manager.setTimeMultiplier(0, 2.0);
        manager.setTimeMultiplier(0, 0.0);
        manager.setSystemTimeSyncEnabled(0, true);
        manager.setSystemTimeSyncIntervalSeconds(0, 0);
        manager.setTimeZoneOffsetMinutes(0, 9999);
        manager.setLocation(0, 200.0, -30.0, 123.5);

        manager.setTimeMultiplier(1, -100.0);
        manager.setSystemTimeSyncEnabled(1, false);
        manager.setSystemTimeSyncIntervalSeconds(1, 9999);
        manager.setTimeZoneOffsetMinutes(1, -9999);
        manager.setLocation(1, -100.0, 725.0, -64.0);
        assertState(rows.get("paused_after_two"), manager);
        assertState(rows.get("clamped_negative"), manager);

        NBTTagCompound serialized = new NBTTagCompound();
        NBTTagCompound states = new NBTTagCompound();
        states.setTag("0", currentStateTag(rows.get("paused_after_two"), true));
        states.setTag("1", currentStateTag(rows.get("clamped_negative"), true));
        serialized.setTag("DimensionTimeStates", states);
        StellarManager restored = readServerTag(serialized, "current-schema");
        assertState(rows.get("paused_after_two"), restored);
        assertState(rows.get("clamped_negative"), restored);
    }

    @Test
    public void characterizesLegacyAndMissingTagMigration() throws Exception {
        Map<String, NbtRow> rows = loadNbtRows();

        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("TimeMultiplier", 3);
        legacy.setInteger("SavedTimeMultiplier", 0);
        StellarManager migrated = readServerTag(legacy, "legacy-global");
        assertEquals(1, migrated.getTimeStates().size());
        assertEquals(3.0, migrated.getTimeMultiplier(0), 0.0);
        assertEquals(1.0, migrated.getSavedTimeMultiplier(0), 0.0);

        NBTTagCompound missing = new NBTTagCompound();
        NBTTagCompound states = new NBTTagCompound();
        NBTTagCompound dimensionTwo = new NBTTagCompound();
        dimensionTwo.setDouble("Multiplier", 2.0);
        states.setTag("2", dimensionTwo);
        states.setTag("malformed-dimension", new NBTTagCompound());
        missing.setTag("DimensionTimeStates", states);

        StellarManager restored = readServerTag(missing, "missing-tags");
        assertEquals(1, restored.getTimeStates().size());
        assertState(rows.get("missing_saved_tag"), restored);
    }

    private static StellarManager managerWithDefaults() {
        return readServerTag(new NBTTagCompound(), "m3a2-network-persistence");
    }

    private static StellarManager readServerTag(NBTTagCompound tag, String id) {
        IProxy previous = StellarSky.PROXY;
        CommonProxy proxy = new CommonProxy();
        proxy.getServerSettings().timeMultiplier = 1.0;
        proxy.getServerSettings().systemTimeSync = false;
        proxy.getServerSettings().systemTimeSyncIntervalSeconds = 60;
        StellarSky.PROXY = proxy;
        try {
            StellarManager manager = new StellarManager(id);
            manager.syncFromNBT(tag, false);
            return manager;
        } finally {
            StellarSky.PROXY = previous;
        }
    }

    private static NBTTagCompound currentStateTag(NbtRow row, boolean includeSavedMultiplier) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setDouble("Multiplier", row.multiplier());
        if(includeSavedMultiplier)
            tag.setDouble("SavedMultiplier", row.savedMultiplier());
        tag.setBoolean("SystemTimeSync", row.systemSync());
        tag.setInteger("SystemTimeSyncInterval", row.intervalSeconds());
        tag.setBoolean("TimeZoneOverride", row.timeZoneOverride());
        tag.setInteger("TimeZoneOffsetMinutes", row.timeZoneMinutes());
        tag.setBoolean("LocationOverride", row.locationOverride());
        tag.setDouble("Latitude", row.latitude());
        tag.setDouble("Longitude", row.longitude());
        tag.setDouble("Altitude", row.altitude());
        return tag;
    }

    private static void assertState(NbtRow row, StellarManager manager) {
        assertNotNull(row);
        StellarManager.TimeState state = manager.getTimeStates().get(row.dimension());
        assertNotNull(row.id(), state);
        assertEquals(row.id(), row.multiplier(), state.getMultiplier(), 0.0);
        assertEquals(row.id(), row.savedMultiplier(), manager.getSavedTimeMultiplier(row.dimension()), 0.0);
        assertEquals(row.id(), row.systemSync(), state.isSystemTimeSync());
        assertEquals(row.id(), row.intervalSeconds(), state.getSystemTimeSyncIntervalSeconds());
        assertEquals(row.id(), row.timeZoneOverride(), state.hasTimeZoneOverride());
        assertEquals(row.id(), row.timeZoneMinutes(), state.getTimeZoneOffsetMinutes());
        assertEquals(row.id(), row.locationOverride(), state.hasLocationOverride());
        assertEquals(row.id(), row.latitude(), state.getLatitude(), 0.0);
        assertEquals(row.id(), row.longitude(), state.getLongitude(), 0.0);
        assertEquals(row.id(), row.altitude(), state.getAltitude(), 0.0);
    }

    private static List<WireRow> loadWireRows() throws Exception {
        List<String[]> fields = loadTsv(WIRE_FIXTURE, 8);
        List<WireRow> rows = new ArrayList<>();
        for(String[] row : fields)
            rows.add(new WireRow(row[0], Integer.parseInt(row[1]), Double.parseDouble(row[2]),
                    Boolean.parseBoolean(row[3]), Integer.parseInt(row[4]), Integer.parseInt(row[5]),
                    row[6], row[7]));
        return rows;
    }

    private static Map<String, NbtRow> loadNbtRows() throws Exception {
        Map<String, NbtRow> rows = new HashMap<>();
        for(String[] row : loadTsv(NBT_FIXTURE, 13)) {
            NbtRow value = new NbtRow(row[0], Integer.parseInt(row[1]), Double.parseDouble(row[2]),
                    Double.parseDouble(row[3]), Boolean.parseBoolean(row[4]), Integer.parseInt(row[5]),
                    Boolean.parseBoolean(row[6]), Integer.parseInt(row[7]), Boolean.parseBoolean(row[8]),
                    Double.parseDouble(row[9]), Double.parseDouble(row[10]), Double.parseDouble(row[11]), row[12]);
            if(rows.put(value.id(), value) != null)
                throw new AssertionError("Duplicate fixture id " + value.id());
        }
        return rows;
    }

    private static List<String[]> loadTsv(String resource, int expectedColumns) throws Exception {
        InputStream stream = NetworkPersistenceGoldenTest.class.getResourceAsStream(resource);
        assertNotNull("Missing " + resource, stream);
        List<String[]> rows = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                if(line.isBlank() || line.startsWith("#") || line.startsWith("id\t"))
                    continue;
                String[] fields = line.split("\t", -1);
                assertEquals("Invalid fixture row " + line, expectedColumns, fields.length);
                rows.add(fields);
            }
        }
        assertFalse("Empty fixture " + resource, rows.isEmpty());
        return rows;
    }

    private record WireRow(String id, int dimension, double multiplier, boolean systemSync,
            int offsetMinutes, int intervalSeconds, String expectedHex, String classification) {
    }

    private record NbtRow(String id, int dimension, double multiplier, double savedMultiplier,
            boolean systemSync, int intervalSeconds, boolean timeZoneOverride, int timeZoneMinutes,
            boolean locationOverride, double latitude, double longitude, double altitude,
            String classification) {
    }
}
