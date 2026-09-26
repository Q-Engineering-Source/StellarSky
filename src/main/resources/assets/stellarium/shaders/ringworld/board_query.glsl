// Curved analytic material query shared by the board depth writer, horizon and
// spatial-air passes.  The including shader must provide these small accessors:
//   int ssBoardCoverage();
//   dvec2 ssBoardVerticalBounds();     // [lower, upper), physical Y
//   dvec2 ssBoardStripBounds();        // [minZ, maxZ), physical Z
//   dvec4 ssBoardBandSpec();           // heading X/Z, spacing, panel width
//   dvec2 ssBoardEdgeSpec();           // edge relative, orientation
// All coordinates are physical render-origin-relative coordinates.  The
// display ray is unit length and lambda is a positive display-space distance.

const double SS_BOARD_NO_HIT = -1.0LF;
const int SS_BOARD_FACE_SIDE = 0;
const int SS_BOARD_FACE_TOP = 1;
const int SS_BOARD_FACE_UNDERSIDE = 2;
const int SS_BOARD_FACE_NEGATIVE_STRIP_SIDE = 3;
const int SS_BOARD_FACE_PANEL_LEADING_SIDE = 4;
const int SS_BOARD_FACE_POSITIVE_STRIP_SIDE = 5;
const int SS_BOARD_FACE_PANEL_TRAILING_SIDE = 6;
const int SS_BOARD_MAX_BREAKS = 10;
const int SS_BOARD_BISECTION_STEPS = 44;

double ssBoardPositiveModulo(double value, double period) {
    double result = mod(value, period);
    return result < 0.0LF ? result + period : result;
}

bool ssBoardMaterialAt(double projection) {
    if (ssBoardCoverage() == 2) return true;
    if (ssBoardCoverage() != 1) return false;
    dvec4 bands = ssBoardBandSpec();
    dvec2 edge = ssBoardEdgeSpec();
    double position = ssBoardPositiveModulo(edge.y * (projection - edge.x), bands.z);
    return position <= bands.w;
}

double ssBoardProjection(dvec3 point) {
    dvec4 bands = ssBoardBandSpec();
    return dvec2(point.x, point.z).x * bands.x + dvec2(point.x, point.z).y * bands.y;
}

double ssBoardOrientedProjection(vec3 ray, double lambda) {
    dvec2 edge = ssBoardEdgeSpec();
    return edge.y * (ssBoardProjection(ssCurvedRayPoint(ray, lambda)) - edge.x);
}

double ssBoardOrientedDerivative(vec3 ray, double lambda) {
    dvec4 bands = ssBoardBandSpec();
    dvec2 edge = ssBoardEdgeSpec();
    dvec3 derivative = ssCurvedRayDerivative(ray, lambda);
    return edge.y * (bands.x * derivative.x + bands.y * derivative.z);
}

void ssBoardInsertBreak(inout double values[SS_BOARD_MAX_BREAKS], inout int count, double value,
                        double minimum, double maximum) {
    if (!(value > minimum && value < maximum) || count >= SS_BOARD_MAX_BREAKS) return;
    for (int i = 0; i < SS_BOARD_MAX_BREAKS; i++) {
        if (i >= count) break;
        if (abs(values[i] - value) <= 1.0e-9LF * max(1.0LF, abs(value))) return;
    }
    int position = count;
    for (int i = 0; i < SS_BOARD_MAX_BREAKS; i++) {
        if (i >= count) break;
        if (value < values[i]) { position = i; break; }
    }
    for (int i = SS_BOARD_MAX_BREAKS - 1; i > 0; i--) {
        if (i > position && i <= count) values[i] = values[i - 1];
    }
    values[position] = value;
    count++;
}

// F'(lambda) = orientation * (headingX * R*A*rayX/rho^2 + headingZ*rayZ).
// Its zeros are a quadratic because rho^2 is quadratic.  Thus a curved ray
// has at most two band-projection extrema before the cylinder-axis cut.
void ssBoardAddProjectionExtrema(vec3 ray, inout double values[SS_BOARD_MAX_BREAKS], inout int count,
                                 double minimum, double maximum) {
    if (uSSCurvatureEnabled == 0) return;
    dvec4 bands = ssBoardBandSpec();
    double c = bands.y * double(ray.z);
    double dx = double(ray.x);
    double dy = double(ray.y);
    double radius = ssCurvedRadius();
    double axisDistance = radius - (ssCurvedOriginY() + double(uSSEyeRelative.y));
    double a = dx * dx + dy * dy;
    double b = -2.0LF * axisDistance * dy;
    double d = axisDistance * axisDistance;
    double constant = bands.x * radius * axisDistance * dx;
    if (c == 0.0LF) return;
    double discriminant = b * b - 4.0LF * a * (d + constant / c);
    if (discriminant < 0.0LF || a == 0.0LF) return;
    double root = sqrt(discriminant);
    ssBoardInsertBreak(values, count, (-b - root) / (2.0LF * a), minimum, maximum);
    ssBoardInsertBreak(values, count, (-b + root) / (2.0LF * a), minimum, maximum);
}

