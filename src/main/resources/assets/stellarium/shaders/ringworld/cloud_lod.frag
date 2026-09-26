#version 400 compatibility

uniform vec4 uCloudRotation;
uniform vec4 uCloudOriginYZ;
uniform vec4 uCloudViewport;
uniform int uCloudPass;
uniform int uCloudMaterialMode;
uniform sampler2D uCloudHigh;
uniform sampler2D uCloudLow;
uniform sampler2D uCloudAtlas;
uniform vec4 uCloudPageOriginHiLo;
uniform ivec2 uCloudPageExtent;
// offsetY, depth, layers, xzScale
uniform vec4 uCloudAtlasInfo;
// finest cell width, cloud thickness, y scale
uniform vec3 uCloudCell;
uniform float uCloudBaseY;
uniform vec2 uCloudDistanceBand;
uniform vec2 uCloudFarBand;
uniform float uCloudRain;
uniform sampler2D uCloudGroundHigh;
uniform sampler2D uCloudGroundLow;
uniform ivec2 uCloudGroundPixelOffset;
uniform vec2 uStripZ;
uniform vec2 uBoard;
uniform vec4 uBands;
uniform vec2 uBandEdge;
uniform int uCoverage;
uniform float uSideFeather;
uniform float uMotionFeather;
uniform float uCloudBottomBrightness;
uniform vec2 uCloudMaterialXRelativeHiLo;
uniform vec2 uCloudObserverYZ;
uniform int uCloudLocalMode;
uniform vec2 uCloudLocalAtlasOffset;
uniform vec3 uCloudLocalShadeOffset;

const int SS_CLOUD_LOD_ATLAS_WIDTH = 512;

flat in dvec4 vCloudPlane;
flat in dvec3 vCloudMaterialNormal;
flat in double vCloudPhysicalAnchor;
flat in dvec3 vCloudMaterialChart;
in vec3 vCloudLocalMaterial;

vec3 cloudUnproject(float z) {
    vec2 ndc = ((gl_FragCoord.xy - uCloudViewport.xy) / uCloudViewport.zw) * 2.0 - 1.0;
    vec4 eye = gl_ProjectionMatrixInverse * vec4(ndc, z, 1.0);
    eye /= eye.w;
    vec4 local = gl_ModelViewMatrixInverse * vec4(eye.xyz, 1.0);
    return local.xyz / local.w;
}

dvec3 cloudMaterialEmbedded(dvec3 embedded) {
    double radius = ssCurvedRadius();
    double radialY = radius - embedded.y;
    double radial = sqrt(embedded.x * embedded.x + radialY * radialY);
    if (radial == 0.0LF) discard;
    double longitude = ssCurvedAtan2(embedded.x, radialY) * radius;
    double cellWidth = double(uCloudCell.x) * double(uCloudAtlasInfo.w);
    double pageX = double(uCloudPageOriginHiLo.x) + double(uCloudPageOriginHiLo.y);
    double pageCenter = (pageX + double(uCloudPageExtent.x) * 0.5LF) * cellWidth;
    double circumference = SS_CURVED_TAU * radius;
    longitude += floor((pageCenter - longitude) / circumference + 0.5LF) * circumference;
    return dvec3(longitude, radius - radial, embedded.z);
}

// Local chart avoids the full inverse-cylinder atan2 on ordinary material pixels.
// A sentinel asks the caller to use the exact reference path outside the proven domain.
double cloudChartLongitude(double embeddedX, double radialY, double radius, double centerX, double centerSin, double centerCos) {
    double u = embeddedX * centerCos - radialY * centerSin;
    double v = embeddedX * centerSin + radialY * centerCos;
    if (radius < 8192000.0LF || v <= 0.0LF || isnan(u) || isinf(u) || isnan(v) || isinf(v)) return 1.0e30LF;
    double q = u / v;
    if (abs(q) > 0.001LF) return 1.0e30LF;
    return centerX + radius * (q - q * q * q / 3.0LF);
}

double cloudChartError(double radius, double centerX, double longitude) {
    // Split payload/basis reconstruction and FP64 cancellation dominate. The final
    // term bounds R*q^5/5 for |q|<=.001, including the cached/legacy chart comparison.
    return 256.0LF * 2.220446049250313e-16LF * (abs(centerX) + radius + abs(longitude) + 1.0LF)
            + radius * 2.0e-16LF;
}

