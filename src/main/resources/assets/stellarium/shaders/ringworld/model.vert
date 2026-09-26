#version 400 compatibility

uniform vec4 uModelRotation;
uniform vec4 uModelOriginYZ;
uniform vec2 uModelOriginX;
uniform mat4 uModelClipProjection;
layout(location = 0) in vec3 aPositionHigh;
layout(location = 1) in vec3 aPositionLow;
layout(location = 2) in vec4 aPlaneHigh;
layout(location = 3) in vec4 aPlaneLow;
layout(location = 4) in vec2 aUv;
flat out dvec4 vModelPlane;
out vec2 vModelUv;
uniform int uModelLayer;
uniform int uModelTerrainLevel;
out vec3 vModelPhysical;

void main() {
    if(uModelLayer==3 && uModelTerrainLevel<8) {
        // Near terrain is deliberately planar: no cylinder transform or inverse chart.
        dvec3 p=dvec3(aPositionHigh)+dvec3(aPositionLow);
        dvec3 origin=dvec3(double(uModelOriginX.x)+double(uModelOriginX.y),
                double(uModelOriginYZ.x)+double(uModelOriginYZ.y),double(uModelOriginYZ.z)+double(uModelOriginYZ.w));
        dvec3 displayed=p-origin;
        dvec4 plane=dvec4(aPlaneHigh)+dvec4(aPlaneLow);
        vModelPlane=dvec4(plane.xyz,plane.w+dot(plane.xyz,origin));
        gl_Position=vec4(dmat4(uModelClipProjection)*dmat4(gl_ModelViewMatrix)*dvec4(displayed,1.0LF));
        vModelUv=aUv;
        vModelPhysical=vec3(displayed);
        return;
    }
    double c = double(uModelRotation.x) + double(uModelRotation.y);
    double s = double(uModelRotation.z) + double(uModelRotation.w);
    double radius = ssCurvedRadius();
    double oy = double(uModelOriginYZ.x) + double(uModelOriginYZ.y);
    double oz = double(uModelOriginYZ.z) + double(uModelOriginYZ.w);
    dvec3 p = dvec3(aPositionHigh) + dvec3(aPositionLow);
    dvec3 displayed = dvec3(c*p.x+s*(p.y-radius)+double(uSSEyeRelative.x),
            -s*p.x+c*(p.y-radius)+radius-oy, p.z-oz);
    dvec4 plane = dvec4(aPlaneHigh) + dvec4(aPlaneLow);
    dvec3 n = dvec3(c*plane.x+s*plane.y, -s*plane.x+c*plane.y, plane.z);
    dvec3 b = dvec3(-s*radius+double(uSSEyeRelative.x), radius-c*radius-oy, -oz);
    // This mesh stores N.P+d=0 (unlike board_mesh's N.P=d).
    vModelPlane = dvec4(n, plane.w-dot(n,b));
    gl_Position = vec4(dmat4(uModelClipProjection)*dmat4(gl_ModelViewMatrix)*dvec4(displayed,1.0LF));
    vModelUv = aUv;
    vModelPhysical=uModelLayer==3?vec3(ssCurvedPhysicalPointD(displayed)):vec3(0.0);
}
