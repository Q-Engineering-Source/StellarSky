#version 120

// Display-linear compositing over copies of the managed Minecraft framebuffer.
// GLSL 1.20 has no sRGB transfer helpers; calibrated pow(2.2) is deliberately
// approximate and requires real-GPU visual validation before acceptance.
// A no-air ray returns the copied source texel verbatim; it never round-trips gamma.
uniform sampler2D uSceneColor;
uniform sampler2D uSceneDepth;
uniform mat4 uInverseProjection;
uniform mat4 uInverseModelView;
uniform vec2 uSnapshotFootYZ; // frozen feet Y/Z; X stays CPU-double relative
uniform vec3 uCameraRelative;
uniform vec3 uAirProfile; // lower, full-density top, exclusive upper
uniform vec2 uStripZ; // camera-relative half-open bounds
uniform vec2 uBoard; // camera-relative base, thickness
uniform vec4 uBands; // heading XZ, spacing, panel width
uniform vec2 uBandEdge; // nearest material edge relative to camera, orientation
uniform int uCoverage; // 0 empty, 1 partial, 2 full; no fake periodic dimensions for constants
uniform float uSideFeather;
uniform float uMotionFeather;
uniform float uMaxDistance;
uniform float uBandMeanTransmission;
uniform vec3 uSigmaT;
uniform vec3 uSigmaS;
uniform vec3 uSunRadiance;
varying vec2 vUv;

int ssBoardCoverage() { return uCoverage; }
dvec2 ssBoardVerticalBounds() { return dvec2(uBoard.x, uBoard.x + uBoard.y); }
dvec2 ssBoardStripBounds() { return dvec2(uStripZ); }
dvec4 ssBoardBandSpec() { return dvec4(uBands); }
dvec2 ssBoardEdgeSpec() { return dvec2(uBandEdge); }

#include "board_query.glsl"
#ifdef SS_AIR_CURVED_ONLY
const float SS_CLOUD_LARGE_T = float(SS_CLOUD_NUMERIC_TRACE_LIMIT);
#else
#include "cloud_query.glsl"
#endif

float densityAt(vec3 relativePoint) {
    float worldY = uSnapshotFootYZ.x + relativePoint.y;
    if (worldY < uAirProfile.x || worldY >= uAirProfile.z || relativePoint.z < uStripZ.x || relativePoint.z >= uStripZ.y)
        return 0.0;
    if (worldY <= uAirProfile.y) return 1.0;
    float progress = (worldY - uAirProfile.y) / (uAirProfile.z - uAirProfile.y);
    return 1.0 - progress * progress * (3.0 - 2.0 * progress);
}

float raySlab(float origin, float direction, float minimum, float maximum, inout float entry, inout float exit) {
    if (direction == 0.0) return origin >= minimum && origin < maximum ? 1.0 : 0.0;
    float first = (minimum - origin) / direction;
    float second = (maximum - origin) / direction;
    entry = max(entry, min(first, second));
    exit = min(exit, max(first, second));
    return exit > entry ? 1.0 : 0.0;
}

float boardDistance(vec3 origin, vec3 ray, float budget) {
    if (uSSCurvatureEnabled != 0) {
        double curved = ssBoardDistance(ray, double(budget));
        return curved >= 0.0LF ? float(curved) : budget;
    }
    float entry = 0.0;
    float exit = budget;
    if (raySlab(origin.y, ray.y, uBoard.x, uBoard.x + uBoard.y, entry, exit) == 0.0) return budget;
    if (raySlab(origin.z, ray.z, uStripZ.x, uStripZ.y, entry, exit) == 0.0) return budget;
    // One analytic next-material entry suffices even across a long empty gap.
    // The bound is an actual candidate distance, never a sampling-quality cap.
    if (uCoverage == 0) return budget;
    if (uCoverage == 2) return entry;
    float projection = dot((origin + ray * entry).xz, uBands.xy);
    float oriented = uBandEdge.y * (projection - uBandEdge.x);
    float position = mod(oriented, uBands.z);
    if (position < 0.0) position += uBands.z;
    if (position <= uBands.w) return entry;
    float along = uBandEdge.y * dot(ray.xz, uBands.xy);
    if (along == 0.0) return budget;
    float offset = along > 0.0 ? (uBands.z - position) / along : (position - uBands.w) / -along;
    float hit = entry + offset;
    return hit >= entry && hit <= exit ? hit : budget;
}

