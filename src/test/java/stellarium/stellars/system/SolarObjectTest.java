package stellarium.stellars.system;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SolarObjectTest {
	@Test
	public void initialEarthPositionIsAnImmutableEpochSnapshot() {
		Sun sun = new Sun("sun", 24000.0 * 365.25);
		sun.earthPos.set(1.0, 2.0, 3.0);

		sun.initialUpdate();
		sun.earthPos.set(4.0, 5.0, 6.0);

		assertEquals(1.0, sun.initialEarthPos.getX(), 0.0);
		assertEquals(2.0, sun.initialEarthPos.getY(), 0.0);
		assertEquals(3.0, sun.initialEarthPos.getZ(), 0.0);
	}
}
