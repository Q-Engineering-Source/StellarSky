#version 120

varying vec2 objectTexCoord;
varying vec4 objectColor;

void main() {
	gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
	objectTexCoord = gl_MultiTexCoord0.st;
	objectColor = gl_Color;
}
