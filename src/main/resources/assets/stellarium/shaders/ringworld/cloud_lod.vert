#version 400 compatibility

uniform vec4 uCloudRotation;
uniform vec4 uCloudOriginYZ;
uniform mat4 uCloudClipProjection;
// 0: embedded legacy page; 1: affine physical slab; 2: curved physical slab.
uniform int uCloudLocalMode;
uniform vec2 uCloudLocalEyeX;
uniform vec2 uCloudLocalBounds;
uniform vec4 uCloudLocalTransform;

layout(location = 0) in vec3 aCorner0High;
layout(location = 1) in vec3 aCorner0Low;
layout(location = 2) in vec3 aCorner1High;
layout(location = 3) in vec3 aCorner1Low;
layout(location = 4) in vec3 aCorner2High;
layout(location = 5) in vec3 aCorner2Low;
layout(location = 6) in vec3 aCorner3High;
layout(location = 7) in vec3 aCorner3Low;
layout(location = 8) in vec4 aPlaneHigh;
layout(location = 9) in vec4 aPlaneLow;
// Signed physical axis (+/-1=X,+/-2=Y,+/-3=Z), then unwrapped center-X high/low.
layout(location = 10) in vec3 aMaterialChart;
layout(location = 11) in vec2 aPhysicalAnchorHiLo;

flat out dvec4 vCloudPlane;
flat out dvec3 vCloudMaterialNormal;
flat out double vCloudPhysicalAnchor;
flat out dvec3 vCloudDebugPoint;
flat out dvec3 vCloudMaterialChart;
out vec3 vCloudLocalMaterial;

dvec3 localPhysical(dvec3 p) {
    p.x -= double(uCloudLocalEyeX.x) + double(uCloudLocalEyeX.y);
    p.x = clamp(p.x, double(uCloudLocalBounds.x), double(uCloudLocalBounds.y));
    return p;
}

double cloudLocalCurveX(double x, double radius) {
    double angle = x / radius;
    double a2 = angle * angle;
    return x * (1.0LF - a2 / 6.0LF + a2 * a2 / 120.0LF);
}

double cloudLocalCurveRise(double x, double radius) {
    double angle = x / radius;
    double a2 = angle * angle;
    return x * angle * (0.5LF - a2 / 24.0LF + a2 * a2 / 720.0LF);
}

dvec3 localDisplay(dvec3 p) {
    double radius = ssCurvedRadius();
    double x, rise;
    if (uCloudLocalMode == 1) {
        x = double(uCloudLocalTransform.x) * p.x + double(uCloudLocalTransform.y);
        rise = double(uCloudLocalTransform.z) * p.x + double(uCloudLocalTransform.w);
    } else {
        // Near slabs are at most 16km from the eye. Stable small-angle series
        // avoids subtracting two AU-sized values and is bounded by policy.
        x = cloudLocalCurveX(p.x, radius);
        rise = cloudLocalCurveRise(p.x, radius);
    }
    double factor = 1.0LF - p.y / radius;
    return dvec3(x * factor + double(uSSEyeRelative.x), p.y + rise * factor
        - double(uCloudOriginYZ.x) - double(uCloudOriginYZ.y),
        p.z - double(uCloudOriginYZ.z) - double(uCloudOriginYZ.w));
}

dvec3 cloudCorner() {
    // Strip 1,2,0,3 preserves triangles 0,1,2 and 0,2,3, including winding.
    if (gl_VertexID == 0) return dvec3(aCorner1High) + dvec3(aCorner1Low);
    if (gl_VertexID == 1) return dvec3(aCorner2High) + dvec3(aCorner2Low);
    if (gl_VertexID == 2) return dvec3(aCorner0High) + dvec3(aCorner0Low);
    return dvec3(aCorner3High) + dvec3(aCorner3Low);
}

