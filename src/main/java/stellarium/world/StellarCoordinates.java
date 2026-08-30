package stellarium.world;

import stellarapi.api.CelestialPeriod;
import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.view.ICCoordinates;
import stellarium.common.ServerSettings;

public class StellarCoordinates implements ICCoordinates {
	// Greenwich mean sidereal angle at J2000.0 (2000-01-01 12:00 UT).
	private static final double J2000_GMST = Math.toRadians(280.46061837);

	//Rotation
	private double rot;

	//Length
	private double yearLength, dayLength;

	//Coordinate
	private double latitude, longitude;

	private double axialTilt, precession;
	private int systemTimeOffsetMinutes;

	private double zeroTime;

	private CelestialPeriod dayPeriod;
	private boolean systemTimeModel;

	public StellarCoordinates(ServerSettings commonSettings, PerDimensionSettings settings) {
		this(commonSettings, settings, false);
	}

	public StellarCoordinates(ServerSettings commonSettings, PerDimensionSettings settings, boolean systemTimeModel) {
		this.systemTimeModel = systemTimeModel;
		this.latitude = Math.toRadians(settings.latitude);
		this.longitude = Math.toRadians(settings.longitude);
		this.axialTilt = Math.toRadians(commonSettings.propAxialTilt.getDouble());
		this.precession = Math.toRadians(commonSettings.propPrecession.getDouble());
		configureTimeModel(commonSettings);

		/*double fixedYearLength = this.dayLength * this.yearLength;
		this.yearPeriod = new CelestialPeriod("Year", fixedYearLength,
				(this.zeroTime / fixedYearLength - this.longitude / 2 / Math.PI - 0.25)%1.0);*/

		EqtoEc.setAsRotation(1.0, 0.0, 0.0, -this.axialTilt);
		EctoEq.setAsRotation(1.0, 0.0, 0.0, this.axialTilt);
		REqtoHor.setAsRotation(1.0, 0.0, 0.0, Math.PI / 2 - this.latitude);
		HortoREq.setAsRotation(1.0, 0.0, 0.0, this.latitude - Math.PI / 2);
	}

	public void setSystemTimeModel(ServerSettings settings, boolean enabled) {
		if(this.systemTimeModel == enabled)
			return;
		this.systemTimeModel = enabled;
		configureTimeModel(settings);
	}

	public void setSystemTimeModel(ServerSettings settings, boolean enabled, int offsetMinutes) {
		if(this.systemTimeModel == enabled && this.systemTimeOffsetMinutes == offsetMinutes)
			return;
		this.systemTimeModel = enabled;
		this.systemTimeOffsetMinutes = offsetMinutes;
		configureTimeModel(settings);
	}

	public boolean isSystemTimeModel() {
		return this.systemTimeModel;
	}

	public CelestialPeriod getCivilDayPeriod() {
		return new CelestialPeriod("Civil Day", 24000.0, 0.25);
	}

	private void configureTimeModel(ServerSettings settings) {
		if(this.systemTimeModel) {
			this.yearLength = stellarium.time.StellarSkyTime.REAL_TIME_YEAR_DAYS;
			this.dayLength = 24000.0;
		} else {
			this.yearLength = settings.year;
			this.dayLength = settings.day;
		}
		this.rot = 2 * Math.PI * (this.yearLength + 1);
		double fixedDaylength = this.dayLength * this.yearLength / (this.yearLength + 1);
		if(this.systemTimeModel) {
			// The raw world clock is local civil time. Its J2000.0 value is
			// 2000-01-01 12:00 UTC represented in the configured local zone.
			this.zeroTime = -(30000.0 + this.systemTimeOffsetMinutes * (24000.0 / 1440.0));
		} else {
			this.zeroTime = (settings.yearOffset * settings.year + settings.dayOffset) * settings.day
					+ settings.tickOffset;
		}
		this.dayPeriod = new CelestialPeriod("Celestial Day", fixedDaylength,
				(this.zeroTime / fixedDaylength + (this.systemTimeModel ? J2000_GMST / (2.0 * Math.PI) : 0.0)
						+ this.longitude / (2.0 * Math.PI) - 0.25) % 1.0);
	}

	public void setLocationDegrees(double latitude, double longitude) {
		this.latitude = Math.toRadians(Math.max(-90.0, Math.min(90.0, latitude)));
		double normalizedLongitude = longitude % 360.0;
		if(normalizedLongitude < 0.0)
			normalizedLongitude += 360.0;
		this.longitude = Math.toRadians(normalizedLongitude);
		REqtoHor.setAsRotation(1.0, 0.0, 0.0, Math.PI / 2 - this.latitude);
		HortoREq.setAsRotation(1.0, 0.0, 0.0, this.latitude - Math.PI / 2);
		if(this.systemTimeModel) {
			double fixedDaylength = this.dayLength * this.yearLength / (this.yearLength + 1);
			this.zeroTime = -(30000.0 + this.systemTimeOffsetMinutes * (24000.0 / 1440.0));
			this.dayPeriod = new CelestialPeriod("Celestial Day", fixedDaylength,
					(this.zeroTime / fixedDaylength + J2000_GMST / (2.0 * Math.PI)
							+ this.longitude / (2.0 * Math.PI) - 0.25) % 1.0);
		}
	}

