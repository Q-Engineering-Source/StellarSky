#version 120

uniform vec4 uViewport;
uniform vec2 uBandHeading;
uniform float uBandSpacing;
uniform float uPanelWidth;
uniform float uGapWidth;
uniform float uEdgeRelative;
uniform int uEdgeOrientation;
uniform int uCoverage;
uniform float uBaseYRelative;
uniform int uThickness;
uniform vec2 uStripZBoundsRelative;

vec3 unproject(float clipZ) {
    vec2 normalized = ((gl_FragCoord.xy - uViewport.xy) / uViewport.zw) * 2.0 - 1.0;
    vec4 eye = gl_ProjectionMatrixInverse * vec4(normalized, clipZ, 1.0);
    eye /= eye.w;
    vec4 local = gl_ModelViewMatrixInverse * vec4(eye.xyz, 1.0);
    return local.xyz / local.w;
}

float signedRemainder(float value, float period) {
    return value - (value < 0.0 ? ceil(value / period) : floor(value / period)) * period;
}

bool materialAt(float fromEdge) {
    float remainder = signedRemainder(fromEdge, uBandSpacing);
    if (remainder >= 0.0) {
        return remainder <= uPanelWidth;
    }
    return remainder <= -uGapWidth;
}

float nextEntryDelta(float fromEdge, float alongBand) {
    float remainder = signedRemainder(fromEdge, uBandSpacing);
    if (alongBand > 0.0) {
        return remainder >= 0.0 ? uBandSpacing - remainder : -remainder;
    }
    return remainder >= 0.0 ? uPanelWidth - remainder : -uGapWidth - remainder;
}

void main() {
    vec3 nearPoint = unproject(-1.0);
    vec3 farPoint = unproject(1.0);
    vec3 ray = farPoint - nearPoint;
    float lower = uBaseYRelative;
    float upper = lower + float(uThickness);
    float entry;
    float exit;
    bool hasFiniteExit = true;

    if (ray.y == 0.0) {
        if (nearPoint.y < lower || nearPoint.y >= upper) {
            discard;
        }
        entry = 0.0;
        exit = 0.0;
        hasFiniteExit = false;
    } else {
        float first = (lower - nearPoint.y) / ray.y;
        float second = (upper - nearPoint.y) / ray.y;
        entry = min(first, second);
        exit = max(first, second);
        if (exit <= 0.0) {
            discard;
        }
    }

    // Intersect the same physical ray with the finite transverse strip. An
    // observer outside the strip may still see its side; do not camera-cull it.
    if (ray.z == 0.0) {
        if (nearPoint.z < uStripZBoundsRelative.x || nearPoint.z >= uStripZBoundsRelative.y) {
            discard;
        }
    } else {
        float firstZ = (uStripZBoundsRelative.x - nearPoint.z) / ray.z;
        float secondZ = (uStripZBoundsRelative.y - nearPoint.z) / ray.z;
        entry = max(entry, min(firstZ, secondZ));
        float exitZ = max(firstZ, secondZ);
        exit = hasFiniteExit ? min(exit, exitZ) : exitZ;
        hasFiniteExit = true;
        if (exit <= 0.0 || max(entry, 0.0) > exit) {
            discard;
        }
    }

    if (uCoverage == 0) {
        discard;
    }
    float hitParameter = max(entry, 0.0);
    vec3 hit = nearPoint + ray * hitParameter;
    if (uCoverage == 1) {
        float fromEdge = float(uEdgeOrientation) * (dot(hit.xz, uBandHeading) - uEdgeRelative);
        float alongBand = float(uEdgeOrientation) * dot(ray.xz, uBandHeading);
        if (!materialAt(fromEdge)) {
            if (alongBand == 0.0) {
                discard;
            }
            hitParameter += nextEntryDelta(fromEdge, alongBand) / alongBand;
            if (hasFiniteExit && hitParameter > exit) {
                discard;
            }
            hit = nearPoint + ray * hitParameter;
        }
        // nextEntryDelta selected the closed material boundary analytically.
        // Reclassifying its rounded position would punch holes in that side face.
    }

    vec4 clip = gl_ProjectionMatrix * gl_ModelViewMatrix * vec4(hit, 1.0);
    if (!(clip.w > 0.0)) discard;
    float ndcDepth = clip.z / clip.w;
    // Reject non-representable rays rather than emit undefined depth (GLSL 1.20 has no isnan).
    if (!(abs(ndcDepth) <= 3.402823e38)) discard;
    float physicalDepth = gl_DepthRange.near + (ndcDepth * 0.5 + 0.5) * gl_DepthRange.diff;
    gl_FragDepth = clamp(physicalDepth, min(gl_DepthRange.near, gl_DepthRange.far),
            max(gl_DepthRange.near, gl_DepthRange.far));
    gl_FragColor = vec4(0.12, 0.12, 0.12, 1.0);
}
