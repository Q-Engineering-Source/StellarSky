package stellarium.time;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.minecraft.profiler.Profiler;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;

public class LegacyTimeCadenceGoldenTest {
    private static final String CADENCE_FIXTURE = "/stellarium/legacy-golden/2a/cadence.tsv";

    @Test
    public void classifiesTheActualCadenceCalculationWithoutAnExtraPredictionCall() {
        ClientClockWorld world = new ClientClockWorld(3100, 0L);
        StellarSkyTime.setClientTimeState(3100, 1.0, false, 0, 60);
        assertEquals(new StellarSkyTime.WorldTimeUpdate(Long.MIN_VALUE, true, true),
                StellarSkyTime.calculateNextWorldTime(world, Long.MAX_VALUE));
        StellarSkyTime.setClientTimeState(3100, -1.0, false, 0, 60);
        assertEquals(new StellarSkyTime.WorldTimeUpdate(Long.MAX_VALUE, true, true),
                StellarSkyTime.calculateNextWorldTime(world, Long.MIN_VALUE));
        StellarSkyTime.setClientTimeState(3100, 0.0, false, 0, 60);
        assertEquals(new StellarSkyTime.WorldTimeUpdate(40L, true, false),
                StellarSkyTime.calculateNextWorldTime(world, 40L));
        StellarSkyTime.setClientTimeState(3100, 1.0, true, 0, 60);
        StellarSkyTime.WorldTimeUpdate corrected = StellarSkyTime.calculateNextWorldTime(world, 40L);
        assertFalse(corrected.normalCadence());
        assertFalse(corrected.arithmeticWrapped());
        // The first real calculation consumes the correction deadline; the next is cadence.
        assertTrue(StellarSkyTime.calculateNextWorldTime(world, corrected.worldTime()).normalCadence());
    }

    @Test
    public void reproducesDiscreteWorldAndWeatherCadenceFromGoldenFixture() throws Exception {
        List<CadenceCase> fixtures = loadCadenceFixture();
        assertFalse("Cadence fixture must not be empty", fixtures.isEmpty());

        int dimension = 2000;
        for(CadenceCase fixture : fixtures) {
            ClientClockWorld world = new ClientClockWorld(dimension++, fixture.totalWorldTime());
            StellarSkyTime.setClientTimeState(world.provider.getDimension(), fixture.multiplier(), false, 0, 60);

            assertEquals(fixture.id() + " world time",
                    fixture.expectedNextWorldTime(),
                    StellarSkyTime.nextWorldTime(world, fixture.currentWorldTime()));
            assertEquals(fixture.id() + " weather",
                    fixture.expectedNextWeather(),
                    StellarSkyTime.nextWeatherTime(world, fixture.currentWeather()));
        }
    }

    @Test
    public void preservesLegacyMultiplierClampRules() {
        assertEquals(-20.0, StellarSkyTime.clamp(-100.0), 0.0);
        assertEquals(72.0, StellarSkyTime.clamp(100.0), 0.0);
        assertEquals(0.5, StellarSkyTime.clamp(0.5), 0.0);
        assertEquals(1.0, StellarSkyTime.clamp(Double.NaN), 0.0);
        assertEquals(1.0, StellarSkyTime.clamp(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(1.0, StellarSkyTime.clamp(Double.NEGATIVE_INFINITY), 0.0);
    }

    @Test
    public void convertsMappedSystemTimeWorldClockToAstronomicalYear() {
        ClientClockWorld world = new ClientClockWorld(3000, 0L);
        StellarSkyTime.setClientTimeState(3000, 1.0, true, 480, 60);

        assertEquals(0.0, StellarSkyTime.getAstronomicalYear(world, 38000L), 0.0);
        assertEquals(0.002737909255830788,
                StellarSkyTime.getAstronomicalYear(world, 62000L), 1.0e-18);
    }

    private static List<CadenceCase> loadCadenceFixture() throws Exception {
        InputStream stream = LegacyTimeCadenceGoldenTest.class.getResourceAsStream(CADENCE_FIXTURE);
        assertNotNull("Missing " + CADENCE_FIXTURE, stream);

        List<CadenceCase> fixtures = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                if(line.isBlank() || line.startsWith("#") || line.startsWith("id\t"))
                    continue;
                String[] fields = line.split("\t", -1);
                assertEquals("Invalid cadence fixture row: " + line, 7, fields.length);
                fixtures.add(new CadenceCase(
                        fields[0],
                        Long.parseLong(fields[1]),
                        Long.parseLong(fields[2]),
                        Double.parseDouble(fields[3]),
                        Long.parseLong(fields[4]),
                        Integer.parseInt(fields[5]),
                        Integer.parseInt(fields[6])));
            }
        }
        return fixtures;
    }

    private record CadenceCase(
            String id,
            long totalWorldTime,
            long currentWorldTime,
            double multiplier,
            long expectedNextWorldTime,
            int currentWeather,
            int expectedNextWeather) {
    }

    private static final class ClientClockWorld extends World {
        private ClientClockWorld(int dimension, long totalWorldTime) {
            super(null,
                    new WorldInfo(new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                            "m3a2-time-golden"),
                    new TestWorldProvider(dimension),
                    new Profiler(),
                    true);
            this.worldInfo.setWorldTotalTime(totalWorldTime);
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

    private static final class TestWorldProvider extends WorldProvider {
        private TestWorldProvider(int dimension) {
            this.setDimension(dimension);
        }

        @Override
        public DimensionType getDimensionType() {
            return DimensionType.OVERWORLD;
        }
    }
}
