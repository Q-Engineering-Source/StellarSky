/*
 * Shared inverse/forward display-ray mapping for the one-radius ringworld chart.
 *
 * This file follows the GLSL 4.00 compatibility version/macro preamble. It is not a
 * complete shader.  All positions returned by ssCurved*Point are relative to the frozen render
 * origin.  `ray` is a unit direction from the optical display origin, so its display point is
 * uSSEyeRelative + ray * lambda.  The only radius is uSSRadiusHiLo; it must be the same radius
 * used for the central-Sun geometry.
 *
 * The inverse chart ends at the cylinder axis.  Point and derivative functions return NaNs at that
 * singularity rather than silently selecting a longitude.  Query callers must therefore stop at
 * ssCurvedAxisLambda(ray), when it is finite.
 */
#extension GL_ARB_gpu_shader_fp64 : require

uniform int uSSCurvatureEnabled;
uniform vec2 uSSRadiusHiLo;
uniform vec2 uSSOriginYHiLo;
uniform vec3 uSSEyeRelative;

const double SS_CURVED_PI = 3.14159265358979323846264338327950288419716939937510LF;
const double SS_CURVED_HALF_PI = 1.57079632679489661923132169163975144209858469968755LF;
const double SS_CURVED_QUARTER_PI = 0.78539816339744830961566084581987572104929234984378LF;
const double SS_CURVED_TAU = 6.28318530717958647692528676655900576839433879875021LF;
const double SS_CURVED_TAN_PI_OVER_EIGHT = 0.41421356237309504880168872420969807856967187537695LF;
const double SS_CURVED_NO_AXIS = 1.797693134862315708145274237317043567981e308LF;

double ssCurvedRadius() {
    return double(uSSRadiusHiLo.x) + double(uSSRadiusHiLo.y);
}

double ssCurvedOriginY() {
    return double(uSSOriginYHiLo.x) + double(uSSOriginYHiLo.y);
}

dvec3 ssCurvedEyeRelative() {
    return dvec3(uSSEyeRelative);
}

double ssCurvedAxisInvalid() {
    // Runtime 0/0 creates a deliberate NaN for the undefined longitude at the cylinder axis.
    double zero = 0.0LF;
    return zero / zero;
}

double ssCurvedLength3(double x, double y, double z) {
    double scale = max(abs(x), max(abs(y), abs(z)));
    if (scale == 0.0LF) return 0.0LF;
    x /= scale;
    y /= scale;
    z /= scale;
    return scale * sqrt(x * x + y * y + z * z);
}

/* atan(t) on |t| <= tan(pi/8).  The alternating Taylor remainder is less than
 * |t|^57 / 57, below 2e-24 at the bound.  Range reduction therefore retains substantially better
 * than a millimetre after multiplication by the one-AU radius, without a FP64 trig builtin. */
double ssCurvedAtanReduced(double t) {
    double squared = t * t;
    // The same degree-55 series, evaluated with constant coefficients. Avoid
    // a loop-carried FP64 division for each term in every inverse-chart sample.
    double polynomial = -1.0LF / 55.0LF;
    polynomial = 1.0LF / 53.0LF + squared * polynomial;
    polynomial = -1.0LF / 51.0LF + squared * polynomial;
    polynomial = 1.0LF / 49.0LF + squared * polynomial;
    polynomial = -1.0LF / 47.0LF + squared * polynomial;
    polynomial = 1.0LF / 45.0LF + squared * polynomial;
    polynomial = -1.0LF / 43.0LF + squared * polynomial;
    polynomial = 1.0LF / 41.0LF + squared * polynomial;
    polynomial = -1.0LF / 39.0LF + squared * polynomial;
    polynomial = 1.0LF / 37.0LF + squared * polynomial;
    polynomial = -1.0LF / 35.0LF + squared * polynomial;
    polynomial = 1.0LF / 33.0LF + squared * polynomial;
    polynomial = -1.0LF / 31.0LF + squared * polynomial;
    polynomial = 1.0LF / 29.0LF + squared * polynomial;
    polynomial = -1.0LF / 27.0LF + squared * polynomial;
    polynomial = 1.0LF / 25.0LF + squared * polynomial;
    polynomial = -1.0LF / 23.0LF + squared * polynomial;
    polynomial = 1.0LF / 21.0LF + squared * polynomial;
    polynomial = -1.0LF / 19.0LF + squared * polynomial;
    polynomial = 1.0LF / 17.0LF + squared * polynomial;
    polynomial = -1.0LF / 15.0LF + squared * polynomial;
    polynomial = 1.0LF / 13.0LF + squared * polynomial;
    polynomial = -1.0LF / 11.0LF + squared * polynomial;
    polynomial = 1.0LF / 9.0LF + squared * polynomial;
    polynomial = -1.0LF / 7.0LF + squared * polynomial;
    polynomial = 1.0LF / 5.0LF + squared * polynomial;
    polynomial = -1.0LF / 3.0LF + squared * polynomial;
    return t * (1.0LF + squared * polynomial);
}