float sceneDistance(float depth, vec2 uv, vec3 ray) {
    // Only exact depth-clear is sky. Values just below one can represent
    // genuine opaque terrain a few blocks before a 512/1024-block far plane.
    if (depth >= 1.0) return SS_CLOUD_LARGE_T;
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    view /= view.w;
    vec4 sceneRelative4 = uInverseModelView * vec4(view.xyz, 1.0);
    vec3 sceneRelative = sceneRelative4.xyz / sceneRelative4.w;
    float distance = dot(sceneRelative - uCameraRelative, ray);
    return distance > 0.0 ? distance : SS_CLOUD_LARGE_T;
}

vec3 stableAverage(vec3 opticalDepth) {
    vec3 series = vec3(1.0) - opticalDepth * 0.5 + opticalDepth * opticalDepth / 6.0;
    return vec3(opticalDepth.r < 0.0001 ? series.r : (1.0 - exp(-opticalDepth.r)) / opticalDepth.r,
            opticalDepth.g < 0.0001 ? series.g : (1.0 - exp(-opticalDepth.g)) / opticalDepth.g,
            opticalDepth.b < 0.0001 ? series.b : (1.0 - exp(-opticalDepth.b)) / opticalDepth.b);
}

float nextPlane(float current, float end, float origin, float direction, float plane) {
    if (abs(direction) < 0.000001) return end;
    float hit = (plane - origin) / direction;
    return hit > current + 0.0001 && hit < end ? hit : end;
}

float positiveRemainder(float value, float period) {
    float result = mod(value, period);
    return result < 0.0 ? result + period : result;
}

float nextBandBoundary(float current, float end, vec3 ray, float boundary) {
    if (uCoverage != 1) return end;
    float slope = uBandEdge.y * dot(ray.xz, uBands.xy);
    if (abs(slope) < 0.000001) return end;
    float position = positiveRemainder(uBandEdge.y * (dot((uCameraRelative + ray * current).xz, uBands.xy)
            - uBandEdge.x), uBands.z);
    float delta = slope > 0.0 ? boundary - position : position - boundary;
    if (delta <= 0.0001) delta += uBands.z;
    float hit = current + delta / abs(slope);
    return hit < end ? hit : end;
}

float nextMandatoryCut(float current, float end, vec3 ray) {
    float next = end;
    next = min(next, nextPlane(current, end, uCameraRelative.y, ray.y, uAirProfile.y - uSnapshotFootYZ.x));
    next = min(next, nextPlane(current, end, uCameraRelative.y, ray.y, uBoard.x));
    next = min(next, nextPlane(current, end, uCameraRelative.y, ray.y, uBoard.x + uBoard.y));
    if (uSideFeather > 0.0) {
        next = min(next, nextPlane(current, end, uCameraRelative.z, ray.z, uStripZ.x + uSideFeather));
        next = min(next, nextPlane(current, end, uCameraRelative.z, ray.z, uStripZ.y - uSideFeather));
    }
    if (uCoverage == 1) {
        next = min(next, nextBandBoundary(current, end, ray, 0.0));
        next = min(next, nextBandBoundary(current, end, ray, uBands.w));
        if (uMotionFeather > 0.0) {
            next = min(next, nextBandBoundary(current, end, ray, uMotionFeather));
            next = min(next, nextBandBoundary(current, end, ray, uBands.w - uMotionFeather));
        }
    }
    return next;
}

float verticalSunMass(float worldY) {
    if (worldY >= uAirProfile.z) return 0.0;
    float fade = uAirProfile.z - uAirProfile.y;
    if (worldY <= uAirProfile.y) return fade * 0.5 + uAirProfile.y - worldY;
    float u = (worldY - uAirProfile.y) / fade;
    return fade * (0.5 - u + u * u * u - 0.5 * u * u * u * u);
}

