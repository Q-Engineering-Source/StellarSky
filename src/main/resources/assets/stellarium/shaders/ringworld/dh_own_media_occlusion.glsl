/* STELLARSKY_DH_3_2_0_B_OWN_MEDIA_OCCLUSION_BEGIN */
#extension GL_ARB_gpu_shader_fp64 : require
in vec3 vSSDisplayFromEye;
uniform int uSSOwnMediaEnabled;
uniform sampler2D uSSOwnMediaDistance;
uniform ivec2 uSSOwnMediaPixelOffset;

void ssDiscardBehindOwnMedia() {
    if (uSSOwnMediaEnabled == 0) return;
    ivec2 mediaPixel = ivec2(gl_FragCoord.xy) + uSSOwnMediaPixelOffset;
    vec2 encodedDistance = texelFetch(uSSOwnMediaDistance, mediaPixel, 0).rg;
    // R=0 is the immutable no-hit encoding. Do not substitute a far-plane distance.
    if (encodedDistance.r == 0.0) return;
    double mediaDistance = double(encodedDistance.r) + double(encodedDistance.g);
    double dhDistance = length(dvec3(vSSDisplayFromEye));
    // Preserve the already-rendered media on an exact distance tie.
    if (mediaDistance > 0.0LF && dhDistance >= mediaDistance) discard;
}
/* STELLARSKY_DH_3_2_0_B_OWN_MEDIA_OCCLUSION_END */
