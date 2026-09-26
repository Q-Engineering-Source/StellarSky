package stellarium.stellars.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import stellarapi.api.lib.math.Vector3;

public class SunVisualTransformTest {

	private static final double EPSILON = 1.0e-12;

	@Test
	public void axisAndGeneralCentersMapToGroundZenith() {
		assertMapsToZenith(new Vector3(0.0, 0.0, 7.0));
		assertMapsToZenith(new Vector3(3.0, 0.0, 0.0));
		assertMapsToZenith(new Vector3(-2.0, 5.0, 4.0));
	}

	@Test
	public void rotationPreservesMeshLengthsDotProductsAndOrientation() {
		SunVisualTransform transform = SunVisualTransform.toGroundZenith(
				new Vector3(1.25, -0.5, 2.0));
		assertPreservesMeshMetrics(transform);
	}

	@Test
	public void almostAntipodalCenterWithTinyHorizontalComponentUsesFiniteProperFallback() {
		Vector3 center = new Vector3(1.0e-16, -1.0e-16, -1.0);
		SunVisualTransform transform = SunVisualTransform.toGroundZenith(center);
		Vector3 result = transform.transform(center, new Vector3());

		assertTrue(Double.isFinite(result.getX()));
		assertTrue(Double.isFinite(result.getY()));
		assertTrue(Double.isFinite(result.getZ()));
		assertEquals(0.0, result.getX(), EPSILON);
		assertEquals(0.0, result.getY(), EPSILON);
		assertEquals(center.size(), result.getZ(), EPSILON);
		assertPreservesMeshMetrics(transform);
	}

	private static void assertPreservesMeshMetrics(SunVisualTransform transform) {
		Vector3 first = new Vector3(0.3, -0.8, 0.5);
		Vector3 second = new Vector3(-0.6, 0.4, 0.7);
		Vector3 third = new Vector3(0.2, 0.9, -0.3);
		Vector3 rotatedFirst = transform.transform(first, new Vector3());
		Vector3 rotatedSecond = transform.transform(second, new Vector3());
		Vector3 rotatedThird = transform.transform(third, new Vector3());

		assertEquals(first.size(), rotatedFirst.size(), EPSILON);
		assertEquals(second.size(), rotatedSecond.size(), EPSILON);
		assertEquals(first.dot(second), rotatedFirst.dot(rotatedSecond), EPSILON);
		assertEquals(tripleProduct(first, second, third),
				tripleProduct(rotatedFirst, rotatedSecond, rotatedThird), EPSILON);

		Vector3 xAxis = transform.transform(new Vector3(1.0, 0.0, 0.0), new Vector3());
		Vector3 yAxis = transform.transform(new Vector3(0.0, 1.0, 0.0), new Vector3());
		Vector3 zAxis = transform.transform(new Vector3(0.0, 0.0, 1.0), new Vector3());
		assertEquals(1.0, tripleProduct(xAxis, yAxis, zAxis), EPSILON);
	}

	@Test
	public void exactAndNearAntipodalCentersRemainFiniteAndMapToZenith() {
		assertMapsToZenith(new Vector3(0.0, 0.0, -1.0));
		assertMapsToZenith(new Vector3(1.0e-10, -3.0e-10, -1.0));
	}

	@Test
	public void nearZenithButNondegenerateCenterStillMapsExactlyToZenith() {
		SunVisualTransform transform = SunVisualTransform.toGroundZenith(
				new Vector3(1.0e-8, -2.0e-8, 1.0));
		Vector3 result = transform.transform(new Vector3(1.0e-8, -2.0e-8, 1.0), new Vector3());

		assertEquals(0.0, result.getX(), EPSILON);
		assertEquals(0.0, result.getY(), EPSILON);
		assertEquals(1.0, result.getZ(), EPSILON);
	}

	@Test
	public void rejectsZeroAndNonFiniteCenters() {
		assertThrows(IllegalArgumentException.class,
				() -> SunVisualTransform.toGroundZenith(new Vector3()));
		assertThrows(IllegalArgumentException.class,
				() -> SunVisualTransform.toGroundZenith(new Vector3(Double.NaN, 0.0, 1.0)));
		assertThrows(IllegalArgumentException.class,
				() -> SunVisualTransform.toGroundZenith(new Vector3(Double.POSITIVE_INFINITY, 0.0, 1.0)));
	}

	private static void assertMapsToZenith(Vector3 center) {
		Vector3 result = SunVisualTransform.toGroundZenith(center).transform(center, new Vector3());
		assertTrue(Double.isFinite(result.getX()));
		assertTrue(Double.isFinite(result.getY()));
		assertTrue(Double.isFinite(result.getZ()));
		assertEquals(0.0, result.getX(), EPSILON);
		assertEquals(0.0, result.getY(), EPSILON);
		assertEquals(center.size(), result.getZ(), EPSILON);
	}

	private static double tripleProduct(Vector3 first, Vector3 second, Vector3 third) {
		return new Vector3().setCross(first, second).dot(third);
	}
}
