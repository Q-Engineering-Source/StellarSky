// The composite shades a previously traced normal. It must not pull in the
// cloud tracer or its per-pixel transition/rank inputs.
uniform float uCloudBottomBrightness;

float ssCloudFaceBrightness(vec3 normal) {
    return 0.72 + 0.28 * max(normal.y, 0.0)
            + (uCloudBottomBrightness - 0.72) * max(-normal.y, 0.0);
}
