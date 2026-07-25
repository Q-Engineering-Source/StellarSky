package stellarium.time;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import stellarapi.api.CelestialPeriod;

public class StellarSkyTimeTest {
	@Test
	public void setsMidnightUsingSolarPeriodInsteadOfMinecraftPhase() {
		CelestialPeriod solarDay = new CelestialPeriod("Day", 24000.0, 1.0 / 12.0);

		long result = StellarSkyTime.timeAtSolarOffset(solarDay, 18000L, 0.0);

		assertEquals(-2000L, result);
		assertEquals(0.0, solarDay.getOffset(result, 0.0f), 0.000001);
	}

	@Test
	public void preservesCurrentLocalSolarDayWhenSettingAnEarlierTime() {
		CelestialPeriod solarDay = new CelestialPeriod("Day", 24000.0, 0.25);

		long result = StellarSkyTime.timeAtSolarOffset(solarDay, 31000L, 0.25);

		assertEquals(24000L, result);
		assertEquals(0.25, solarDay.getOffset(result, 0.0f), 0.000001);
	}

	@Test
	public void supportsConfiguredNonVanillaDayLengths() {
		CelestialPeriod solarDay = new CelestialPeriod("Day", 48000.0, 0.25);

		long result = StellarSkyTime.timeAtSolarOffset(solarDay, 0L, 0.5);

		assertEquals(12000L, result);
		assertEquals(0.5, solarDay.getOffset(result, 0.0f), 0.000001);
	}
}