bool cloudChartNeedsExact(double longitude, double error, double cellWidth, double pageX) {
    if (isnan(longitude) || isinf(longitude) || abs(longitude) >= 1.0e29LF || !(cellWidth > 0.0LF)) return true;
    double cell = longitude / cellWidth - pageX;
    double fraction = cell - floor(cell);
    double margin = error / cellWidth + 16.0LF * 2.220446049250313e-16LF * (abs(longitude / cellWidth) + abs(pageX) + 1.0LF);
    return min(fraction, 1.0LF - fraction) <= margin;
}

double cloudCachedHeight(double radius, double radialSquared, double normalY, double anchor) {
    // Horizontal faces use the exact cached Y anchor in cloudFaceMaterial. Computing
    // a radial square root only to overwrite it contributes nothing to their material.
    return abs(normalY) > 0.5LF ? anchor : radius - sqrt(radialSquared);
}

dvec3 cloudMaterialPoint(dvec3 displayed) {
    double c = double(uCloudRotation.x) + double(uCloudRotation.y);
    double s = double(uCloudRotation.z) + double(uCloudRotation.w);
    double radius = ssCurvedRadius();
    double originY = double(uCloudOriginYZ.x) + double(uCloudOriginYZ.y);
    double originZ = double(uCloudOriginYZ.z) + double(uCloudOriginYZ.w);
    double dx = displayed.x - double(uSSEyeRelative.x);
    double dy = displayed.y - (radius - originY);
    dvec3 embedded = dvec3(c * dx - s * dy, s * dx + c * dy + radius, displayed.z + originZ);
    if (uCloudMaterialMode == 0) return cloudMaterialEmbedded(embedded);
    double radialY = radius - embedded.y;
    double radialSquared = embedded.x * embedded.x + radialY * radialY;
    if (radialSquared == 0.0LF) discard;
    double longitude;
    if (abs(vCloudMaterialNormal.x) > 0.5LF) {
        // This coordinate is overwritten by the same physical face anchor below.
        longitude = vCloudPhysicalAnchor;
    } else {
        longitude = cloudChartLongitude(embedded.x, radialY, radius, vCloudMaterialChart.x,
                vCloudMaterialChart.y, vCloudMaterialChart.z);
        double cellWidth = double(uCloudCell.x) * double(uCloudAtlasInfo.w);
        double pageX = double(uCloudPageOriginHiLo.x) + double(uCloudPageOriginHiLo.y);
        if (cloudChartNeedsExact(longitude, cloudChartError(radius, vCloudMaterialChart.x, longitude), cellWidth, pageX)) {
            return cloudMaterialEmbedded(embedded);
        }
    }
    return dvec3(longitude, cloudCachedHeight(radius, radialSquared, vCloudMaterialNormal.y, vCloudPhysicalAnchor), embedded.z);
}

vec4 cloudCachedMaterial(dvec3 material) {
    double cellWidth = double(uCloudCell.x) * double(uCloudAtlasInfo.w);
    double cellHeight = double(uCloudCell.y) * double(uCloudCell.z);
    if (!(cellWidth > 0.0LF) || !(cellHeight > 0.0LF)) discard;
    double pageX = double(uCloudPageOriginHiLo.x) + double(uCloudPageOriginHiLo.y);
    double pageZ = double(uCloudPageOriginHiLo.z) + double(uCloudPageOriginHiLo.w);
    int x = int(floor(material.x / cellWidth - pageX));
    int z = int(floor(material.z / cellWidth - pageZ));
    int width = uCloudPageExtent.x;
    int depth = int(uCloudAtlasInfo.y);
    int layers = int(uCloudAtlasInfo.z);
    if (x < 0 || x >= width || z < 0 || z >= depth) discard;
    int layer = layers == 1 ? 0 : int(floor((material.y - double(uCloudBaseY)) / cellHeight));
    if (layer < 0 || layer >= layers) discard;
    int atlasY = int(uCloudAtlasInfo.x) + layer * depth + z;
    return texelFetch(uCloudAtlas, ivec2(x, atlasY), 0);
}

