package stellarium.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

import net.minecraft.util.ResourceLocation;
import stellarapi.api.CelestialPeriod;
import stellarapi.api.celestials.CelestialEffectors;
import stellarapi.api.celestials.CelestialObject;
import stellarapi.api.celestials.EnumObjectType;
import stellarapi.api.daywake.EnumDaytimeDescriptor;
import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.optics.EnumRGBA;
import stellarapi.api.optics.Wavelength;
import stellarapi.api.view.IAtmosphereEffect;
import stellarapi.api.view.ICCoordinates;
import stellarapi.example.CelestialHelperSimple;
import stellarapi.feature.command.FixedCommandTime;
import stellarapi.impl.daytime.DefaultDaytimeChecker;

public class LegacyGameplayHelperGoldenTest {
    private static final String FIXTURE = "/stellarium/legacy-golden/2e/gameplay-helper.tsv";
    private static final double EPSILON = 1.0e-6;

    @Test
    public void freezesSimpleHelperAngleOpticsAndMoonPhase() throws Exception {
        Map<String, String> golden = loadGolden();
        CelestialPeriod day = new CelestialPeriod("Golden Day", 24000.0, 0.25);
        FixedCelestialObject sun = new FixedCelestialObject("sun", EnumObjectType.Star,
                day, null, new SpCoord(0.0, -10.0).getVec(), 0.4, 0.0);
        FixedCelestialObject moon = new FixedCelestialObject("moon", EnumObjectType.Satellite,
                day, new CelestialPeriod("Golden Lunar Month", 64000.0, 0.125),
                new SpCoord(0.0, 20.0).getVec(), 0.2, 0.35);
        FixedCoordinates coordinates = new FixedCoordinates(
                new CelestialPeriod("Coordinate Day", 48000.0, 0.125));
        CelestialHelperSimple helper = new CelestialHelperSimple(
                1.5f, 0.8f, sun, moon, coordinates, new FixedAtmosphere());

        assertEquals(number(golden, "sun_angle_t0"), helper.calculateCelestialAngle(0L, 0.0f), EPSILON);
        assertEquals(number(golden, "sun_angle_t6000"), helper.calculateCelestialAngle(6000L, 0.0f), EPSILON);
        assertEquals(number(golden, "sky_transmission"), helper.getSkyTransmissionFactor(0.0f), EPSILON);
        assertEquals(number(golden, "sunrise_sunset_factor"),
                helper.calculateSunriseSunsetFactor(EnumRGBA.Red, 0.0f), EPSILON);
        assertEquals(number(golden, "dispersion_factor"), helper.getDispersionFactor(EnumRGBA.Blue, 0.0f), EPSILON);
        assertEquals(number(golden, "light_pollution_factor"),
                helper.getLightPollutionFactor(EnumRGBA.Green, 0.0f), EPSILON);
        assertEquals(number(golden, "minimum_sky_render_brightness"),
                helper.minimumSkyRenderBrightness(), EPSILON);
        assertEquals(integer(golden, "moon_phase_t0"), helper.getCurrentMoonPhase(0L));
        assertEquals(integer(golden, "moon_phase_t8000"), helper.getCurrentMoonPhase(8000L));
        assertEquals(number(golden, "moon_phase_factor"), helper.getCurrentMoonPhaseFactor(), EPSILON);
    }

    @Test
    public void freezesNullableSunFallbackAndNullHeightAnomaly() throws Exception {
        Map<String, String> golden = loadGolden();
        FixedCoordinates coordinates = new FixedCoordinates(
                new CelestialPeriod("Coordinate Day", 48000.0, 0.125));
        CelestialHelperSimple helper = new CelestialHelperSimple(
                1.0f, 1.0f, null, null, coordinates, new FixedAtmosphere());

        assertEquals(number(golden, "fallback_angle_t0"), helper.calculateCelestialAngle(0L, 0.0f), EPSILON);
        assertEquals(0.0f, helper.getSunlightFactor(EnumRGBA.Alpha, 0.0f), 0.0f);
        assertEquals(0.0f, helper.getSunlightRenderBrightnessFactor(0.0f), 0.0f);
        assertEquals(0.0f, helper.calculateSunriseSunsetFactor(EnumRGBA.Red, 0.0f), 0.0f);

        try {
            helper.getSunHeightFactor(0.0f);
            fail("Legacy helper dereferences nullable Sun before its null guard");
        } catch(NullPointerException expected) {
            assertTrue(expected.getMessage() == null || expected.getMessage().contains("sun"));
        }
    }

    @Test
    public void freezesConventionalSinglePrimaryDaytimeDescriptors() throws Exception {
        Map<String, String> golden = loadGolden();
        FixedCelestialObject sun = new FixedCelestialObject("descriptor_sun", EnumObjectType.Star,
                new CelestialPeriod("Descriptor Day", 24000.0, 0.0), null,
                new Vector3(1.0, 0.0, 0.0), 1.0, 0.0);
        CelestialEffectors sources = new CelestialEffectors(List.of(sun));
        DescriptorCoordinates coordinates = new DescriptorCoordinates(false);
        DefaultDaytimeChecker checker = new DefaultDaytimeChecker();

        assertFalse(checker.accept(null, null, coordinates, EnumDaytimeDescriptor.MIDDAY));
        assertFalse(checker.accept(null, sources, null, EnumDaytimeDescriptor.MIDDAY));

        for(EnumDaytimeDescriptor descriptor : EnumDaytimeDescriptor.values()) {
            long expected = integer(golden, "descriptor_" + descriptor.name());
            assertTrue(descriptor.name(), checker.accept(null, sources, coordinates, descriptor));
            assertEquals(descriptor.name(), expected,
                    checker.timeForCertainDescriptor(null, sources, coordinates, descriptor, 0L));
            assertTrue(descriptor.name(), checker.isDescriptorApply(
                    null, sources, coordinates, descriptor, expected, 2));
        }
    }