/* Robust FP64 atan2 assembled from the reduced series above.  It deliberately has no undefined
 * (0, 0) fallback: callers test the cylinder axis before asking for longitude. */
double ssCurvedAtan2(double y, double x) {
    double ay = abs(y);
    double ax = abs(x);
    if (ax == 0.0LF) {
        if (ay == 0.0LF) return ssCurvedAxisInvalid();
        return y > 0.0LF ? SS_CURVED_HALF_PI : -SS_CURVED_HALF_PI;
    }

    double ratio = ay / ax;
    double base;
    if (ratio > 1.0LF) {
        // The reciprocal can still be close to one (for example ratio=1.01), so it needs the
        // same pi/4 reduction before the small-interval polynomial.  Do not send it directly to
        // ssCurvedAtanReduced unless it is within tan(pi/8).
        double reciprocal = 1.0LF / ratio;
        if (reciprocal > SS_CURVED_TAN_PI_OVER_EIGHT) {
            base = SS_CURVED_HALF_PI - SS_CURVED_QUARTER_PI
                    - ssCurvedAtanReduced((reciprocal - 1.0LF) / (reciprocal + 1.0LF));
        } else {
            base = SS_CURVED_HALF_PI - ssCurvedAtanReduced(reciprocal);
        }
    } else if (ratio > SS_CURVED_TAN_PI_OVER_EIGHT) {
        base = SS_CURVED_QUARTER_PI + ssCurvedAtanReduced((ratio - 1.0LF) / (ratio + 1.0LF));
    } else {
        base = ssCurvedAtanReduced(ratio);
    }
    if (x < 0.0LF) base = SS_CURVED_PI - base;
    return y < 0.0LF ? -base : base;
}

