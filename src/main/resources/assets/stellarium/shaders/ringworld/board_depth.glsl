uniform int uSSBoardMeshDepthActive;
uniform sampler2D uSSBoardMeshHigh;
uniform sampler2D uSSBoardMeshLow;
uniform ivec2 uSSBoardMeshPixelOffset;

bool ssSelectedBoardPrecedes(double candidate) {
    if (uSSBoardMeshDepthActive == 0) return false;
    ivec2 pixel = ivec2(gl_FragCoord.xy) - uSSBoardMeshPixelOffset;
    float high = texelFetch(uSSBoardMeshHigh, pixel, 0).r;
    if (high >= 1.0e29) return false;
    vec2 key = ssEncodeOwnMediaDistance(candidate).rg;
    if (high != key.x) return high < key.x;
    return texelFetch(uSSBoardMeshLow, pixel, 0).r <= key.y;
}