vec4 cloudFaceMaterial(dvec3 hit, out dvec3 materialPoint) {
    if (uCloudLocalMode != 0) {
        // Perspective-correct physical coordinates belong to this rasterized face.
        // No cylinder inverse, angular fallback, or per-pixel radial square root.
        vec3 cellPoint = vec3((vCloudLocalMaterial.x + uCloudLocalAtlasOffset.x) / (uCloudCell.x * uCloudAtlasInfo.w),
            (vCloudLocalMaterial.y - uCloudBaseY) / (uCloudCell.y * uCloudCell.z),
            (vCloudLocalMaterial.z + uCloudLocalAtlasOffset.y) / (uCloudCell.x * uCloudAtlasInfo.w));
        cellPoint -= vec3(vCloudMaterialNormal) * 0.0001;
        ivec3 cellIndex = ivec3(floor(cellPoint));
        if (cellIndex.x < 0 || cellIndex.x >= uCloudPageExtent.x || cellIndex.z < 0
                || cellIndex.z >= int(uCloudAtlasInfo.y) || cellIndex.y < 0 || cellIndex.y >= int(uCloudAtlasInfo.z)) discard;
        vec4 material = texelFetch(uCloudAtlas, ivec2(cellIndex.x, int(uCloudAtlasInfo.x)
            + cellIndex.y * int(uCloudAtlasInfo.y) + cellIndex.z), 0);
        if (material.a < 0.5) discard;
        materialPoint = dvec3(vCloudLocalMaterial + uCloudLocalShadeOffset);
        return material;
    }
    materialPoint = cloudMaterialPoint(hit);
    double cellWidth = double(uCloudCell.x) * double(uCloudAtlasInfo.w);
    double cellHeight = double(uCloudCell.y) * double(uCloudCell.z);
    // Restore the physical face coordinate instead of the sagged chord coordinate.
    // The exact anchor is cached by the worker and shared by all four vertices.
    double epsilon = min(cellWidth, cellHeight) * 1.0e-4LF;
    if (abs(vCloudMaterialNormal.x) > 0.5LF) materialPoint.x = vCloudPhysicalAnchor - vCloudMaterialNormal.x * epsilon;
    else if (abs(vCloudMaterialNormal.y) > 0.5LF) materialPoint.y = vCloudPhysicalAnchor - vCloudMaterialNormal.y * epsilon;
    else materialPoint.z = vCloudPhysicalAnchor - vCloudMaterialNormal.z * epsilon;
    vec4 material = cloudCachedMaterial(materialPoint);
    if (material.a < 0.5) discard;
    return material;
}

