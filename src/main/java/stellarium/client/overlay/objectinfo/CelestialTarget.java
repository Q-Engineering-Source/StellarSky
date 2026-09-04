package stellarium.client.overlay.objectinfo;

final class CelestialTarget {
    final String identifier;
    final String englishName;
    final String chineseName;
    final String type;
    final double magnitude;
    final double altitude;
    final double azimuth;
    final double rightAscension;
    final double declination;
    final Double phase;
    final boolean moon;

    CelestialTarget(String identifier, String englishName, String chineseName,
            String type, double magnitude, double altitude,
            double azimuth, double rightAscension, double declination, Double phase, boolean moon) {
        this.identifier = identifier;
        this.englishName = englishName;
        this.chineseName = chineseName;
        this.type = type;
        this.magnitude = magnitude;
        this.altitude = altitude;
        this.azimuth = azimuth;
        this.rightAscension = rightAscension;
        this.declination = declination;
        this.phase = phase;
        this.moon = moon;
    }
}
