#version 120
#extension GL_ARB_point_sprite : enable

varying vec3 starColor;
varying float starIntensity;
uniform float brightnessScale;
uniform float pixelScale;
uniform float epochYears;

void main() {
	vec3 propagatedPosition = gl_Vertex.xyz + gl_MultiTexCoord1.xyz * epochYears;
	gl_Position = gl_ModelViewProjectionMatrix * vec4(propagatedPosition, 1.0);
	starColor = gl_Color.rgb;
	starIntensity = clamp(gl_Color.a * brightnessScale, 0.0, 4.0);
	gl_PointSize = clamp(pixelScale * (1.0 + 1.15 * sqrt(starIntensity)), 1.0, 5.0);
}
