#version 120

// Compact curved near-air compositor.  The generic curved/DH/own-media
// preamble is supplied by RingworldShaderSource.readCurved; this source keeps
// only the tangent 0..2048 m local volume and never traces clouds or board hits.
uniform sampler2D uSceneColor;
uniform sampler2D uSceneDepth;
uniform mat4 uInverseProjection;
uniform mat4 uInverseModelView;
uniform vec2 uSnapshotFootYZ;
uniform vec3 uCameraRelative;
uniform vec3 uAirProfile;
uniform vec2 uStripZ;
uniform vec2 uBoard;
uniform vec4 uBands;
uniform vec2 uBandEdge;
uniform int uCoverage;
uniform float uSideFeather;
uniform float uMotionFeather;
uniform float uLocalAirDistance;
uniform float uBandMeanTransmission;
uniform vec3 uSigmaT;
uniform vec3 uSigmaS;
uniform vec3 uSunRadiance;
varying vec2 vUv;

const float SS_LOCAL_NO_HIT = 1.0e15;
const float SS_GAUSS_A = 0.2113248654;
const float SS_GAUSS_B = 0.7886751346;

float localRaySlab(float origin, float direction, float minimum, float maximum, inout float entry, inout float exit) {
    if (direction == 0.0) return origin >= minimum && origin < maximum ? 1.0 : 0.0;
    float first = (minimum - origin) / direction;
    float second = (maximum - origin) / direction;
    entry = max(entry, min(first, second));
    exit = min(exit, max(first, second));
    return exit > entry ? 1.0 : 0.0;
}

float localSceneDistance(float depth, vec2 uv, vec3 ray) {
    // Native depth is an opaque endpoint only below the exact clear value.
    if (depth == 1.0) return SS_LOCAL_NO_HIT;
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    view /= view.w;
    vec4 sceneRelative4 = uInverseModelView * vec4(view.xyz, 1.0);
    vec3 sceneRelative = sceneRelative4.xyz / sceneRelative4.w;
    float distance = dot(sceneRelative - uCameraRelative, ray);
    return distance > 0.0 ? distance : SS_LOCAL_NO_HIT;
}

float localDensity(vec3 point) {
    float worldY = uSnapshotFootYZ.x + point.y;
    if (worldY < uAirProfile.x || worldY >= uAirProfile.z || point.z < uStripZ.x || point.z >= uStripZ.y) return 0.0;
    if (worldY <= uAirProfile.y) return 1.0;
    float progress = (worldY - uAirProfile.y) / (uAirProfile.z - uAirProfile.y);
    return 1.0 - progress * progress * (3.0 - 2.0 * progress);
}

float localVerticalSunMass(float worldY) {
    if (worldY >= uAirProfile.z) return 0.0;
    float fade = uAirProfile.z - uAirProfile.y;
    if (worldY <= uAirProfile.y) return fade * 0.5 + uAirProfile.y - worldY;
    float u = (worldY - uAirProfile.y) / fade;
    return fade * (0.5 - u + u * u * u - 0.5 * u * u * u * u);
}

float localPositiveRemainder(float value, float period) {
    float result = mod(value, period);
    return result < 0.0 ? result + period : result;
}

// Illumination is sampled at the physical tangent point.  The board endpoint
// is intentionally absent: native/DH/own-media depth already selects opacity.
float localBoardTransmission(vec3 point) {
    if (point.y >= uBoard.x + uBoard.y || uCoverage == 0) return 1.0;
    float position = localPositiveRemainder(uBandEdge.y * (dot(point.xz, uBands.xy) - uBandEdge.x), uBands.z);
    bool material = uCoverage == 2 || position <= uBands.w;
    if (point.y >= uBoard.x) return material ? 0.0 : 1.0;
    float transmission = material ? 0.0 : 1.0;
    if (uCoverage == 1 && material && uMotionFeather > 0.0) {
        float inwardPanel = min(position, uBands.w - position);
        float progress = clamp(inwardPanel / uMotionFeather, 0.0, 1.0);
        transmission = 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }
    if (uSideFeather > 0.0) {
        float inward = min(point.z - uStripZ.x, uStripZ.y - point.z);
        float edge = clamp(inward / uSideFeather, 0.0, 1.0);
        transmission = mix(1.0, transmission, edge * edge * (3.0 - 2.0 * edge));
    }
    return transmission;
}