    @Test
    public void recordsAfternoonFallthroughAsShieldedImplementationAnomaly() throws Exception {
        Map<String, String> golden = loadGolden();
        FixedCelestialObject sun = new FixedCelestialObject("polar_sun", EnumObjectType.Star,
                new CelestialPeriod("Descriptor Day", 24000.0, 0.0), null,
                new Vector3(1.0, 0.0, 0.0), 1.0, 0.0);
        CelestialEffectors sources = new CelestialEffectors(List.of(sun));
        DescriptorCoordinates coordinates = new DescriptorCoordinates(true);
        DefaultDaytimeChecker checker = new DefaultDaytimeChecker();

        assertFalse("The dispatcher rejects AFTERNOON when rise offset is NaN",
                checker.accept(null, sources, coordinates, EnumDaytimeDescriptor.AFTERNOON));
        assertEquals(integer(golden, "direct_afternoon_fallthrough"),
                checker.timeForCertainDescriptor(
                        null, sources, coordinates, EnumDaytimeDescriptor.AFTERNOON, 0L));
    }

    @Test
    public void recordsFixedCommandDescriptorParserAsUnreachable() {
        FixedCommandTime command = new FixedCommandTime();
        for(EnumDaytimeDescriptor descriptor : EnumDaytimeDescriptor.values()) {
            assertNull(descriptor.name(), command.getDescriptor(descriptor.name().toLowerCase(Locale.ROOT)));
            assertNull(descriptor.name(), command.getDescriptor(descriptor.name()));
        }
    }

    private static Map<String, String> loadGolden() throws Exception {
        InputStream stream = LegacyGameplayHelperGoldenTest.class.getResourceAsStream(FIXTURE);
        if(stream == null)
            throw new AssertionError("Missing " + FIXTURE);

        Map<String, String> values = new HashMap<>();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                if(line.isBlank() || line.startsWith("#") || line.startsWith("id\t"))
                    continue;
                String[] fields = line.split("\t", -1);
                assertEquals("Invalid golden row: " + line, 3, fields.length);
                assertNull("Duplicate golden key: " + fields[0], values.put(fields[0], fields[1]));
            }
        }
        return values;
    }

    private static double number(Map<String, String> golden, String key) {
        return Double.parseDouble(required(golden, key));
    }

    private static long integer(Map<String, String> golden, String key) {
        return Long.parseLong(required(golden, key));
    }

    private static String required(Map<String, String> golden, String key) {
        String value = golden.get(key);
        if(value == null)
            throw new AssertionError("Missing golden key: " + key);
        return value;
    }

    private static final class FixedCelestialObject extends CelestialObject {
        private final double brightness;
        private final double phase;

        private FixedCelestialObject(String path, EnumObjectType type, CelestialPeriod horizontalPeriod,
                CelestialPeriod phasePeriod, Vector3 position, double brightness, double phase) {
            super(new ResourceLocation("m3a2", path), type);
            this.brightness = brightness;
            this.phase = phase;
            this.setHorizontalPeriod(horizontalPeriod);
            this.setPhasePeriod(phasePeriod);
            this.setPos(position);
        }

        @Override
        public double getCurrentBrightness(Wavelength wavelength) {
            return this.brightness;
        }

        @Override
        public double getCurrentPhase() {
            return this.phase;
        }
    }

    private static class FixedCoordinates implements ICCoordinates {
        private final CelestialPeriod period;
        private final Matrix3 identity = new Matrix3().setIdentity();

        private FixedCoordinates(CelestialPeriod period) {
            this.period = period;
        }

        @Override
        public Matrix3 getProjectionToGround() {
            return new Matrix3(this.identity);
        }

        @Override
        public CelestialPeriod getPeriod() {
            return this.period;
        }

        @Override
        public double getHighestHeightAngle(Vector3 absPos) {
            return 60.0;
        }

        @Override
        public double getLowestHeightAngle(Vector3 absPos) {
            return -30.0;
        }

        @Override
        public double calculateInitialOffset(Vector3 initialAbsPos, double periodLength) {
            return 0.0;
        }

        @Override
        public double offsetTillObjectReach(Vector3 absPos, double heightAngle) {
            return Double.NaN;
        }
    }

    private static final class DescriptorCoordinates extends FixedCoordinates {
        private final boolean missingRise;

        private DescriptorCoordinates(boolean missingRise) {
            super(new CelestialPeriod("Coordinate Day", 24000.0, 0.0));
            this.missingRise = missingRise;
        }

        @Override
        public double offsetTillObjectReach(Vector3 absPos, double heightAngle) {
            if(Math.abs(heightAngle - 30.0) < EPSILON)
                return this.missingRise ? Double.NaN : 0.2;
            if(Math.abs(heightAngle + 15.0) < EPSILON)
                return 0.3;
            if(Math.abs(heightAngle) < EPSILON)
                return 0.25;
            return Double.NaN;
        }
    }

    private static final class FixedAtmosphere implements IAtmosphereEffect {
        @Override
        public void applyAtmRefraction(SpCoord pos) {
        }

        @Override
        public void disapplyAtmRefraction(SpCoord pos) {
        }

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
            return 0.25f;
        }

        @Override
        public float getDispersionFactor(Wavelength wavelength, float partialTicks) {
            return 0.8f;
        }

        @Override
        public float getLightPollutionFactor(Wavelength wavelength, float partialTicks) {
            return 0.1f;
        }

        @Override
        public float minimumSkyRenderBrightness() {
            return 0.2f;
        }
    }

}
