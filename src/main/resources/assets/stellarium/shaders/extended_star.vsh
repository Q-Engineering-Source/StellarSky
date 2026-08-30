#version 120
#extension GL_ARB_point_sprite : enable

varying vec3 starColor;
varying float starIntensity;
uniform float brightnessScale;
uniform float pixelScale;
uniform float epochYears;
uniform float magnitudeLimit;
uniform float twinkleAmount;
uniform float animationTime;
uniform vec3 zenithDirection;

float randomValue(float seed) {
	return fract(sin(seed) * 43758.5453123);
}

float smoothRandom(float seed, float time) {
	float sampleIndex = floor(time);
	float amount = smoothstep(0.0, 1.0, fract(time));
	float current = randomValue(seed + sampleIndex * 17.0);
	float next = randomValue(seed + (sampleIndex + 1.0) * 17.0);
	return mix(current, next, amount);
}

void main() {
	vec3 propagatedPosition = gl_Vertex.xyz + gl_MultiTexCoord0.xyz * epochYears;
	gl_Position = gl_ModelViewProjectionMatrix * vec4(propagatedPosition, 1.0);
	starColor = gl_Color.rgb;
	float magnitude = gl_Color.a * 16.0 - 2.0;
	float limitFade = 1.0 - smoothstep(magnitudeLimit - 0.65,
			magnitudeLimit, magnitude);
	if (limitFade <= 0.0) {
		gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
		starColor = vec3(0.0);
		starIntensity = 0.0;
		gl_PointSize = 1.0;
		return;
	}
	float flux = pow(10.0, -0.4 * (magnitude + 0.5));
	float adaptedCore = max(0.05, 0.62 * sqrt(flux));
	float baseIntensity = clamp(adaptedCore * brightnessScale * limitFade,
			0.0, 4.0);
	float twinkleBrightness = 1.0;
	if (twinkleAmount > 0.0) {
		float sinAltitude = dot(normalize(propagatedPosition),
				normalize(zenithDirection));
		// Stellarium keeps 10% twinkle at zenith and reaches full strength
		// toward the horizon.
		float altitudeFactor = clamp(1.0 - 0.9 * sinAltitude, 0.1, 1.0);
		float seed = dot(normalize(gl_Vertex.xyz),
				vec3(127.1, 311.7, 74.7));
		twinkleBrightness = 1.0 - altitudeFactor * twinkleAmount
				* smoothRandom(seed, animationTime * 8.0);
	}
	starIntensity = baseIntensity * twinkleBrightness;
	// Stellarium pins sub-pixel sources to a stable visible footprint and
	// compensates their flux instead of allowing them to blink out.
	gl_PointSize = clamp(pixelScale * (2.0 + 1.8 * sqrt(baseIntensity)),
			2.0, 7.0);
}
