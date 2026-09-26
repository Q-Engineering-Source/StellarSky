#version 400 compatibility

uniform vec4 uMeshOriginYZ;
uniform mat4 uMeshClipProjection;
layout(location = 0) in vec3 aMeshPositionHigh;
layout(location = 1) in vec3 aMeshPositionLow;
layout(location = 2) in vec4 aMeshPlaneHigh;
layout(location = 3) in vec4 aMeshPlaneLow;
layout(location = 4) in vec3 aMeshPhysicalLocal;
layout(location = 5) in vec3 aMeshTileFace;
flat out dvec4 vMeshPlane;
flat out dvec3 vMeshTileFace;
out vec3 vMeshPhysicalLocal;

void main() {
    // Only tile-local material coordinates interpolate. Global positions and the
    // triangle plane retain their CPU double precision through high/residual pairs.
    dvec3 basePosition = dvec3(aMeshPositionHigh) + dvec3(aMeshPositionLow);
    double originY = double(uMeshOriginYZ.x) + double(uMeshOriginYZ.y);
    double originZ = double(uMeshOriginYZ.z) + double(uMeshOriginYZ.w);
    dvec3 displayed = basePosition + dvec3(double(uSSEyeRelative.x), -originY, -originZ);
    dvec4 clip = dmat4(uMeshClipProjection) * dmat4(gl_ModelViewMatrix) * dvec4(displayed, 1.0LF);
    // Use an infinite far plane only for primitive clipping. The near-plane
    // expression z+w is multiplied by a positive constant, so crossing triangles
    // retain exact homogeneous near clipping, including behind-eye vertices.
    // The fragment still writes the caller's original finite-projection depth.
    gl_Position = vec4(clip);
    vMeshPlane = dvec4(aMeshPlaneHigh) + dvec4(aMeshPlaneLow);
    vMeshTileFace = dvec3(double(aMeshTileFace.x) + double(aMeshTileFace.y),
            double(aMeshTileFace.z), 0.0LF);
    vMeshPhysicalLocal = aMeshPhysicalLocal;
}