double ssBoardNextProjectionExtremum(vec3 ray, double current, double maximum) {
    if (uSSCurvatureEnabled == 0) return maximum;
    dvec4 bands = ssBoardBandSpec();
    double c = bands.y * double(ray.z);
    double dx = double(ray.x), dy = double(ray.y);
    double radius = ssCurvedRadius();
    double axisDistance = radius - (ssCurvedOriginY() + double(uSSEyeRelative.y));
    double a = dx * dx + dy * dy;
    if (c == 0.0LF || a == 0.0LF) return maximum;
    double b = -2.0LF * axisDistance * dy;
    double discriminant = b * b - 4.0LF * a
            * (axisDistance * axisDistance + bands.x * radius * axisDistance * dx / c);
    if (discriminant < 0.0LF) return maximum;
    double root = sqrt(discriminant);
    double first = (-b - root) / (2.0LF * a);
    double second = (-b + root) / (2.0LF * a);
    double epsilon = 1.0e-9LF * max(1.0LF, abs(current));
    if (first > current + epsilon && first < maximum) maximum = first;
    if (second > current + epsilon && second < maximum) maximum = second;
    return maximum;
}

bool ssBoardInsideVolume(dvec3 point) {
    dvec2 vertical = ssBoardVerticalBounds();
    dvec2 strip = ssBoardStripBounds();
    return point.y >= vertical.x && point.y < vertical.y && point.z >= strip.x && point.z < strip.y;
}

double ssBoardBisectProjection(vec3 ray, double low, double high, double target) {
    // Axis-aligned bands are physical longitude/Z planes. Reuse the exact
    // curved-ray crossing already used by cloud traversal instead of taking
    // 44 inverse-chart samples. Keep the bracket authoritative: a singular,
    // opposite-half-plane or rounded-outside candidate uses the general path.
    dvec4 bands = ssBoardBandSpec();
    dvec2 edge = ssBoardEdgeSpec();
    if (edge.y != 0.0LF) {
        double projection = target / edge.y + edge.x;
        double direct = SS_BOARD_NO_HIT;
        if (bands.y == 0.0LF && bands.x != 0.0LF) {
            direct = ssCurvedLongitudeHit(ray, projection / bands.x);
        } else if (bands.x == 0.0LF && bands.y != 0.0LF) {
            direct = ssCurvedZHit(ray, projection / bands.y);
        }
        if (direct > 0.0LF && direct >= low && direct <= high) return direct;
    }
    double lowValue = ssBoardOrientedProjection(ray, low) - target;
    for (int step = 0; step < SS_BOARD_BISECTION_STEPS; step++) {
        double mid = (low + high) * 0.5LF;
        double midValue = ssBoardOrientedProjection(ray, mid) - target;
        if ((lowValue <= 0.0LF && midValue <= 0.0LF) || (lowValue >= 0.0LF && midValue >= 0.0LF)) {
            low = mid;
            lowValue = midValue;
        } else {
            high = mid;
        }
    }
    return (low + high) * 0.5LF;
}

// First crossing of one periodic F boundary before the next projection
// extremum.  Callers retain the old bounded-tail policy after their segment
// budget is exhausted; before that point no Gauss interval straddles a board
// light discontinuity.
double ssBoardNextPeriodicBoundary(vec3 ray, double current, double maximum, double boundary,
                                   double start, double end, double derivative) {
    // The caller shares this monotonic interval and its inverse-chart samples
    // among the material and feather boundaries.
    if (!(maximum > current)) return maximum;
    dvec4 bands = ssBoardBandSpec();
    if (derivative == 0.0LF) return maximum;
    double position = ssBoardPositiveModulo(start, bands.z);
    double delta = derivative > 0.0LF ? boundary - position : position - boundary;
    double epsilon = 1.0e-8LF * max(1.0LF, bands.z);
    if (delta <= epsilon) delta += bands.z;
    double target = derivative > 0.0LF ? start + delta : start - delta;
    if (!((derivative > 0.0LF && target <= end) || (derivative < 0.0LF && target >= end))) return maximum;
    double hit = ssBoardBisectProjection(ray, current, maximum, target);
    return hit > current + epsilon && hit < maximum ? hit : maximum;
}