float localPeriodMeanTransmission(vec3 point) {
    if (uCoverage == 0 || point.y >= uBoard.x + uBoard.y) return 1.0;
    if (uCoverage == 2) return 0.0;
    if (point.y >= uBoard.x) return (uBands.z - uBands.w) / uBands.z;
    float transmission = uBandMeanTransmission;
    if (uSideFeather > 0.0) {
        float inward = min(point.z - uStripZ.x, uStripZ.y - point.z);
        float edge = clamp(inward / uSideFeather, 0.0, 1.0);
        transmission = mix(1.0, transmission, edge * edge * (3.0 - 2.0 * edge));
    }
    return transmission;
}

vec3 localStableAverage(vec3 opticalDepth) {
    vec3 series = vec3(1.0) - opticalDepth * 0.5 + opticalDepth * opticalDepth / 6.0;
    return vec3(opticalDepth.r < 0.0001 ? series.r : (1.0 - exp(-opticalDepth.r)) / opticalDepth.r,
            opticalDepth.g < 0.0001 ? series.g : (1.0 - exp(-opticalDepth.g)) / opticalDepth.g,
            opticalDepth.b < 0.0001 ? series.b : (1.0 - exp(-opticalDepth.b)) / opticalDepth.b);
}

void localIntegrateInterval(vec3 ray, float start, float end, inout vec3 accumulated, inout vec3 transmission) {
    float piece = end - start;
    if (!(piece > 0.0)) return;
    // The average is valid only once this interval crosses at least one board period.
    bool periodMean = uCoverage == 1 && abs(dot(ray.xz, uBands.xy)) * piece > uBands.z;
    float phaseCosine = clamp(ray.y, -1.0, 1.0);
    float g = 0.35;
    float phase = (1.0 - g * g) / (12.5663706 * pow(1.0 + g * g - 2.0 * g * phaseCosine, 1.5));
    for (int sampleIndex = 0; sampleIndex < 2; sampleIndex++) {
        float fraction = sampleIndex == 0 ? SS_GAUSS_A : SS_GAUSS_B;
        vec3 point = uCameraRelative + ray * (start + piece * fraction);
        float density = localDensity(point);
        float ds = piece * 0.5;
        vec3 opticalDepth = uSigmaT * density * ds;
        float boardTransmission = periodMean ? localPeriodMeanTransmission(point) : localBoardTransmission(point);
        vec3 sunTransmission = exp(-uSigmaT * localVerticalSunMass(uSnapshotFootYZ.x + point.y));
        vec3 sourceLight = uSigmaS * density * uSunRadiance * phase * boardTransmission * sunTransmission;
        accumulated += transmission * sourceLight * ds * localStableAverage(opticalDepth);
        transmission *= exp(-opticalDepth);
    }
}

float localNextPlane(float current, float end, float origin, float direction, float plane) {
    if (abs(direction) < 0.000001) return end;
    float hit = (plane - origin) / direction;
    return hit > current + 0.0001 && hit < end ? hit : end;
}

void main() {
    vec4 source = texture2D(uSceneColor, vUv);
    vec4 view = uInverseProjection * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewDirection = normalize(view.xyz / view.w);
    vec3 ray = normalize((uInverseModelView * vec4(viewDirection, 0.0)).xyz);

    // Topaque is selected before the local window.  T=min(Topaque, 2048) is
    // only an integration bound, never an invented opaque hit.
    double nativeOpaque = double(localSceneDistance(texture2D(uSceneDepth, vUv).r, vUv, ray));
    double opaque = min(nativeOpaque, min(ssDistantOpaqueLimitD(ray), ssOwnMediaOpaqueLimit()));
    float entry = 0.0;
    float end = min(uLocalAirDistance, float(opaque));
    if (localRaySlab(uCameraRelative.y, ray.y, uAirProfile.x - uSnapshotFootYZ.x,
            uAirProfile.z - uSnapshotFootYZ.x, entry, end) == 0.0
            || localRaySlab(uCameraRelative.z, ray.z, uStripZ.x, uStripZ.y, entry, end) == 0.0
            || !(end > entry)) {
        gl_FragColor = source;
        return;
    }

    vec3 accumulated = vec3(0.0);
    vec3 transmission = vec3(1.0);
    // The only density discontinuity inside an air slab is full-density -> cubic fade.
    float densityCut = localNextPlane(entry, end, uCameraRelative.y, ray.y, uAirProfile.y - uSnapshotFootYZ.x);
    localIntegrateInterval(ray, entry, densityCut, accumulated, transmission);
    if (densityCut < end) localIntegrateInterval(ray, densityCut, end, accumulated, transmission);

    vec3 farLinear = pow(max(source.rgb, vec3(0.0)), vec3(2.2));
    vec3 result = accumulated + transmission * farLinear;
    gl_FragColor = vec4(pow(clamp(result, 0.0, 1.0), vec3(1.0 / 2.2)), source.a);
}
