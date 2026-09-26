#version 400 compatibility

uniform vec4 uDebugViewport;
uniform sampler2D uCloudHigh;
uniform sampler2D uCloudLow;
uniform int uDebugMode;
uniform vec2 uDebugDistanceBand;
uniform sampler2D uCloudGroundHigh;
uniform sampler2D uCloudGroundLow;
uniform ivec2 uCloudGroundPixelOffset;

flat in dvec4 vCloudPlane;
flat in dvec3 vCloudDebugPoint;

vec3 debugUnproject(float z) {
    vec2 ndc = ((gl_FragCoord.xy - uDebugViewport.xy) / uDebugViewport.zw) * 2.0 - 1.0;
    vec4 eye = gl_ProjectionMatrixInverse * vec4(ndc, z, 1.0);
    eye /= eye.w;
    vec4 local = gl_ModelViewMatrixInverse * vec4(eye.xyz, 1.0);
    return local.xyz / local.w;
}

bool debugCloudPrecedes(double lambda) {
    ivec2 pixel = ivec2(gl_FragCoord.xy - uDebugViewport.xy);
    float selectedHigh = texelFetch(uCloudHigh, pixel, 0).r;
    if (selectedHigh >= 1.0e29) return false;
    vec2 candidate = ssEncodeOwnMediaDistance(lambda).rg;
    // An equal selected face is the surface being inspected. Any strictly closer
    // cloud must occlude the marker, including differences in the low residual.
    if (selectedHigh != candidate.x) return selectedHigh < candidate.x;
    return texelFetch(uCloudLow, pixel, 0).r < candidate.y;
}

bool debugGroundPrecedes(double candidate) {
    ivec2 pixel = ivec2(gl_FragCoord.xy) - uCloudGroundPixelOffset;
    float high = texelFetch(uCloudGroundHigh, pixel, 0).r;
    if (high >= 1.0e29) return false;
    vec2 key = ssEncodeOwnMediaDistance(candidate).rg;
    if (high != key.x) return high < key.x;
    return texelFetch(uCloudGroundLow, pixel, 0).r <= key.y;
}

double debugCandidateDistance(vec3 ray) {
    if (uDebugMode == 1) {
        // A point sprite expands around one vertex. Its footprint has constant
        // camera depth, not the depth of an unbounded adjacent cloud face.
        dvec3 relative = vCloudDebugPoint - dvec3(uSSEyeRelative);
        dvec3 viewNormal = dvec3(gl_ModelViewMatrix[0][2], gl_ModelViewMatrix[1][2], gl_ModelViewMatrix[2][2]);
        double denominator = dot(viewNormal, dvec3(ray));
        if (abs(denominator) < 1.0e-15LF) return -1.0LF;
        return dot(viewNormal, relative) / denominator;
    }
    double denominator = dot(vCloudPlane.xyz, dvec3(ray));
    if (abs(denominator) < 1.0e-15LF) return -1.0LF;
    return -(vCloudPlane.w + dot(vCloudPlane.xyz, dvec3(uSSEyeRelative))) / denominator;
}

void main() {
    vec3 ray = normalize(debugUnproject(0.0) - debugUnproject(-1.0));
    double lambda = debugCandidateDistance(ray);
    if (isnan(lambda) || isinf(lambda) || lambda <= 0.0LF || lambda < double(uDebugDistanceBand.x)
            || lambda >= double(uDebugDistanceBand.y) || debugCloudPrecedes(lambda)
            || ssSelectedBoardPrecedes(lambda) || debugGroundPrecedes(lambda)
            || lambda > double(ssDistantOpaqueLimit(ray))) discard;
    dvec3 hit = dvec3(uSSEyeRelative) + dvec3(ray) * lambda;
    dvec4 clip = dmat4(gl_ProjectionMatrix) * dmat4(gl_ModelViewMatrix) * dvec4(hit, 1.0LF);
    if (!(clip.w > 0.0LF)) discard;
    double ndc = clip.z / clip.w;
    if (isnan(ndc) || isinf(ndc) || ndc < -1.0LF) discard;
    float depth = gl_DepthRange.near + (float(ndc) * 0.5 + 0.5) * gl_DepthRange.diff;
    gl_FragDepth = clamp(depth, min(gl_DepthRange.near, gl_DepthRange.far), max(gl_DepthRange.near, gl_DepthRange.far));
    vec3 color = uDebugMode == 1 ? vec3(1.0, 0.31, 0.02) : vec3(0.02, 0.92, 1.0);
    gl_FragData[0] = vec4(color, 1.0);
}