void ssCurvedSinCos(double angle, out double sine, out double cosine) {
    // The shader never calls GL's float trig.  Folding to [-pi/2, pi/2] keeps every polynomial
    // intermediate bounded; orders 21/22 leave a below-double-rounding residual at pi/2.
    double turns = floor((angle + SS_CURVED_PI) / SS_CURVED_TAU);
    double reduced = angle - turns * SS_CURVED_TAU;
    double cosineSign = 1.0LF;
    if (reduced > SS_CURVED_HALF_PI) {
        reduced = SS_CURVED_PI - reduced;
        cosineSign = -1.0LF;
    } else if (reduced < -SS_CURVED_HALF_PI) {
        reduced = -SS_CURVED_PI - reduced;
        cosineSign = -1.0LF;
    }
    double squared = reduced * reduced;
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

double ssCurvedSinc(double angle, double sine) {
    if (abs(angle) < 0.0001LF) {
        double squared = angle * angle;
        return 1.0LF - squared / 6.0LF + squared * squared / 120.0LF
                - squared * squared * squared / 5040.0LF;
    }
    return sine / angle;
}

double ssCurvedVOverAngle(double angle, double v) {
    if (abs(angle) < 0.0001LF) {
        double squared = angle * angle;
        return angle * (0.5LF - squared / 24.0LF + squared * squared / 720.0LF
                - squared * squared * squared / 40320.0LF);
    }
    return v / angle;
}

/* Maps a physical render-origin-relative point into the frozen display frame. */
dvec3 ssCurvedDisplayPoint(dvec3 physicalRelative) {
    if (uSSCurvatureEnabled == 0) return physicalRelative;
    double radius = ssCurvedRadius();
    dvec3 eye = ssCurvedEyeRelative();
    double arc = physicalRelative.x - eye.x;
    double angle = arc / radius;
    double sine;
    double cosineIgnored;
    ssCurvedSinCos(angle, sine, cosineIgnored);
    double halfSine;
    double halfCosineIgnored;
    ssCurvedSinCos(angle * 0.5LF, halfSine, halfCosineIgnored);
    double v = 2.0LF * halfSine * halfSine;
    double worldY = ssCurvedOriginY() + physicalRelative.y;
    return dvec3(arc * ssCurvedSinc(angle, sine) - worldY * sine + eye.x,
            physicalRelative.y + arc * ssCurvedVOverAngle(angle, v) - worldY * v,
            physicalRelative.z);
}

/* FP64 inverse used by the ray overload as well as the public float-display API. */
dvec3 ssCurvedPhysicalPointD(dvec3 display) {
    if (uSSCurvatureEnabled == 0) return display;
    double radius = ssCurvedRadius();
    double originY = ssCurvedOriginY();
    dvec3 eye = ssCurvedEyeRelative();
    double qx = display.x - eye.x;
    double qy = display.y - eye.y;
    double radialEyeDistance = radius - (originY + eye.y);
    double b = radialEyeDistance - qy;
    double radial = sqrt(qx * qx + b * b);
    if (radial == 0.0LF) return dvec3(ssCurvedAxisInvalid());

    double physicalY;
    if (b >= 0.0LF) {
        // R-r = (R-b) - qx^2/(r+b); use the half quotient to avoid squaring a large qx.
        double halfDenominator = radial * 0.5LF + b * 0.5LF;
        physicalY = eye.y + qy - qx * ((qx * 0.5LF) / halfDenominator);
    } else {
        // Around the antipode r+b is ill-conditioned, so use R-r = R+b-qx^2/(r-b).
        double halfDenominator = radial * 0.5LF - b * 0.5LF;
        physicalY = radius + b - qx * ((qx * 0.5LF) / halfDenominator) - originY;
    }
    double angle = ssCurvedAtan2(qx, b);
    if (angle == SS_CURVED_PI) angle = -SS_CURVED_PI;
    return dvec3(eye.x + angle * radius, physicalY, display.z);
}

/* Maps a float display render-origin-relative point into physical coordinates.  At the cylinder
 * axis longitude is undefined and this returns a NaN vector; see ssCurvedAxisLambda. */
dvec3 ssCurvedPhysicalPoint(vec3 displayRelative) {
    return ssCurvedPhysicalPointD(dvec3(displayRelative));
}

dvec3 ssCurvedRayPoint(vec3 unitDisplayRay, double lambda) {
    // Keep the generated display sample in FP64.  Converting a horizon-scale lambda to vec3 here
    // would quantize it by kilometres before the stable inverse can recover its physical point.
    return ssCurvedPhysicalPointD(ssCurvedEyeRelative() + dvec3(unitDisplayRay) * lambda);
}

/* Positive finite distance to the singular cylinder axis, or SS_CURVED_NO_AXIS for a ray that
 * does not exactly pass through it. */
double ssCurvedAxisLambda(vec3 unitDisplayRay) {
    if (uSSCurvatureEnabled == 0 || unitDisplayRay.x != 0.0 || unitDisplayRay.y <= 0.0) {
        return SS_CURVED_NO_AXIS;
    }
    return (ssCurvedRadius() - (ssCurvedOriginY() + double(uSSEyeRelative.y))) / double(unitDisplayRay.y);
}

/* d physicalPoint / d lambda.  It returns NaNs at the same axis singularity as rayPoint. */
dvec3 ssCurvedRayDerivative(vec3 unitDisplayRay, double lambda) {
    if (uSSCurvatureEnabled == 0) return dvec3(unitDisplayRay);
    double dx = double(unitDisplayRay.x);
    double dy = double(unitDisplayRay.y);
    double dz = double(unitDisplayRay.z);
    double radialEyeDistance = ssCurvedRadius() - (ssCurvedOriginY() + double(uSSEyeRelative.y));
    double qx = dx * lambda;
    double b = radialEyeDistance - dy * lambda;
    double radialSquared = qx * qx + b * b;
    if (radialSquared == 0.0LF) return dvec3(ssCurvedAxisInvalid());
    double radial = sqrt(radialSquared);
    return dvec3(ssCurvedRadius() * (b * dx + qx * dy) / radialSquared,
            (b * dy - qx * dx) / radial, dz);
}

double ssCurvedPathWeight(vec3 unitDisplayRay, double lambda) {
    dvec3 derivative = ssCurvedRayDerivative(unitDisplayRay, lambda);
    return ssCurvedLength3(derivative.x, derivative.y, derivative.z);
}

/* Returns 2 for two sorted real roots, 1 for an exact tangent, 0 for no real roots, and -1 for
 * a ray coplanar with the requested physical-height cylinder.  roots is (0,0) for 0/-1 and
 * (t,t) for a tangent.  This function deliberately does not reject negative roots or roots at/
 * beyond ssCurvedAxisLambda: callers select their positive integration interval explicitly. */
int ssCurvedHeightRoots(vec3 ray, double worldY, out dvec2 roots) {
    roots = dvec2(0.0LF);
    if (uSSCurvatureEnabled == 0) {
        if (double(ray.y) == 0.0LF) return worldY == ssCurvedOriginY() + double(uSSEyeRelative.y) ? -1 : 0;
        double root = (worldY - (ssCurvedOriginY() + double(uSSEyeRelative.y))) / double(ray.y);
        roots = dvec2(root, root);
        return 1;
    }
    double radius = ssCurvedRadius();
    double eyeY = ssCurvedOriginY() + double(uSSEyeRelative.y);
    double dx = double(ray.x);
    double dy = double(ray.y);
    double a = dx * dx + dy * dy;
    if (a == 0.0LF) return worldY == eyeY ? -1 : 0;
    double radialEyeDistance = radius - eyeY;
    double b = -2.0LF * radialEyeDistance * dy;
    // c=(A-r)(A+r), never R^2-(R-worldY)^2: this is cancellation-free near the ground.
    double c = (worldY - eyeY) * (2.0LF * radius - eyeY - worldY);
    double discriminant = b * b - 4.0LF * a * c;
    if (discriminant < 0.0LF) return 0;
    if (discriminant == 0.0LF) {
        double root = -b / (2.0LF * a);
        roots = dvec2(root, root);
        return 1;
    }
    double squareRoot = sqrt(discriminant);
    double q = -0.5LF * (b + (b >= 0.0LF ? squareRoot : -squareRoot));
    double first;
    double second;
    if (q == 0.0LF) {
        first = (-b - squareRoot) / (2.0LF * a);
        second = (-b + squareRoot) / (2.0LF * a);
    } else {
        first = q / a;
        second = c / q;
    }
    roots = dvec2(min(first, second), max(first, second));
    return 2;
}

/* Returns a positive physical-X plane hit on the observer-centred canonical longitude branch.
 * -1 means no crossing, coplanar ray, or the antipodal angular-plane branch.  A negative result
 * other than -1 is -axisLambda: the requested crossing is at/beyond the singular cylinder axis
 * and must terminate the ray instead of being used as a hit. */
double ssCurvedLongitudeHit(vec3 ray, double physicalXRelative) {
    if (uSSCurvatureEnabled == 0) {
        if (double(ray.x) == 0.0LF) return -1.0LF;
        double hit = (physicalXRelative - double(uSSEyeRelative.x)) / double(ray.x);
        return hit > 0.0LF ? hit : -1.0LF;
    }
    double radius = ssCurvedRadius();
    double arc = physicalXRelative - double(uSSEyeRelative.x);
    double circumference = SS_CURVED_TAU * radius;
    arc -= floor((arc + SS_CURVED_PI * radius) / circumference) * circumference;
    if (arc >= SS_CURVED_PI * radius) arc -= circumference;
    double sine;
    double cosine;
    ssCurvedSinCos(arc / radius, sine, cosine);
    double denominator = double(ray.x) * cosine + double(ray.y) * sine;
    double numerator = (radius - (ssCurvedOriginY() + double(uSSEyeRelative.y))) * sine;
    if (denominator == 0.0LF) return -1.0LF;
    double hit = numerator / denominator;
    if (!(hit > 0.0LF)) return -1.0LF;
    double axis = ssCurvedAxisLambda(ray);
    if (hit >= axis) return -axis;
    double qx = double(ray.x) * hit;
    double b = (radius - (ssCurvedOriginY() + double(uSSEyeRelative.y))) - double(ray.y) * hit;
    return qx * sine + b * cosine > 0.0LF ? hit : -1.0LF;
}

/* Physical Z is preserved by the cylinder map.  Return semantics match ssCurvedLongitudeHit:
 * positive is a usable forward crossing, -1 is miss/coplanar/backward, and -axisLambda marks a
 * crossing that is beyond the undefined cylinder axis. */
double ssCurvedZHit(vec3 ray, double physicalZRelative) {
    if (double(ray.z) == 0.0LF) return -1.0LF;
    double hit = (physicalZRelative - double(uSSEyeRelative.z)) / double(ray.z);
    if (!(hit > 0.0LF)) return -1.0LF;
    double axis = ssCurvedAxisLambda(ray);
    return hit >= axis ? -axis : hit;
}
