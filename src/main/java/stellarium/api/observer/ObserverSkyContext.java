package stellarium.api.observer;

import java.util.Objects;

import net.minecraft.util.ResourceLocation;

/**
 * Describes the astronomical reference frame seen by one observer.
 *
 * The current StellarSky renderer consumes latitude and longitude. System,
 * body, frame, and altitude are carried now so space mods can select other
 * celestial models without changing the network protocol again.
 */
public final class ObserverSkyContext {
	public static final ResourceLocation DEFAULT_SYSTEM = new ResourceLocation("stellarium", "solar_system");
	public static final ResourceLocation DEFAULT_FRAME = new ResourceLocation("stellarium", "surface");

	private final int dimension;
	private final ResourceLocation systemId;
	private final ResourceLocation bodyId;
	private final ResourceLocation frameId;
	private final double latitude;
	private final double longitude;
	private final double altitude;

	public ObserverSkyContext(int dimension, ResourceLocation systemId, ResourceLocation bodyId,
			ResourceLocation frameId, double latitude, double longitude, double altitude) {
		this.dimension = dimension;
		this.systemId = Objects.requireNonNull(systemId, "systemId");
		this.bodyId = Objects.requireNonNull(bodyId, "bodyId");
		this.frameId = Objects.requireNonNull(frameId, "frameId");
		this.latitude = Math.max(-90.0, Math.min(90.0, latitude));
		double normalized = longitude % 360.0;
		this.longitude = normalized < 0.0 ? normalized + 360.0 : normalized;
		this.altitude = altitude;
	}

	public static ObserverSkyContext dimensionDefault(int dimension, double latitude, double longitude) {
		return new ObserverSkyContext(dimension, DEFAULT_SYSTEM,
				new ResourceLocation("stellarium", "dimension/" + dimension), DEFAULT_FRAME,
				latitude, longitude, 0.0);
	}

	public int getDimension() {
		return this.dimension;
	}

	public ResourceLocation getSystemId() {
		return this.systemId;
	}

	public ResourceLocation getBodyId() {
		return this.bodyId;
	}

	public ResourceLocation getFrameId() {
		return this.frameId;
	}

	public double getLatitude() {
		return this.latitude;
	}

	public double getLongitude() {
		return this.longitude;
	}

	public double getAltitude() {
		return this.altitude;
	}

	@Override
	public boolean equals(Object other) {
		if(this == other)
			return true;
		if(!(other instanceof ObserverSkyContext))
			return false;
		ObserverSkyContext context = (ObserverSkyContext) other;
		return this.dimension == context.dimension
				&& Double.compare(this.latitude, context.latitude) == 0
				&& Double.compare(this.longitude, context.longitude) == 0
				&& Double.compare(this.altitude, context.altitude) == 0
				&& this.systemId.equals(context.systemId)
				&& this.bodyId.equals(context.bodyId)
				&& this.frameId.equals(context.frameId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.dimension, this.systemId, this.bodyId, this.frameId,
				this.latitude, this.longitude, this.altitude);
	}
}
