#version 400 compatibility

uniform vec2 uStripZ;
uniform vec2 uBoard;
uniform vec4 uBands;
uniform vec2 uBandEdge;
uniform int uCoverage;
uniform float uSideFeather;
uniform float uMotionFeather;
uniform float uWeather;
uniform int uCloudLodEnabled;

#include "cloud_style.glsl"

varying vec3 vRelativePosition;
varying vec3 vBaseColor;

float positiveRemainder(float value, float period) {
    float result = mod(value, period);
    return result < 0.0 ? result + period : result;
}

float shadeAt(vec3 point) {
    if (point.z < uStripZ.x || point.z >= uStripZ.y) return 1.0;
    if (point.y >= uBoard.x + uBoard.y || uCoverage == 0) return 1.0;

    float position = positiveRemainder(uBandEdge.y * (dot(point.xz, uBands.xy) - uBandEdge.x), uBands.z);
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
        float progress = clamp(inward / uSideFeather, 0.0, 1.0);
        float smoothstep = progress * progress * (3.0 - 2.0 * progress);
        transmission = mix(1.0, transmission, smoothstep);
    }
    return transmission;
}

void main() {
    // The VBO position is physical. Transform only this sample into the
    // optical display chart before comparing it with the DH opaque limit.
    dvec3 displayOffset = ssCurvedDisplayPoint(dvec3(vRelativePosition)) - dvec3(uSSEyeRelative);
    double displayLambda = ssCurvedLength3(displayOffset.x, displayOffset.y, displayOffset.z);
    if (ssSelectedBoardPrecedes(displayLambda)) discard;
    if (displayLambda > 0.0LF && displayLambda > double(ssDistantOpaqueLimit(vec3(displayOffset / displayLambda)))) discard;
    // Derivatives must be evaluated before the per-pixel LOD discard.
    vec3 screenNormal = normalize(cross(dFdx(vRelativePosition), dFdy(vRelativePosition)));
    vec3 outwardNormal = gl_FrontFacing ? screenNormal : -screenNormal;
    if (uCloudLodEnabled != 0) {
        vec4 eye4 = gl_ModelViewMatrixInverse * vec4(0.0, 0.0, 0.0, 1.0);
        vec3 eye = eye4.xyz / eye4.w;
        if (length(vRelativePosition - eye) >= ssCloudTransitionBoundaries().x) discard;
    }
    // Mesh winding is outward. Derivatives retain that face information even
    // though this closed volume deliberately renders both sides for observers
    // inside the cloud field.
    float side = ssCloudFaceBrightness(outwardNormal);
    // CloudMeshBuilder preserves source RGB rather than baking face factors.
    // This is point-local board transmission, never observer/ground shading or
    // Minecraft's global night cloud colour.
    float localTransmission = shadeAt(vRelativePosition);
    float illumination = 0.08 + 0.92 * localTransmission;
    illumination *= mix(1.0, 0.72, uWeather);
    gl_FragData[0] = vec4(vBaseColor * side * illumination, 1.0);
    gl_FragData[1] = ssEncodeOwnMediaDistance(displayLambda);
}