float shadeAt(vec3 point) {
    float worldZ = uSnapshotFootYZ.y + point.z;
    if (worldZ < uStripZ.x + uSnapshotFootYZ.y || worldZ >= uStripZ.y + uSnapshotFootYZ.y) return 1.0;
    if (point.y >= uBoard.x + uBoard.y) return 1.0;
    if (uCoverage == 0) return 1.0;
    float position = mod(uBandEdge.y * (dot(point.xz, uBands.xy) - uBandEdge.x), uBands.z);
    if (position < 0.0) position += uBands.z;
    bool material = uCoverage == 2 || position <= uBands.w;
    // Within the physical board material, direct transmission is binary. The
    // soft movement/side field exists only below the board, matching core.
    if (point.y >= uBoard.x) return material ? 0.0 : 1.0;
    float transmission = material ? 0.0 : 1.0;
    if (uCoverage == 1 && material && uMotionFeather > 0.0) {
        float inwardPanel = min(position, uBands.w - position);
        float progress = clamp(inwardPanel / uMotionFeather, 0.0, 1.0);
        transmission = 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }
    if (uSideFeather > 0.0) {
        float inward = min(worldZ + 8192.0, 8192.0 - worldZ);
        float edge = clamp(inward / uSideFeather, 0.0, 1.0);
        transmission = mix(1.0, transmission, edge * edge * (3.0 - 2.0 * edge));
    }
    return transmission;
}

// A long residual segment can cover many short board periods. Only its
// unresolved illumination is averaged; neither the air volume nor its actual
// opaque terminator is shortened. This is a bounded quadrature approximation.
float periodMeanShadeAt(vec3 point) {
    if (uCoverage == 0 || point.y >= uBoard.x + uBoard.y) return 1.0;
    if (uCoverage == 2) return 0.0;
    if (point.y >= uBoard.x) return (uBands.z - uBands.w) / uBands.z;
    float transmission = uBandMeanTransmission;
    if (uSideFeather > 0.0) {
        float inward = min(point.z - uStripZ.x, uStripZ.y - point.z);
        float edge = clamp(inward / uSideFeather, 0.0, 1.0);
        transmission = mix(1.0, transmission, edge * edge * (3.0 - 2.0 * edge));
    }
    return transmission;
}

const int SS_AIR_MAX_CUTS = 14;

void ssAirInsertCut(inout double values[SS_AIR_MAX_CUTS], inout int count, double value,
                    double minimum, double maximum) {
    if (!(value > minimum && value < maximum) || count >= SS_AIR_MAX_CUTS) return;
    for (int i = 0; i < SS_AIR_MAX_CUTS; i++) {
        if (i >= count) break;
        if (abs(values[i] - value) <= 1.0e-9LF * max(1.0LF, abs(value))) return;
    }
    int position = count;
    for (int i = 0; i < SS_AIR_MAX_CUTS; i++) {
        if (i >= count) break;
        if (value < values[i]) { position = i; break; }
    }
    for (int i = SS_AIR_MAX_CUTS - 1; i > 0; i--) {
        if (i > position && i <= count) values[i] = values[i - 1];
    }
    values[position] = value;
    count++;
}

void ssAirAddHeightCuts(vec3 ray, double worldY, inout double values[SS_AIR_MAX_CUTS], inout int count,
                        double minimum, double maximum) {
    dvec2 roots;
    int kind = ssCurvedHeightRoots(ray, worldY, roots);
    if (kind > 0) {
        ssAirInsertCut(values, count, roots.x, minimum, maximum);
        ssAirInsertCut(values, count, roots.y, minimum, maximum);
    }
}

bool ssAirContains(dvec3 physical) {
    double worldY = double(uSnapshotFootYZ.x) + physical.y;
    return worldY >= double(uAirProfile.x) && worldY < double(uAirProfile.z)
            && physical.z >= double(uStripZ.x) && physical.z < double(uStripZ.y);
}

