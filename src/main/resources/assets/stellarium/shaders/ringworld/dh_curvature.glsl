/* STELLARSKY_DH_3_2_0_B_CURVATURE_BEGIN */
// DH builds vertexWorldPos as exact-camera-relative (vPosition + uModelOffset).  Its camera can
// differ from the SS optical eye in third-person views, so use the separately captured native DH
// camera offset to bridge to render-origin space.  The base DH Earth curve is disabled per draw.
uniform vec3 uSSDhCameraOffset;
uniform int uSSDhLightMode;
out vec3 vSSDhPhysicalRelative;
out vec2 vSSDhRawLight;

void ssApplyDistantHorizonsCurvature(inout vec3 vertexWorldPos) {
    if (uSSCurvatureEnabled == 0) return;
    dvec3 physicalRenderRelative = dvec3(vertexWorldPos) + dvec3(uSSDhCameraOffset);
    dvec3 displayRenderRelative = ssCurvedDisplayPoint(physicalRenderRelative);
    vertexWorldPos = vec3(displayRenderRelative - dvec3(uSSDhCameraOffset));
}
/* STELLARSKY_DH_3_2_0_B_CURVATURE_END */
