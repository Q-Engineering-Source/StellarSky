#version 120

varying vec2 imageTexCoord;
varying vec4 imageColor;

void main() {
	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
	imageTexCoord = gl_MultiTexCoord0.st;
	imageColor = gl_Color;
}
