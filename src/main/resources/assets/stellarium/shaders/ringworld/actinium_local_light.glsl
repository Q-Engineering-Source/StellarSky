uniform int uSSDhLightMode;
uniform mat4 uSSLightInverseView;
uniform vec4 uSSDhBandHigh;
uniform vec4 uSSDhBandLow;
uniform vec4 uSSDhBoundsHigh;
uniform vec4 uSSDhBoundsLow;
uniform vec4 uSSDhDirectionHigh;
uniform vec4 uSSDhDirectionLow;

ivec2 ssActiniumLocalLight(ivec2 rawLight, vec3 nativePosition, mat4 nativeView) {
    if (uSSDhLightMode == 0) return rawLight;
    // Same native-camera to physical render-origin bridge as the curvature mapping.
    dvec3 p = dvec3((uSSLightInverseView * nativeView * vec4(nativePosition, 1.0)).xyz);
    dvec4 b = dvec4(uSSDhBandHigh) + dvec4(uSSDhBandLow);
    dvec4 h = dvec4(uSSDhBoundsHigh) + dvec4(uSSDhBoundsLow);
    dvec4 d = dvec4(uSSDhDirectionHigh) + dvec4(uSSDhDirectionLow);
    double subtraction = ssDhSkySubtraction(uSSDhLightMode, p.x,p.y,p.z,
            b.x,b.y,b.z,b.w, h.x,h.y,h.z,h.w, d.x,d.y,d.z,d.w);
    // Native Actinium uses 0..240 light coordinates, BLOCK on X and SKY on Y.
    return ivec2(rawLight.x, max(0, rawLight.y - 16 * int(subtraction)));
}
