#version 400 compatibility

uniform vec2 uStripZ;
uniform vec2 uBoard;
uniform vec4 uBands;
uniform vec2 uBandEdge;
uniform int uCoverage;
uniform float uSideFeather;
uniform float uMotionFeather;
uniform float uWeather;
uniform mat4 uInverseProjection;
uniform mat4 uInverseModelView;
uniform vec3 uCameraRelative;
uniform sampler2D uCloudTraceGeometry;
uniform sampler2D uCloudTraceMaterial;

varying vec2 vUv;

// Use the Java-owned numeric limit without importing the expensive cloud tracer.
const float SS_CLOUD_LARGE_T = float(SS_CLOUD_NUMERIC_TRACE_LIMIT);

int ssBoardCoverage() { return uCoverage; }
dvec2 ssBoardVerticalBounds() { return dvec2(uBoard.x, uBoard.x + uBoard.y); }
dvec2 ssBoardStripBounds() { return dvec2(uStripZ); }
dvec4 ssBoardBandSpec() { return dvec4(uBands); }
dvec2 ssBoardEdgeSpec() { return dvec2(uBandEdge); }

#include "board_query.glsl"
#include "cloud_composite_style.glsl"

float positiveRemainder(float value, float period) {
    float result = mod(value, period);
    return result < 0.0 ? result + period : result;
}

float cloudLightTransmission(vec3 point) {
    if (point.z < uStripZ.x || point.z >= uStripZ.y || point.y >= uBoard.x + uBoard.y || uCoverage == 0) return 1.0;
    float position = positiveRemainder(uBandEdge.y * (dot(point.xz, uBands.xy) - uBandEdge.x), uBands.z);
    bool material = uCoverage == 2 || position <= uBands.w;
    if (point.y >= uBoard.x) return material ? 0.0 : 1.0;
    float transmission = material ? 0.0 : 1.0;
    if (uCoverage == 1 && material && uMotionFeather > 0.0) {
        float inward = min(position, uBands.w - position);
        float progress = clamp(inward / uMotionFeather, 0.0, 1.0);
        transmission = 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }
    if (uSideFeather > 0.0) {
        float inward = min(point.z - uStripZ.x, uStripZ.y - point.z);
        float progress = clamp(inward / uSideFeather, 0.0, 1.0);
        float smoothFactor = progress * progress * (3.0 - 2.0 * progress);
        transmission = mix(1.0, transmission, smoothFactor);
    }
    return transmission;
}

void main() {
    vec4 rawGeometry = texture2D(uCloudTraceGeometry, vUv);
    float cloudT = rawGeometry.w;
    if (cloudT < 0.0) discard;
    vec4 rawMaterial = texture2D(uCloudTraceMaterial, vUv);
    vec4 farView = uInverseProjection * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewDirection = normalize(farView.xyz / farView.w);
    vec3 ray = normalize((uInverseModelView * vec4(viewDirection, 0.0)).xyz);
    if (cloudT > ssDistantOpaqueLimit(ray)) discard;
    if (uSSBoardMeshDepthActive != 0) {
        if (ssSelectedBoardPrecedes(double(cloudT))) discard;
    } else {
        double boardT = ssBoardDistance(ray, double(cloudT) + 1.0LF);
        if (boardT >= 0.0LF && boardT <= double(cloudT)) discard;
    }
    vec3 point = vec3(ssCurvedRayPoint(ray, double(cloudT)));
    vec4 eyeClip = gl_ProjectionMatrix * (gl_ModelViewMatrix * vec4(uCameraRelative, 1.0));
    vec4 rayClip = gl_ProjectionMatrix * (gl_ModelViewMatrix * vec4(ray, 0.0));
    vec4 clip = cloudT >= float(SS_CLOUD_LOD_TAIL11_END)
            ? rayClip + eyeClip / cloudT : eyeClip + rayClip * cloudT;
    if (!(clip.w > 0.0) || clip.x != clip.x || clip.y != clip.y || clip.z != clip.z
            || abs(clip.x) >= SS_CLOUD_LARGE_T || abs(clip.y) >= SS_CLOUD_LARGE_T
            || abs(clip.z) >= SS_CLOUD_LARGE_T || abs(clip.w) >= SS_CLOUD_LARGE_T) discard;
    float physicalDepth = gl_DepthRange.near + (clip.z / clip.w * 0.5 + 0.5) * gl_DepthRange.diff;
    gl_FragDepth = clamp(physicalDepth, min(gl_DepthRange.near, gl_DepthRange.far),
            max(gl_DepthRange.near, gl_DepthRange.far));
    float censored = rawMaterial.w;
    bool materialLod = censored == 0.0 || censored == 3.0 || censored == 4.0;
    float face = materialLod ? ssCloudFaceBrightness(rawGeometry.xyz) : 0.72;
    vec3 colour = materialLod ? rawMaterial.rgb : vec3(0.55);
    float illumination = materialLod ? 0.08 + 0.92 * cloudLightTransmission(point) : 0.08;
    illumination *= mix(1.0, 0.72, uWeather);
    gl_FragData[0] = vec4(colour * face * illumination, 1.0);
    gl_FragData[1] = ssEncodeOwnMediaDistance(double(cloudT));
}
