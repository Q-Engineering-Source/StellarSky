// Raw trace variant: cache-target viewport starts at (0,0), while rank must
// remain anchored to the caller's original framebuffer pixel.
uniform vec2 uCloudRawPixelOffset;
uniform vec3 uCloudTransitionWidths;

float ssCloudPixelRank() {
    vec2 pixel = mod(floor(gl_FragCoord.xy + uCloudRawPixelOffset), 8.0);
    float rank = 0.0;
    for (int bit = 0; bit < 3; bit++) {
        vec2 digit = mod(pixel, 2.0);
        rank = 4.0 * rank + 2.0 * mod(digit.x + digit.y, 2.0) + digit.y;
        pixel = floor(pixel * 0.5);
    }
    return (rank + 0.5) / 64.0;
}

vec3 ssCloudTransitionBoundaries() {
    float offset = -sin(asin(1.0 - 2.0 * ssCloudPixelRank()) / 3.0);
    return vec3(float(SS_CLOUD_LOD_FINE_3D_END), float(SS_CLOUD_LOD_MID_3D_END),
            float(SS_CLOUD_LOD_LOW_3D_END)) + uCloudTransitionWidths * offset;
}