	/*public CelestialPeriod getYearPeriod() {
		return this.yearPeriod;
	}*/

	public void update(double year) {
		double epochRotation = this.systemTimeModel ? J2000_GMST : 0.0;
		ZTEctoNEc.setAsRotation(0.0, 0.0, 1.0, -this.precession*year);
		NEctoZTEc.setAsRotation(0.0, 0.0, 1.0, this.precession*year);
		NEqtoREq.setAsRotation(0.0, 0.0, 1.0, -this.rot*year - epochRotation - this.longitude);
		REqtoNEq.setAsRotation(0.0, 0.0, 1.0, this.rot*year + epochRotation + this.longitude);

		Vector3 East = new Vector3(1.0, 0.0, 0.0);
		invtransform(East);

		Vector3 North = new Vector3(0.0, 1.0, 0.0);
		invtransform(North);

		this.ZenD = new Vector3(0.0, 0.0, 1.0);
		invtransform(ZenD);

		projection.setRow(0, East);
		projection.setRow(1, North);
		projection.setRow(2, ZenD);

		Vector3 EastEq = new Vector3(1.0, 0.0, 0.0);
		invtransformEq(EastEq);

		Vector3 NorthEq = new Vector3(0.0, 1.0, 0.0);
		invtransformEq(NorthEq);

		Vector3 ZenEq = new Vector3(0.0,0.0,1.0);
		invtransformEq(ZenEq);

		projectionEq.setRow(0, EastEq);
		projectionEq.setRow(1, NorthEq);
		projectionEq.setRow(2, ZenEq);
	}

	private void invtransform(Vector3 vec) {
		HortoREq.transform(vec);
		REqtoNEq.transform(vec);
		EqtoEc.transform(vec);
		NEctoZTEc.transform(vec);
	}

	private void invtransformEq(Vector3 vec) {
		EqtoEc.transform(vec);
		NEctoZTEc.transform(vec);
	}


	//Direction of Zenith
	private Vector3 ZenD;

	//Vector from Earth center to Ground
	@Deprecated
	private Vector3 Zen = new Vector3();


	//Equatorial to Ecliptic
	private final Matrix3 EqtoEc = new Matrix3(); 

	//Ecliptic to Equatorial
	private final Matrix3 EctoEq = new Matrix3(); 


	//Zero Time Ecliptic to Now Ecliptic
	private Matrix3 ZTEctoNEc = new Matrix3();

	//Now Ecliptic to Zero Time Ecliptic
	private Matrix3 NEctoZTEc = new Matrix3();


	//Now Equatorial to Rotating Equatorial
	private Matrix3 NEqtoREq = new Matrix3();

	//Rotating Equatorial to Now Equatorial
	private Matrix3 REqtoNEq = new Matrix3();


	//Rotating Equatorial to Horizontal
	private Matrix3 REqtoHor = new Matrix3();

	//Horizontal to Rotating Equatorial
	private Matrix3 HortoREq = new Matrix3();

	private Matrix3 projection = new Matrix3();
	private Matrix3 projectionEq = new Matrix3();


	@Override
	public Matrix3 getProjectionToGround() {
		return this.projection;
	}

	@Override
	public CelestialPeriod getPeriod() {
		return this.dayPeriod;
	}

	@Override
	public double calculateInitialOffset(Vector3 initialAbsPos, double periodLength) {
		Vector3 eqrPos = new Vector3(initialAbsPos);
		projectionEq.transform(eqrPos);

		SpCoord coord = new SpCoord();
		coord.setWithVec(eqrPos);

		return (this.zeroTime / periodLength
				+ (this.systemTimeModel ? J2000_GMST / (2.0 * Math.PI) : 0.0)
				+ this.longitude / 2.0 / Math.PI - coord.x / 360.0 - 0.25)%1.0;
	}

	@Override
	public double getHighestHeightAngle(Vector3 absPos) {
		Vector3 eqrPos = new Vector3(absPos);
		projectionEq.transform(eqrPos);

		SpCoord coord = new SpCoord();
		coord.setWithVec(eqrPos);

		return 90.0 - Math.abs(this.latitude - coord.y);
	}

	@Override
	public double getLowestHeightAngle(Vector3 absPos) {
		Vector3 eqrPos = new Vector3(absPos);
		projectionEq.transform(eqrPos);

		SpCoord coord = new SpCoord();
		coord.setWithVec(eqrPos);

		return Math.abs(this.latitude + coord.y) - 90.0;
	}

	@Override
	public double offsetTillObjectReach(Vector3 absPos, double heightAngle) {		
		Vector3 eqrPos = new Vector3(absPos);
		projectionEq.transform(eqrPos);

		SpCoord coord = new SpCoord();
		coord.setWithVec(eqrPos);

		return this.hourAngleForHeight(Math.toRadians(heightAngle), Math.toRadians(coord.y), Math.toRadians(this.latitude)) / (2 * Math.PI);
	}

	private double hourAngleForHeight(double h, double dec, double lat) {
		return Math.acos((- Math.sin(h) + Math.sin(dec) * Math.sin(lat)) / (Math.cos(dec) * Math.cos(lat)));
	}

}
