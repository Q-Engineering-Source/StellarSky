#version 400 compatibility

uniform vec3 uMeshOffset;
varying vec3 vRelativePosition;
varying vec3 vBaseColor;

void main() {
    vRelativePosition = gl_Vertex.xyz + uMeshOffset;
    vBaseColor = gl_Color.rgb;
    // VBO vertices retain their physical render-origin-relative coordinates:
    // cloud field semantics, face lighting, and LOD therefore stay in the
    // same domain as the atlas.  Only the final raster position is bent.
    vec3 displayPosition = vec3(ssCurvedDisplayPoint(dvec3(vRelativePosition)));
    gl_Position = gl_ModelViewProjectionMatrix * vec4(displayPosition, 1.0);
}
