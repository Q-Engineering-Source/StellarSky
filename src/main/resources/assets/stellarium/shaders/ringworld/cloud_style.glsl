// Same framebuffer pixel, frozen widths and opaque ownership in all three passes.
uniform vec3 uCloudTransitionWidths;
uniform float uCloudBottomBrightness;

float ssCloudPixelRank() {
    vec2 pixel = mod(floor(gl_FragCoord.xy), 8.0);
    float rank = 0.0;
    for (int bit = 0; bit < 3; bit++) {
        vec2 digit = mod(pixel, 2.0);
        rank = 4.0 * rank + 2.0 * mod(digit.x + digit.y, 2.0) + digit.y;
        pixel = floor(pixel * 0.5);
    }
    return (rank + 0.5) / 64.0;
}

vec3 ssCloudTransitionBoundaries() {
    // A single shifted boundary per pixel is equivalent to complementary
    // smoothstep coverage. No per-frame RNG, second ray or blended depth.
    float offset = -sin(asin(1.0 - 2.0 * ssCloudPixelRank()) / 3.0);
    return vec3(float(SS_CLOUD_LOD_FINE_3D_END), float(SS_CLOUD_LOD_MID_3D_END),
            float(SS_CLOUD_LOD_LOW_3D_END)) + uCloudTransitionWidths * offset;
}

float ssCloudFaceBrightness(vec3 normal) {
    return 0.72 + 0.28 * max(normal.y, 0.0)
            + (uCloudBottomBrightness - 0.72) * max(-normal.y, 0.0);
}
