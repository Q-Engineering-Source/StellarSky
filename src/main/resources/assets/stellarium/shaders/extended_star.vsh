#version 120
#extension GL_ARB_point_sprite : enable

varying vec3 starColor;
varying float starIntensity;
uniform float brightnessScale;
uniform float pixelScale;

void main() {
	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
	starColor = gl_Color.rgb;
	starIntensity = gl_Color.a * brightnessScale;
	gl_PointSize = clamp(pixelScale * (1.0 + 2.0 * sqrt(max(starIntensity, 0.0))), 1.0, 9.0);
}