// Queries every physical shell interval.  Height roots, z crossings and the
// axis cut are all retained as ordered breakpoints; this intentionally does
// not assume a single visible board segment on a curved ray.
double ssBoardFirstHit(vec3 ray, double maximum, out int face, out dvec3 physicalHit) {
    face = SS_BOARD_FACE_SIDE;
    physicalHit = dvec3(0.0LF);
    if (ssBoardCoverage() == 0 || !(maximum > 0.0LF)) return SS_BOARD_NO_HIT;
    double axis = ssCurvedAxisLambda(ray);
    if (axis > 0.0LF) maximum = min(maximum, axis);
    if (!(maximum > 0.0LF)) return SS_BOARD_NO_HIT;

    double cuts[SS_BOARD_MAX_BREAKS];
    int cutCount = 2;
    cuts[0] = 0.0LF;
    cuts[1] = maximum;
    dvec2 vertical = ssBoardVerticalBounds();
    dvec2 roots;
    // Board bounds are render-origin-relative like the returned physical
    // point; height-root cylinders, however, are expressed in world Y.
    int rootKind = ssCurvedHeightRoots(ray, ssCurvedOriginY() + vertical.x, roots);
    if (rootKind > 0) { ssBoardInsertBreak(cuts, cutCount, roots.x, 0.0LF, maximum); ssBoardInsertBreak(cuts, cutCount, roots.y, 0.0LF, maximum); }
    rootKind = ssCurvedHeightRoots(ray, ssCurvedOriginY() + vertical.y, roots);
    if (rootKind > 0) { ssBoardInsertBreak(cuts, cutCount, roots.x, 0.0LF, maximum); ssBoardInsertBreak(cuts, cutCount, roots.y, 0.0LF, maximum); }
    dvec2 strip = ssBoardStripBounds();
    double zHit = ssCurvedZHit(ray, strip.x);
    if (zHit > 0.0LF) ssBoardInsertBreak(cuts, cutCount, zHit, 0.0LF, maximum);
    zHit = ssCurvedZHit(ray, strip.y);
    if (zHit > 0.0LF) ssBoardInsertBreak(cuts, cutCount, zHit, 0.0LF, maximum);
    ssBoardAddProjectionExtrema(ray, cuts, cutCount, 0.0LF, maximum);

    for (int interval = 0; interval < SS_BOARD_MAX_BREAKS - 1; interval++) {
        if (interval + 1 >= cutCount) break;
        double start = cuts[interval];
        double end = cuts[interval + 1];
        if (!(end > start)) continue;
        double probe = (start + end) * 0.5LF;
        if (!ssBoardInsideVolume(ssCurvedRayPoint(ray, probe))) continue;
        int intervalFace = SS_BOARD_FACE_SIDE;
        dvec3 startPoint = ssCurvedRayPoint(ray, start);
        dvec2 epsilon = dvec2(1.0e-7LF * max(1.0LF, end - start), 0.0LF);
        dvec3 afterStart = ssCurvedRayPoint(ray, min(end, start + epsilon.x));
        dvec2 bounds = ssBoardVerticalBounds();
        dvec2 zBounds = ssBoardStripBounds();
        if (afterStart.y >= bounds.x && startPoint.y < bounds.x) intervalFace = SS_BOARD_FACE_UNDERSIDE;
        else if (afterStart.y < bounds.y && startPoint.y >= bounds.y) intervalFace = SS_BOARD_FACE_TOP;
        else if (afterStart.z >= zBounds.x && startPoint.z < zBounds.x) intervalFace = SS_BOARD_FACE_NEGATIVE_STRIP_SIDE;
        else if (afterStart.z < zBounds.y && startPoint.z >= zBounds.y) intervalFace = SS_BOARD_FACE_POSITIVE_STRIP_SIDE;

        if (ssBoardCoverage() == 2 || ssBoardMaterialAt(ssBoardProjection(startPoint))) {
            physicalHit = startPoint;
            face = intervalFace;
            return start;
        }
        // This interval has no F extremum, so the periodic projection is
        // monotonic and one exact material-boundary root is sufficient.
        dvec4 bands = ssBoardBandSpec();
        double orientedStart = ssBoardOrientedProjection(ray, start);
        double periodPosition = ssBoardPositiveModulo(orientedStart, bands.z);
        double derivative = ssBoardOrientedDerivative(ray, probe);
        if (derivative == 0.0LF) continue;
        double target = derivative > 0.0LF
                ? orientedStart + (bands.z - periodPosition)
                : orientedStart - (periodPosition - bands.w);
        double orientedEnd = ssBoardOrientedProjection(ray, end);
        if (!((derivative > 0.0LF && target <= orientedEnd) || (derivative < 0.0LF && target >= orientedEnd))) continue;
        double hit = ssBoardBisectProjection(ray, start, end, target);
        if (!(hit >= start && hit <= end)) continue;
        physicalHit = ssCurvedRayPoint(ray, hit);
        face = derivative > 0.0LF ? SS_BOARD_FACE_PANEL_LEADING_SIDE : SS_BOARD_FACE_PANEL_TRAILING_SIDE;
        return hit;
    }
    return SS_BOARD_NO_HIT;
}

double ssBoardDistance(vec3 ray, double maximum) {
    int ignoredFace;
    dvec3 ignoredPoint;
    return ssBoardFirstHit(ray, maximum, ignoredFace, ignoredPoint);
}