void main() {
    vCloudLocalMaterial = vec3(0.0);
    if (uCloudLocalMode != 0) {
        dvec3 rawA = dvec3(aCorner0High) + dvec3(aCorner0Low);
        dvec3 rawB = dvec3(aCorner1High) + dvec3(aCorner1Low);
        dvec3 rawC = dvec3(aCorner2High) + dvec3(aCorner2Low);
        dvec3 rawD = dvec3(aCorner3High) + dvec3(aCorner3Low);
        double eyeX = double(uCloudLocalEyeX.x) + double(uCloudLocalEyeX.y);
        double minX = min(min(rawA.x, rawB.x), min(rawC.x, rawD.x)) - eyeX;
        double maxX = max(max(rawA.x, rawB.x), max(rawC.x, rawD.x)) - eyeX;
        dvec3 a = localDisplay(localPhysical(rawA));
        dvec3 b = localDisplay(localPhysical(rawB));
        dvec3 d = localDisplay(localPhysical(rawD));
        dvec3 n = cross(b - a, d - a);
        double norm = length(n);
        if (maxX < double(uCloudLocalBounds.x) || minX >= double(uCloudLocalBounds.y) || norm < 1.0e-20LF) {
            gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
            vCloudPlane = dvec4(0.0LF);
            vCloudDebugPoint = dvec3(0.0LF);
            vCloudMaterialNormal = dvec3(0.0LF);
            vCloudPhysicalAnchor = 0.0LF;
            vCloudMaterialChart = dvec3(0.0LF);
            return;
        }
        n /= norm;
        dvec3 physical = localPhysical(cloudCorner());
        dvec3 displayed = localDisplay(physical);
        vCloudPlane = dvec4(n, -dot(n, a));
        vCloudDebugPoint = displayed;
        float axis = aMaterialChart.x;
        vCloudMaterialNormal = abs(axis) == 1.0 ? dvec3(sign(axis), 0.0LF, 0.0LF)
            : abs(axis) == 2.0 ? dvec3(0.0LF, sign(axis), 0.0LF) : dvec3(0.0LF, 0.0LF, sign(axis));
        vCloudPhysicalAnchor = double(aPhysicalAnchorHiLo.x) + double(aPhysicalAnchorHiLo.y);
        vCloudMaterialChart = dvec3(0.0LF);
        vCloudLocalMaterial = vec3(physical.x, physical.y, physical.z
            - double(uCloudOriginYZ.z) - double(uCloudOriginYZ.w));
        gl_Position = vec4(dmat4(uCloudClipProjection) * dmat4(gl_ModelViewMatrix) * dvec4(displayed, 1.0LF));
        return;
    }
    double c = double(uCloudRotation.x) + double(uCloudRotation.y);
    double s = double(uCloudRotation.z) + double(uCloudRotation.w);
    double radius = ssCurvedRadius();
    double originY = double(uCloudOriginYZ.x) + double(uCloudOriginYZ.y);
    double originZ = double(uCloudOriginYZ.z) + double(uCloudOriginYZ.w);
    dvec3 p = cloudCorner();
    // The worker already knows this physical coordinate; do not invert the
    // cylinder again for the same constant anchor at every covered pixel.
    vCloudPhysicalAnchor = double(aPhysicalAnchorHiLo.x) + double(aPhysicalAnchorHiLo.y);
    dvec3 displayed = dvec3(c * p.x + s * (p.y - radius) + double(uSSEyeRelative.x),
            -s * p.x + c * (p.y - radius) + radius - originY, p.z - originZ);
    vCloudDebugPoint = displayed;
    dvec4 plane = dvec4(aPlaneHigh) + dvec4(aPlaneLow);
    dvec3 n = dvec3(c * plane.x + s * plane.y, -s * plane.x + c * plane.y, plane.z);
    dvec3 shift = dvec3(-s * radius + double(uSSEyeRelative.x), radius - c * radius - originY, -originZ);
    // Stored planes obey N.P+d=0.  Preserve the exact transformed chord plane.
    vCloudPlane = dvec4(n, plane.w - dot(n, shift));
    float axis = aMaterialChart.x;
    vCloudMaterialNormal = abs(axis) == 1.0 ? dvec3(sign(axis), 0.0LF, 0.0LF)
            : abs(axis) == 2.0 ? dvec3(0.0LF, sign(axis), 0.0LF) : dvec3(0.0LF, 0.0LF, sign(axis));
    // Corners 0/3 share physical height, even on reversed and vertical faces.
    // Their midpoint gives the chord's center direction once per vertex, not per fragment.
    dvec3 a = dvec3(aCorner0High) + dvec3(aCorner0Low);
    dvec3 d = dvec3(aCorner3High) + dvec3(aCorner3Low);
    dvec2 radial = dvec2((a.x + d.x) * 0.5LF, radius - (a.y + d.y) * 0.5LF);
    double radialLength = length(radial);
    dvec2 direction = radialLength > 0.0LF ? radial / radialLength : dvec2(0.0LF);
    vCloudMaterialChart = dvec3(double(aMaterialChart.y) + double(aMaterialChart.z), direction);
    gl_Position = vec4(dmat4(uCloudClipProjection) * dmat4(gl_ModelViewMatrix) * dvec4(displayed, 1.0LF));
}