float cloudFarWeight(double distance) {
    float t = clamp((float(distance) - uCloudFarBand.x) / (uCloudFarBand.y - uCloudFarBand.x), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float cloudBayerRank() {
    ivec2 pixel = ivec2(gl_FragCoord.xy - uCloudViewport.xy);
    int x = pixel.x & 3;
    int y = pixel.y & 3;
    return (float((((x & 1) * 2 + (y & 1)) * 4) + (x & 2) + ((y & 2) >> 1)) + 0.5) / 16.0;
}

float positiveRemainder(float value, float period) {
    float result = mod(value, period);
    return result < 0.0 ? result + period : result;
}

float cloudBoardTransmission(vec3 point) {
    if (point.z < uStripZ.x || point.z >= uStripZ.y) return 1.0;
    if (point.y >= uBoard.x + uBoard.y || uCoverage == 0) return 1.0;
    float position = positiveRemainder(uBandEdge.y * (dot(point.xz, uBands.xy) - uBandEdge.x), uBands.z);
    bool panel = uCoverage == 2 || position <= uBands.w;
    if (point.y >= uBoard.x) return panel ? 0.0 : 1.0;
    float transmission = panel ? 0.0 : 1.0;
    if (uCoverage == 1 && panel && uMotionFeather > 0.0) {
        float inwardPanel = min(position, uBands.w - position);
        float progress = clamp(inwardPanel / uMotionFeather, 0.0, 1.0);
        transmission = 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }
    if (uSideFeather > 0.0) {
        float inward = min(point.z - uStripZ.x, uStripZ.y - point.z);
        float progress = clamp(inward / uSideFeather, 0.0, 1.0);
        float smoothing = progress * progress * (3.0 - 2.0 * progress);
        transmission = mix(1.0, transmission, smoothing);
    }
    return transmission;
}

bool cloudGroundPrecedes(double candidate) {
    ivec2 pixel = ivec2(gl_FragCoord.xy) - uCloudGroundPixelOffset;
    float high = texelFetch(uCloudGroundHigh, pixel, 0).r;
    if (high >= 1.0e29) return false;
    vec2 key = ssEncodeOwnMediaDistance(candidate).rg;
    if (high != key.x) return high < key.x;
    return texelFetch(uCloudGroundLow, pixel, 0).r <= key.y;
}

void main() {
    vec3 ray = normalize(cloudUnproject(0.0) - cloudUnproject(-1.0));
    double denominator = dot(vCloudPlane.xyz, dvec3(ray));
    if (abs(denominator) < 1.0e-15LF) discard;
    double lambda = -(vCloudPlane.w + dot(vCloudPlane.xyz, dvec3(uSSEyeRelative))) / denominator;
    if (isnan(lambda) || isinf(lambda) || lambda < double(uCloudDistanceBand.x)
            || lambda >= double(uCloudDistanceBand.y)) discard;
    // Keep cheap complementary coverage ahead of selection texture reads as well.
    if (cloudBayerRank() < cloudFarWeight(lambda)) discard;
    vec4 encoded = ssEncodeOwnMediaDistance(lambda);
    ivec2 reductionPixel = ivec2(gl_FragCoord.xy - uCloudViewport.xy);
    // Prior reductions are immutable here. A losing key cannot reach any output,
    // so reject it before constructing the FP64 hit and projecting it again.
    if (uCloudPass > 0 && encoded.r != texelFetch(uCloudHigh, reductionPixel, 0).r) discard;
    if (uCloudPass == 2 && encoded.g != texelFetch(uCloudLow, reductionPixel, 0).r) discard;
    dvec3 hit = dvec3(uSSEyeRelative) + dvec3(ray) * lambda;
    dvec4 clip = dmat4(gl_ProjectionMatrix) * dmat4(gl_ModelViewMatrix) * dvec4(hit, 1.0LF);
    if (!(clip.w > 0.0LF)) discard;
    double ndc = clip.z / clip.w;
    if (isnan(ndc) || isinf(ndc) || ndc < -1.0LF) discard;
    if (uCloudPass == 2) {
        if (ssSelectedBoardPrecedes(lambda) || cloudGroundPrecedes(lambda)
                || lambda > double(ssDistantOpaqueLimit(ray))) discard;
    }
    // Every reduction winner must satisfy the same coverage as COLOR, including
    // tangent cell/page boundaries. Occupied CPU faces alone cannot prove this.
    dvec3 materialPoint;
    vec4 material = cloudFaceMaterial(hit, materialPoint);
    if (uCloudPass == 0) { gl_FragData[0] = vec4(encoded.r, 0.0, 0.0, 1.0); return; }
    if (uCloudPass == 1) { gl_FragData[0] = vec4(encoded.g, 0.0, 0.0, 1.0); return; }
    float depth = gl_DepthRange.near + (float(ndc) * 0.5 + 0.5) * gl_DepthRange.diff;
    gl_FragDepth = clamp(depth, min(gl_DepthRange.near, gl_DepthRange.far), max(gl_DepthRange.near, gl_DepthRange.far));
    dvec3 localPoint = uCloudLocalMode != 0 ? materialPoint : materialPoint + dvec3(double(uCloudMaterialXRelativeHiLo.x) + double(uCloudMaterialXRelativeHiLo.y),
            -double(uCloudObserverYZ.x), -double(uCloudObserverYZ.y));
    float transmission = cloudBoardTransmission(vec3(localPoint));
    float normalY = float(vCloudMaterialNormal.y);
    if (int(uCloudAtlasInfo.z)==1 && !gl_FrontFacing) normalY=-normalY;
    float face = 0.72 + 0.28 * max(normalY, 0.0)
            + (uCloudBottomBrightness - 0.72) * max(-normalY, 0.0);
    vec3 pointLocal = material.rgb * face * (0.08 + 0.92 * transmission) * (1.0 - 0.24 * uCloudRain);
    float grazing = 1.0 - clamp(abs(dot(normalize(vec3(vCloudPlane.xyz)), ray)), 0.0, 1.0);
    float haze = (0.10 + 0.32 * grazing * grazing) * (1.0-exp(-max(float(lambda)-2048.0,0.0)/16384.0));
    vec3 remote = mix(material.rgb, vec3(0.32, 0.49, 0.72), haze) * (1.0 - 0.22 * uCloudRain);
    gl_FragData[0] = vec4(mix(pointLocal, remote, cloudFarWeight(lambda)), 1.0);
    gl_FragData[1] = encoded;
}
