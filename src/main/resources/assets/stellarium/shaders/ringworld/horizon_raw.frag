#version 400 compatibility

uniform mat4 uInverseProjection;
uniform mat4 uInverseModelView;
uniform vec3 uCameraRelative;

#include "cloud_query.glsl"

varying vec2 vUv;

void main() {
    vec4 farView = uInverseProjection * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewDirection = normalize(farView.xyz / farView.w);
    vec3 ray = normalize((uInverseModelView * vec4(viewDirection, 0.0)).xyz);
    vec3 normal;
    vec3 baseColor;
    float censored;
    float cloudT = ssCloudTrace(uCameraRelative, ray, normal, baseColor, censored);
    // Location zero is a colour output to Actinium's compatibility transformer:
    // its injected alpha test discards our negative miss sentinel (or a near hit).
    // Route data locations 1/2 to private attachments 0/1, without colour alpha semantics.
    gl_FragData[1] = cloudT >= 0.0 ? vec4(normal, cloudT) : vec4(0.0, 0.0, 0.0, -1.0);
    gl_FragData[2] = cloudT >= 0.0 ? vec4(baseColor, censored) : vec4(0.0);
}