double ssAirNextBoardCut(vec3 ray, double current, double end) {
    if (ssBoardCoverage() != 1) return end;
    double maximum = ssBoardNextProjectionExtremum(ray, current, end);
    if (!(maximum > current)) return maximum;
    double startProjection = ssBoardOrientedProjection(ray, current);
    double endProjection = ssBoardOrientedProjection(ray, maximum);
    double derivative = ssBoardOrientedDerivative(ray, (current + maximum) * 0.5LF);
    double next = maximum;
    dvec4 bands = ssBoardBandSpec();
    next = min(next, ssBoardNextPeriodicBoundary(ray, current, maximum, 0.0LF,
            startProjection, endProjection, derivative));
    next = min(next, ssBoardNextPeriodicBoundary(ray, current, maximum, bands.w,
            startProjection, endProjection, derivative));
    // The soft moving edge is itself a lighting discontinuity in the bounded
    // quadrature model, so keep its entry/exit in a separate source piece.
    if (uMotionFeather > 0.0 && double(uMotionFeather) < bands.w) {
        next = min(next, ssBoardNextPeriodicBoundary(ray, current, maximum, double(uMotionFeather),
                startProjection, endProjection, derivative));
        next = min(next, ssBoardNextPeriodicBoundary(ray, current, maximum, bands.w - double(uMotionFeather),
                startProjection, endProjection, derivative));
    }
    return next;
}

