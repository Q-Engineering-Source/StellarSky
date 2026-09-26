/* STELLARSKY_ACTINIUM_C25_CURVATURE_BEGIN */
#extension GL_ARB_gpu_shader_fp64 : require

// This snippet is inserted immediately after Actinium alpha-0.0.8's #version 330 core line.
// It is intentionally self-contained: Actinium owns its shader source, materials, lightmap,
// fog and projection. The appended local-light helper shares this physical coordinate bridge.
uniform int uSSCurvatureEnabled;
uniform vec2 uSSRadiusHiLo;
uniform vec2 uSSOriginYHiLo;
uniform vec3 uSSEyeRelative;
uniform mat4 uSSBaseView;
uniform mat4 uSSInverseBaseView;
uniform mat4 uSSNativeInverseView;

const double SS_PI = 3.1415926535897932384626433832795LF;
const double SS_HALF_PI = 1.5707963267948966192313216916398LF;
const double SS_TAU = 6.2831853071795864769252867665590LF;

double ssReducePi(double angle) {
    // Terrain is camera-bounded, but retain an explicit full-turn reduction before the
    // polynomial evaluation so diagnostic radii never route a large argument to float trig.
    double turns = floor((angle + SS_PI) / SS_TAU);
    return angle - turns * SS_TAU;
}

void ssSinCos(double angle, out double sine, out double cosine) {
    double reduced = ssReducePi(angle);
    double cosineSign = 1.0LF;
    if (reduced > SS_HALF_PI) {
        reduced = SS_PI - reduced;
        cosineSign = -1.0LF;
    } else if (reduced < -SS_HALF_PI) {
        reduced = -SS_PI - reduced;
        cosineSign = -1.0LF;
    }
    // The reduced interval is [-pi/2, pi/2].  These Taylor/Horner polynomials retain
    // double precision without relying on FP64 trig, which ARB_gpu_shader_fp64 does not add.
    double squared = reduced * reduced;
    // sin through x^21 and cos through x^22.  Horner form keeps all intermediate values
    // bounded on the reduced interval while retaining useful FP64 accuracy at pi/2.
    double sinPolynomial = -1.0LF / 51090942171709440000.0LF;
    sinPolynomial = 1.0LF / 121645100408832000.0LF + squared * sinPolynomial;
    sinPolynomial = -1.0LF / 355687428096000.0LF + squared * sinPolynomial;
    sinPolynomial = 1.0LF / 1307674368000.0LF + squared * sinPolynomial;
    sinPolynomial = -1.0LF / 6227020800.0LF + squared * sinPolynomial;
    sinPolynomial = 1.0LF / 39916800.0LF + squared * sinPolynomial;
    sinPolynomial = -1.0LF / 362880.0LF + squared * sinPolynomial;
    sinPolynomial = 1.0LF / 5040.0LF + squared * sinPolynomial;
    sinPolynomial = -1.0LF / 120.0LF + squared * sinPolynomial;
    sinPolynomial = 1.0LF / 6.0LF + squared * sinPolynomial;
    sine = reduced * (1.0LF - squared * sinPolynomial);

    double cosPolynomial = -1.0LF / 1124000727777607680000.0LF;
    cosPolynomial = 1.0LF / 2432902008176640000.0LF + squared * cosPolynomial;
    cosPolynomial = -1.0LF / 6402373705728000.0LF + squared * cosPolynomial;
    cosPolynomial = 1.0LF / 20922789888000.0LF + squared * cosPolynomial;
    cosPolynomial = -1.0LF / 87178291200.0LF + squared * cosPolynomial;
    cosPolynomial = 1.0LF / 479001600.0LF + squared * cosPolynomial;
    cosPolynomial = -1.0LF / 3628800.0LF + squared * cosPolynomial;
    cosPolynomial = 1.0LF / 40320.0LF + squared * cosPolynomial;
    cosPolynomial = -1.0LF / 720.0LF + squared * cosPolynomial;
    cosPolynomial = 1.0LF / 24.0LF + squared * cosPolynomial;
    cosPolynomial = -1.0LF / 2.0LF + squared * cosPolynomial;
    cosine = cosineSign * (1.0LF + squared * cosPolynomial);
}

double ssSinc(double angle, double sine) {
    if (abs(angle) < 0.0001LF) {
        double squared = angle * angle;
        return 1.0LF - squared / 6.0LF + squared * squared / 120.0LF
                - squared * squared * squared / 5040.0LF;
    }
    return sine / angle;
}

double ssVOverAngle(double angle, double v) {
    if (abs(angle) < 0.0001LF) {
        double squared = angle * angle;
        return angle * (0.5LF - squared / 24.0LF + squared * squared / 720.0LF
                - squared * squared * squared / 40320.0LF);
    }
    return v / angle;
}

vec3 ssApplyRingworldCurvature(vec3 nativePosition, mat4 nativeModelView) {
    if (uSSCurvatureEnabled == 0) {
        return nativePosition;
    }

    // Actinium positions are native-camera-relative (including its eye-height shift).  Recover
    // the frozen render-origin-relative physical point before applying the C25 display map.
    vec4 eye = nativeModelView * vec4(nativePosition, 1.0);
    vec3 physicalRenderRelative = (uSSInverseBaseView * eye).xyz;
    double radius = double(uSSRadiusHiLo.x) + double(uSSRadiusHiLo.y);
    double originY = double(uSSOriginYHiLo.x) + double(uSSOriginYHiLo.y);
    dvec3 physical = dvec3(physicalRenderRelative);
    dvec3 eyeRelative = dvec3(uSSEyeRelative);
    double arc = physical.x - eyeRelative.x;
    double angle = arc / radius;
    double sine;
    double cosine;
    ssSinCos(angle, sine, cosine);
    double halfSine;
    double ignoredHalfCosine;
    ssSinCos(angle * 0.5LF, halfSine, ignoredHalfCosine);
    double v = 2.0LF * halfSine * halfSine;
    double worldY = originY + physical.y;
    dvec3 bentRenderRelative = dvec3(
            arc * ssSinc(angle, sine) - worldY * sine,
            (physical.y - eyeRelative.y) + arc * ssVOverAngle(angle, v) - worldY * v,
            physical.z - eyeRelative.z) + eyeRelative;

    // The sole double-to-float conversion is the input to Actinium's native matrix bridge.
    return (uSSNativeInverseView * uSSBaseView * vec4(vec3(bentRenderRelative), 1.0)).xyz;
}
/* STELLARSKY_ACTINIUM_C25_CURVATURE_END */
