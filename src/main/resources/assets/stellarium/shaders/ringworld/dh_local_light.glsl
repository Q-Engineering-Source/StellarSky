// Physical coordinates are captured before curvature. Local SKY is evaluated per fragment:
// coarse LOD triangles may span a whole panel or gap. BLOCK light is never attenuated here.
in vec3 vSSDhPhysicalRelative;
in vec2 vSSDhRawLight;
uniform int uSSDhLightMode;
uniform sampler2D uLightMap;
uniform vec4 uSSDhBandHigh;
uniform vec4 uSSDhBandLow;
uniform vec4 uSSDhBoundsHigh;
uniform vec4 uSSDhBoundsLow;
uniform vec4 uSSDhDirectionHigh;
uniform vec4 uSSDhDirectionLow;

// Scalar kernel is replayed directly against RingworldDisplayLightField in the CPU test.
double ssDhSkySubtraction(int mode, double x, double y, double z,
        double period, double width, double center, double feather,
        double base, double top, double minZ, double maxZ,
        double dirX, double dirZ, double sideFeather, double full) {
    if (mode == 0 || y >= top || z < minZ || z >= maxZ) return 0.0;
    if (mode == 1) return 15.0;
    double projection = x * dirX + z * dirZ - center;
    double offset = projection - floor(projection / period) * period;
    double distance = min(offset, period - offset);
    double edge = width * 0.5;
    if (y >= base) return (full > 0.5 || distance <= edge) ? 15.0 : 0.0;
    double transmission = 0.0;
    if (full < 0.5) {
        if (feather == 0.0) transmission = distance <= edge ? 0.0 : 1.0;
        else {
            double t = clamp((distance - edge + feather) / feather, 0.0, 1.0);
            transmission = t * t * (3.0 - 2.0 * t);
        }
    }
    double opacity = 1.0 - transmission;
    if (sideFeather > 0.0) {
        double t = clamp(min(z - minZ, maxZ - z) / sideFeather, 0.0, 1.0);
        opacity *= t * t * (3.0 - 2.0 * t);
    }
    return 15.0 - floor(15.0 * (1.0 - opacity) + 0.5);
}

vec3 ssDhLocalLight() {
    dvec4 b = dvec4(uSSDhBandHigh) + dvec4(uSSDhBandLow);
    dvec4 h = dvec4(uSSDhBoundsHigh) + dvec4(uSSDhBoundsLow);
    dvec4 d = dvec4(uSSDhDirectionHigh) + dvec4(uSSDhDirectionLow);
    dvec3 p = dvec3(vSSDhPhysicalRelative);
    double subtraction = ssDhSkySubtraction(uSSDhLightMode, p.x,p.y,p.z,
            b.x,b.y,b.z,b.w, h.x,h.y,h.z,h.w, d.x,d.y,d.z,d.w);
    // DH's vertex variable names are reversed: actual packed metadata stores SKY low,
    // BLOCK high. Its native lightmap coordinates (and this varying) are X=BLOCK, Y=SKY.
    float sky = max(0.0, vSSDhRawLight.y * 16.0 - 0.5 - float(subtraction));
    return texture(uLightMap, vec2(vSSDhRawLight.x, (sky + 0.5) / 16.0)).rgb;
}