// Curved air keeps display lambda only as the screen/depth parameter.  Every
// medium lookup, board illumination lookup and optical distance uses the
// inverse-mapped physical point and its physical path metric.
void ssCurvedAirCompose(vec4 source, vec3 ray, double opaqueLimit, bool hasOpaqueHit) {
    double maximum = double(opaqueLimit);
    double axis = ssCurvedAxisLambda(ray);
    if (axis > 0.0LF) maximum = min(maximum, axis);
    if (!(maximum > 0.0LF)) { gl_FragColor = source; return; }

    double cuts[SS_AIR_MAX_CUTS];
    int cutCount = 2;
    cuts[0] = 0.0LF;
    cuts[1] = maximum;
    ssAirAddHeightCuts(ray, double(uAirProfile.x), cuts, cutCount, 0.0LF, maximum);
    ssAirAddHeightCuts(ray, double(uAirProfile.y), cuts, cutCount, 0.0LF, maximum);
    ssAirAddHeightCuts(ray, double(uAirProfile.z), cuts, cutCount, 0.0LF, maximum);
    double zCut = ssCurvedZHit(ray, double(uStripZ.x));
    if (zCut > 0.0LF) ssAirInsertCut(cuts, cutCount, zCut, 0.0LF, maximum);
    zCut = ssCurvedZHit(ray, double(uStripZ.y));
    if (zCut > 0.0LF) ssAirInsertCut(cuts, cutCount, zCut, 0.0LF, maximum);
    if (uSideFeather > 0.0 && 2.0 * double(uSideFeather) < double(uStripZ.y - uStripZ.x)) {
        zCut = ssCurvedZHit(ray, double(uStripZ.x + uSideFeather));
        if (zCut > 0.0LF) ssAirInsertCut(cuts, cutCount, zCut, 0.0LF, maximum);
        zCut = ssCurvedZHit(ray, double(uStripZ.y - uSideFeather));
        if (zCut > 0.0LF) ssAirInsertCut(cuts, cutCount, zCut, 0.0LF, maximum);
    }

    vec3 linear = pow(max(source.rgb, vec3(0.0)), vec3(2.2));
    vec3 accumulated = vec3(0.0);
    vec3 transmission = vec3(1.0);
    bool sawAir = false;
    double backgroundEnd = -1.0LF;
    int evaluatedPieces = 0;
    for (int interval = 0; interval < SS_AIR_MAX_CUTS - 1; interval++) {
        if (interval + 1 >= cutCount) break;
        double start = cuts[interval];
        double end = cuts[interval + 1];
        if (!(end > start) || !ssAirContains(ssCurvedRayPoint(ray, (start + end) * 0.5LF))) continue;
        if (!hasOpaqueHit && backgroundEnd < 0.0LF) backgroundEnd = start + double(uMaxDistance);
        if (backgroundEnd >= 0.0LF) end = min(end, backgroundEnd);
        if (!(end > start)) continue;
        sawAir = true;
        double cursor = start;
        for (int airPart = 0; airPart < 8; airPart++) {
            if (!(end > cursor)) break;
            // Match the old final residual policy: after seven resolved
            // cuts, consume the complete remaining physical segment with a
            // mean only when it really spans repeated board periods.
            bool unresolvedBoardCut = evaluatedPieces >= 7
                    && ssAirNextBoardCut(ray, cursor, end) < end;
            double next = evaluatedPieces >= 7 ? end : ssAirNextBoardCut(ray, cursor, end);
            if (!(next > cursor + 1.0e-9LF)) next = end;
            double piece = next - cursor;
            dvec3 midDerivative = ssCurvedRayDerivative(ray, (cursor + next) * 0.5LF);
            double bandSpeed = abs(double(uBands.x) * midDerivative.x + double(uBands.y) * midDerivative.z);
            for (int quadratureSample = 0; quadratureSample < 2; quadratureSample++) {
                double fraction = quadratureSample == 0 ? 0.211324865405187LF : 0.788675134594813LF;
                double lambda = cursor + piece * fraction;
                dvec3 physicalD = ssCurvedRayPoint(ray, lambda);
                vec3 physical = vec3(physicalD);
                dvec3 physicalDerivative = ssCurvedRayDerivative(ray, lambda);
                double pathWeight = ssCurvedLength3(physicalDerivative.x, physicalDerivative.y, physicalDerivative.z);
                double dsD = piece * 0.5LF * pathWeight;
                float ds = float(dsD);
                float density = densityAt(physical);
                vec3 opticalDepth = uSigmaT * density * ds;
                float shade = shadeAt(physical);
                if (uCoverage == 1 && (unresolvedBoardCut
                        || piece * bandSpeed >= 2.0LF * double(uBands.z)))
                    shade = periodMeanShadeAt(physical);
                float mu = float(clamp(physicalDerivative.y / pathWeight, -1.0LF, 1.0LF));
                float g = 0.35;
                float phase = (1.0 - g * g) / (12.5663706 * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
                vec3 sunTransmission = exp(-uSigmaT * verticalSunMass(uSnapshotFootYZ.x + physical.y));
                vec3 sourceLight = uSigmaS * density * uSunRadiance * phase * shade * sunTransmission;
                accumulated += transmission * sourceLight * ds * stableAverage(opticalDepth);
                transmission *= exp(-opticalDepth);
            }
            evaluatedPieces++;
            cursor = next;
        }
    }
    if (!sawAir || evaluatedPieces == 0) { gl_FragColor = source; return; }
    vec3 result = accumulated + transmission * linear;
    gl_FragColor = vec4(pow(clamp(result, 0.0, 1.0), vec3(1.0 / 2.2)), source.a);
}

void main() {
    vec4 source = texture2D(uSceneColor, vUv);
    vec4 view = uInverseProjection * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewDirection = normalize(view.xyz / view.w);
    vec3 ray = normalize((uInverseModelView * vec4(viewDirection, 0.0)).xyz);
#ifdef SS_AIR_CURVED_ONLY
    {
#else
    if (uSSCurvatureEnabled != 0) {
#endif
        // Actual early raster coverage is authoritative, including screen-door holes and near VBO edges.
        double media = ssOwnMediaOpaqueLimit();
        double dh = ssDistantOpaqueLimitD(ray);
        double nativeScene = double(sceneDistance(texture2D(uSceneDepth, vUv).r, vUv, ray));
        double stop = min(nativeScene, min(dh, media));
        ssCurvedAirCompose(source, ray, stop, stop < double(SS_CLOUD_LARGE_T));
        return;
    }
#ifndef SS_AIR_CURVED_ONLY
    // A finite sampling budget is not a fictitious near opaque surface.
    float board = boardDistance(uCameraRelative, ray, SS_CLOUD_LARGE_T);
    vec3 cloudNormal;
    vec3 cloudColor;
    float cloudCensored;
    float cloud = ssCloudTrace(uCameraRelative, ray, cloudNormal, cloudColor, cloudCensored);
    float sampledDepth = texture2D(uSceneDepth, vUv).r;
    float scene = sceneDistance(sampledDepth, vUv, ray);
    // The copied Minecraft depth has no DH LOD contribution.  Merge the
    // leased DH endpoint before selecting any board/cloud/scene terminator so
    // a physical air segment can never integrate behind its distant terrain.
    scene = min(scene, ssDistantOpaqueLimit(ray));
    // Board.frag writes exact depth=1 when its physical hit lies beyond the
    // clip range, so sceneDistance's exact-clear branch retains board's true
    // analytic hit. In-frustum depth reconstruction and min preserve whichever
    // opaque geometry is physically nearer; no epsilon classification is safe.
    float limit = cloud < 0.0 ? min(scene, board) : min(scene, min(board, cloud));
    bool hasOpaqueHit = limit < SS_CLOUD_LARGE_T;
    float entry = 0.0;
    float exit = limit;
    if (raySlab(uCameraRelative.y, ray.y, uAirProfile.x - uSnapshotFootYZ.x, uAirProfile.z - uSnapshotFootYZ.x, entry, exit) == 0.0
            || raySlab(uCameraRelative.z, ray.z, uStripZ.x, uStripZ.y, entry, exit) == 0.0) {
        gl_FragColor = source;
        return;
    }
    // Only a background ray without an opaque hit uses a finite omitted-tail
    // policy. Apply its length after entering air, not while crossing vacuum.
    if (!hasOpaqueHit) exit = min(exit, entry + uMaxDistance);
    if (!(exit > entry)) { gl_FragColor = source; return; }
    vec3 linear = pow(max(source.rgb, vec3(0.0)), vec3(2.2));
    vec3 accumulated = vec3(0.0);
    vec3 transmission = vec3(1.0);
    float cursor = entry;
    for (int segmentIndex = 0; segmentIndex < 8; segmentIndex++) {
        if (cursor >= exit - 0.0001) break;
        // Resolve the first mandatory boundaries, then consume the complete
        // remaining interval. Never drop the distant air before a visible LOD.
        float next = segmentIndex == 7 ? exit : nextMandatoryCut(cursor, exit, ray);
        if (next <= cursor + 0.0001) next = exit;
        float piece = next - cursor;
        // Two source evaluations per interval, bounded at 16. A residual tail
        // spanning multiple periods uses mean illumination instead of flicker.
        for (int sampleIndex = 0; sampleIndex < 2; sampleIndex++) {
            float fraction = sampleIndex == 0 ? 0.2113248654 : 0.7886751346;
            float distance = cursor + piece * fraction;
            vec3 point = uCameraRelative + ray * distance;
            float density = densityAt(point);
            float ds = piece * 0.5;
            vec3 opticalDepth = uSigmaT * density * ds;
            float shade = shadeAt(point);
            if (uCoverage == 1 && piece * abs(dot(ray.xz, uBands.xy)) >= 2.0 * uBands.z)
                shade = periodMeanShadeAt(point);
            float mu = clamp(ray.y, -1.0, 1.0);
            float g = 0.35;
            float phase = (1.0 - g * g) / (12.5663706 * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
            vec3 sunTransmission = exp(-uSigmaT * verticalSunMass(uSnapshotFootYZ.x + point.y));
            vec3 sourceLight = uSigmaS * density * uSunRadiance * phase * shade * sunTransmission;
            accumulated += transmission * sourceLight * ds * stableAverage(opticalDepth);
            transmission *= exp(-opticalDepth);
        }
        cursor = next;
    }
    vec3 result = accumulated + transmission * linear;
    gl_FragColor = vec4(pow(clamp(result, 0.0, 1.0), vec3(1.0 / 2.2)), source.a);
#endif
}
