// DH owns a separate depth texture; its color composite does not populate Minecraft depth.
uniform int uSSDhDepthActive;
uniform sampler2D uSSDhDepth;
uniform mat4 uSSDhInverseViewProjection;
uniform vec4 uSSDhViewport;
uniform vec3 uSSDhCameraOffset;

double ssDistantOpaqueLimitD(vec3 unitDisplayRay) {
    if (uSSDhDepthActive == 0) return 1.0e15LF;
    vec2 uv = (gl_FragCoord.xy - uSSDhViewport.xy) / uSSDhViewport.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return 1.0e15LF;
    float depth = texture(uSSDhDepth, uv).r;
    if (depth >= 1.0) return 1.0e15LF;
    dvec4 position = dmat4(uSSDhInverseViewProjection) * dvec4(dvec2(uv) * 2.0LF - 1.0LF,
            double(depth) * 2.0LF - 1.0LF, 1.0LF);
    if (position.w == 0.0) return 1.0e15LF;
    dvec3 fromEye = dvec3(position.xyz) / double(position.w)
            + dvec3(uSSDhCameraOffset) - dvec3(uSSEyeRelative);
    double lambda = dot(fromEye, dvec3(unitDisplayRay));
    return lambda > 0.0LF ? lambda : 1.0e15LF;
}

float ssDistantOpaqueLimit(vec3 unitDisplayRay) { return float(ssDistantOpaqueLimitD(unitDisplayRay)); }
