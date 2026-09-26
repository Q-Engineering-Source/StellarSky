// Written by the actual opaque media fragment, after the same discard/depth decisions as color.
// RG=(high, residual) retains distance beyond Minecraft's clamped far-plane depth. RG=(0,0) is empty.
vec4 ssEncodeOwnMediaDistance(double lambda) {
    float high = float(lambda);
    return vec4(high, float(lambda - double(high)), 0.0, 0.0);
}

uniform int uSSOwnMediaDepthActive;
uniform sampler2D uSSOwnMediaDepth;
uniform ivec2 uSSOwnMediaDepthPixelOffset;

double ssOwnMediaOpaqueLimit() {
    if (uSSOwnMediaDepthActive == 0) return 1.0e15LF;
    vec2 encoded = texelFetch(uSSOwnMediaDepth,
            ivec2(gl_FragCoord.xy) + uSSOwnMediaDepthPixelOffset, 0).rg;
    return encoded.x > 0.0 ? double(encoded.x) + double(encoded.y) : 1.0e15LF;
}
