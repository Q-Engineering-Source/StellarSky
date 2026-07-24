#version 120
#extension GL_ARB_point_sprite : enable

varying vec3 objectColor;
uniform float pixelsPerDegree;

void main() {
	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
	objectColor = gl_Color.rgb;
	gl_PointSize = clamp(gl_Color.a * pixelsPerDegree, 1.25, 72.0);
}
