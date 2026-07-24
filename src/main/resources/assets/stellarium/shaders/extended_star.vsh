#version 120
#extension GL_ARB_point_sprite : enable

varying vec3 starColor;
varying float starIntensity;
uniform float brightnessScale;
uniform float pixelScale;

void main() {
	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
	starColor = gl_Color.rgb;
	starIntensity = clamp(gl_Color.a * brightnessScale, 0.0, 4.0);
	gl_PointSize = clamp(pixelScale * (1.0 + 1.15 * sqrt(starIntensity)), 1.0, 5.0);
}
