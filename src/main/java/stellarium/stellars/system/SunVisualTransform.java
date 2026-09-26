package stellarium.stellars.system;

import stellarapi.api.lib.math.Vector3;

/**
 * Immutable proper rotation from a legacy ground-basis sun direction to the
 * ringworld ground zenith. The caller supplies render-local output vectors.
 */
public final class SunVisualTransform {

	private static final double ANTIPODAL_SINE_EPSILON = 1.0e-15;

	private static final SunVisualTransform IDENTITY = new SunVisualTransform(
			1.0, 0.0, 0.0,
			0.0, 1.0, 0.0,
			0.0, 0.0, 1.0);

	private final double m00;
	private final double m01;
	private final double m02;
	private final double m10;
	private final double m11;
	private final double m12;
	private final double m20;
	private final double m21;
	private final double m22;

	private SunVisualTransform(double m00, double m01, double m02,
			double m10, double m11, double m12,
			double m20, double m21, double m22) {
		this.m00 = m00;
		this.m01 = m01;
		this.m02 = m02;
		this.m10 = m10;
		this.m11 = m11;
		this.m12 = m12;
		this.m20 = m20;
		this.m21 = m21;
		this.m22 = m22;
	}

	/**
	 * Builds the shortest stable proper rotation that takes {@code center} to
	 * the ground-basis zenith. Only an effectively degenerate near-zenith
	 * direction remains identity; ordinary near-parallel vectors still rotate.
	 */
	public static SunVisualTransform toGroundZenith(Vector3 center) {
		if(center == null)
			throw new IllegalArgumentException("Sun center must not be null");

		double originalX = center.getX();
		double originalY = center.getY();
		double originalZ = center.getZ();
		if(!Double.isFinite(originalX) || !Double.isFinite(originalY)
				|| !Double.isFinite(originalZ))
			throw new IllegalArgumentException("Sun center must be finite");

		double greatestComponent = Math.max(Math.abs(originalX),
				Math.max(Math.abs(originalY), Math.abs(originalZ)));
		if(greatestComponent == 0.0)
			throw new IllegalArgumentException("Sun center must be nonzero");

		double scaledX = originalX / greatestComponent;
		double scaledY = originalY / greatestComponent;
		double scaledZ = originalZ / greatestComponent;
		double inverseLength = 1.0 / Math.sqrt(scaledX * scaledX
				+ scaledY * scaledY + scaledZ * scaledZ);
		double x = scaledX * inverseLength;
		double y = scaledY * inverseLength;
		double z = scaledZ * inverseLength;
		double cosine = Math.max(-1.0, Math.min(1.0, z));
		double sine = Math.sqrt(x * x + y * y);

		if(sine <= ANTIPODAL_SINE_EPSILON)
			return cosine < 0.0 ? antipodalRotation(x, y, z) : IDENTITY;

		// Axis is normalized(center cross zenith), so Rodrigues maps center to +Z.
		double axisX = y / sine;
		double axisY = -x / sine;
		double oneMinusCosine = 1.0 - cosine;
		return new SunVisualTransform(
				oneMinusCosine * axisX * axisX + cosine,
				oneMinusCosine * axisX * axisY,
				sine * axisY,
				oneMinusCosine * axisX * axisY,
				oneMinusCosine * axisY * axisY + cosine,
				-sine * axisX,
				-sine * axisY,
				sine * axisX,
				cosine);
	}

	private static SunVisualTransform antipodalRotation(double x, double y, double z) {
		// Pick the least aligned cardinal basis before crossing to avoid a tiny axis.
		double absoluteX = Math.abs(x);
		double absoluteY = Math.abs(y);
		double absoluteZ = Math.abs(z);
		double basisX = absoluteX <= absoluteY && absoluteX <= absoluteZ ? 1.0 : 0.0;
		double basisY = absoluteY < absoluteX && absoluteY <= absoluteZ ? 1.0 : 0.0;
		double basisZ = basisX == 0.0 && basisY == 0.0 ? 1.0 : 0.0;
		double axisX = y * basisZ - z * basisY;
		double axisY = z * basisX - x * basisZ;
		double axisZ = x * basisY - y * basisX;
		double inverseAxisLength = 1.0 / Math.sqrt(axisX * axisX
				+ axisY * axisY + axisZ * axisZ);
		axisX *= inverseAxisLength;
		axisY *= inverseAxisLength;
		axisZ *= inverseAxisLength;

		// R(pi) = 2aa^T - I, a proper rotation with determinant +1.
		return new SunVisualTransform(
				2.0 * axisX * axisX - 1.0,
				2.0 * axisX * axisY,
				2.0 * axisX * axisZ,
				2.0 * axisY * axisX,
				2.0 * axisY * axisY - 1.0,
				2.0 * axisY * axisZ,
				2.0 * axisZ * axisX,
				2.0 * axisZ * axisY,
				2.0 * axisZ * axisZ - 1.0);
	}

	/** Applies this transform without allocating a per-vertex vector. */
	public Vector3 transform(Vector3 source, Vector3 destination) {
		if(source == null || destination == null)
			throw new IllegalArgumentException("Sun transform vectors must not be null");
		double x = source.getX();
		double y = source.getY();
		double z = source.getZ();
		destination.setCoord(0, m00 * x + m01 * y + m02 * z);
		destination.setCoord(1, m10 * x + m11 * y + m12 * z);
		destination.setCoord(2, m20 * x + m21 * y + m22 * z);
		return destination;
	}
}
