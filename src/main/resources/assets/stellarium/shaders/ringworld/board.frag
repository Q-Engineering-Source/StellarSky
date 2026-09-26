#version 120

uniform vec4 uViewport;
uniform vec2 uBandHeading;
uniform float uBandSpacing;
uniform float uPanelWidth;
uniform float uEdgeRelative;
uniform int uEdgeOrientation;
uniform int uCoverage;
uniform float uBaseYRelative;
uniform int uThickness;
uniform vec2 uStripZBoundsRelative;
uniform int uDotsEnabled;
uniform float uDotPitch;
uniform float uDotRadius;
uniform float uDotBrightness;
uniform float uDotPerimeterInset;
uniform vec2 uDotObserverPhase;
uniform vec2 uDotNearbyPanelAnchorPhase;
uniform vec2 uDotPanelStepPhase;
uniform float uDotNearbyLeadingRelative;
uniform float uDotOffProbability;
uniform int uDotSeedResidue;
uniform vec2 uDotMaterialCyclePhase;
uniform vec2 uDotPanelStepCyclePhase;
uniform int uDotNearbyPanelResidue;
uniform float uDotTangentPhase;
uniform float uDotTangentCyclePhase;
uniform float uDotPulseBrightness;
uniform int uDotEdgeProfileEnabled;
uniform float uDotEdgeBandFraction;
uniform float uDotEdgeBrightness;
uniform float uDotCenterBrightness;
uniform float uDotEdgeTransitionFraction;

#ifdef SS_BOARD_MESH
uniform vec4 uMeshOriginYZ;
uniform int uMeshDistancePass;
uniform sampler2D uMeshNearestHigh;
uniform sampler2D uMeshNearestLow;
uniform ivec2 uMeshPixelOffset;
uniform vec4 uMeshHeadingHiLo;
uniform vec2 uMeshEdgeHiLo;
flat in dvec4 vMeshPlane;
flat in dvec3 vMeshTileFace;
in vec3 vMeshPhysicalLocal;
#endif

int ssBoardCoverage() { return uCoverage; }
dvec2 ssBoardVerticalBounds() {
    return dvec2(double(uBaseYRelative), double(uBaseYRelative + float(uThickness)));
}
dvec2 ssBoardStripBounds() { return dvec2(uStripZBoundsRelative); }
dvec4 ssBoardBandSpec() {
#ifdef SS_BOARD_MESH
    return dvec4(double(uMeshHeadingHiLo.x) + double(uMeshHeadingHiLo.y),
            double(uMeshHeadingHiLo.z) + double(uMeshHeadingHiLo.w), double(uBandSpacing), double(uPanelWidth));
#else
    return dvec4(double(uBandHeading.x), double(uBandHeading.y), double(uBandSpacing), double(uPanelWidth));
#endif
}
dvec2 ssBoardEdgeSpec() {
#ifdef SS_BOARD_MESH
    return dvec2(double(uMeshEdgeHiLo.x) + double(uMeshEdgeHiLo.y), double(uEdgeOrientation));
#else
    return dvec2(double(uEdgeRelative), double(uEdgeOrientation));
#endif
}

#include "board_query.glsl"

const float DOT_CELL_PERIOD = 4093.0;
const float DOT_SAFE_PANEL_INDEX_MAX = 8388607.0;

const int FACE_SIDE = 0;
const int FACE_TOP = 1;
const int FACE_UNDERSIDE = 2;
const int FACE_NEGATIVE_STRIP_SIDE = 3;
const int FACE_PANEL_LEADING_SIDE = 4;
const int FACE_POSITIVE_STRIP_SIDE = 5;
const int FACE_PANEL_TRAILING_SIDE = 6;
const int FACE_STRIP_SIDE = FACE_NEGATIVE_STRIP_SIDE;
const int FACE_PANEL_SIDE = FACE_PANEL_LEADING_SIDE;

vec3 unproject(float clipZ) {
    vec2 normalized = ((gl_FragCoord.xy - uViewport.xy) / uViewport.zw) * 2.0 - 1.0;
    vec4 eye = gl_ProjectionMatrixInverse * vec4(normalized, clipZ, 1.0);
    eye /= eye.w;
    vec4 local = gl_ModelViewMatrixInverse * vec4(eye.xyz, 1.0);
    return local.xyz / local.w;
}

float positiveRemainder(float value, float period) {
    return value - floor(value / period) * period;
}

float circleCoverage(vec2 displacement, float footprint) {
    float distanceFromCenter = length(displacement);
    float antialias = max(footprint, 0.005);
    float circle = 1.0 - smoothstep(uDotRadius - antialias, uDotRadius + antialias, distanceFromCenter);
    // At a distance where one pixel spans a material fraction of a cell, fade
    // instead of allowing a sparse sample to flicker as the camera moves.
    float gridFade = 1.0 - smoothstep(uDotPitch * 0.10, uDotPitch * 0.35, footprint);
    return circle * gridFade;
}

float latticeCircle(vec2 coordinates, float footprint) {
    vec2 inCell = vec2(positiveRemainder(coordinates.x, uDotPitch),
            positiveRemainder(coordinates.y, uDotPitch));
    vec2 displacement = min(inCell, vec2(uDotPitch) - inCell);
    return circleCoverage(displacement, footprint);
}

float sideCircle(float alongFace, float rowDelta, float footprint) {
    float inCell = positiveRemainder(alongFace, uDotPitch);
    float alongDelta = min(inCell, uDotPitch - inCell);
    return circleCoverage(vec2(alongDelta, rowDelta), footprint);
}

// This deliberately contains nonlinear folded-square stages, avoiding the
// stripe-prone affine lattice of a chained linear congruential hash. Every
// product stays below about 700k, far below the GLSL float integer limit. It
// is intentionally mirrored by RingworldBoardDotOutage.sample.
float dotOutageSample(vec2 cell, float panelResidue, int face) {
    float state = positiveRemainder(cell.x + float(uDotSeedResidue) * 17.0 + 19.0, DOT_CELL_PERIOD);
    float fold = positiveRemainder(state + cell.y * 7.0, 97.0);
    state = positiveRemainder(state * 83.0 + fold * fold * 17.0 + cell.y * 31.0
            + panelResidue * 13.0 + float(face) * 61.0, DOT_CELL_PERIOD);
    fold = positiveRemainder(state + panelResidue * 11.0, 89.0);
    state = positiveRemainder(state * 71.0 + fold * fold * 19.0 + cell.x * 29.0
            + float(face) * 131.0, DOT_CELL_PERIOD);
    fold = positiveRemainder(state + float(uDotSeedResidue), 83.0);
    state = positiveRemainder(state * 59.0 + fold * fold * 23.0 + cell.y * 47.0 + 19.0,
            DOT_CELL_PERIOD);
    return state / DOT_CELL_PERIOD;
}

// GLSL 1.20 has neither integer bit operations nor a safe wide integer type.
// Repeated doubling performs a bounded modular product without ever forming a
// panel-index-by-cycle product near the float exact-integer limit.
float modularProduct(float multiplier, float value, float modulus) {
    float remaining = min(abs(multiplier), DOT_SAFE_PANEL_INDEX_MAX);
    float factor = positiveRemainder(value, modulus);
    float result = 0.0;
    for (int bit = 0; bit < 24; bit++) {
        if (remaining - 2.0 * floor(remaining * 0.5) >= 1.0) {
            result = positiveRemainder(result + factor, modulus);
        }
        factor = positiveRemainder(factor + factor, modulus);
        remaining = floor(remaining * 0.5);
    }
    return multiplier < 0.0 ? positiveRemainder(-result, modulus) : result;
}

float nearestLatticeCenter(float coordinate) {
    float inCell = positiveRemainder(coordinate, uDotPitch);
    return coordinate - inCell + (inCell > uDotPitch * 0.5 ? uDotPitch : 0.0);
}

float nearestLatticeCell(float coordinate) {
    float cycleCoordinate = positiveRemainder(coordinate, uDotPitch * DOT_CELL_PERIOD);
    return positiveRemainder(floor((cycleCoordinate + uDotPitch * 0.5) / uDotPitch),
            DOT_CELL_PERIOD);
}

float widthProfileBrightness(float dotCenterZ) {
    if (uDotEdgeProfileEnabled == 0) {
        return 1.0;
    }
    if (uDotEdgeBandFraction == 0.0) {
        return uDotCenterBrightness;
    }
    float width = uStripZBoundsRelative.y - uStripZBoundsRelative.x;
    float edgeBand = width * uDotEdgeBandFraction;
    float transition = min(width * uDotEdgeTransitionFraction, max(0.0, width * 0.5 - edgeBand));
    float nearestEdge = min(max(0.0, dotCenterZ - uStripZBoundsRelative.x),
            max(0.0, uStripZBoundsRelative.y - dotCenterZ));
    if (nearestEdge <= edgeBand) {
        return uDotEdgeBrightness;
    }
    if (transition == 0.0) {
        return uDotCenterBrightness;
    }
    if (nearestEdge >= edgeBand + transition) {
        return uDotCenterBrightness;
    }
    float progress = smoothstep(0.0, 1.0, (nearestEdge - edgeBand) / transition);
    return mix(uDotEdgeBrightness, uDotCenterBrightness, progress);
}

void main() {
    vec3 nearPoint = unproject(-1.0);
    vec3 farPoint = unproject(1.0);
    vec3 ray = farPoint - nearPoint;
    // GLSL 1.20 derivatives are undefined in non-uniform control flow. Capture
    // the ray basis before every branch/discard, then propagate it analytically
    // to the selected physical hit plane below.
    vec3 nearDx = dFdx(nearPoint);
    vec3 nearDy = dFdy(nearPoint);
    vec3 rayDx = dFdx(ray);
    vec3 rayDy = dFdy(ray);
    vec3 displayRay = normalize(ray);
    int hitFace;
    dvec3 physicalHit;
    // This is a geometric query bound, not a render-distance cutoff.  The
    // projection/depth test below still governs what the rasterizer can show.
#ifdef SS_BOARD_MESH
    dvec3 meshEye = dvec3(0.0LF,
            double(uMeshOriginYZ.x) + double(uMeshOriginYZ.y) + double(uSSEyeRelative.y),
            double(uMeshOriginYZ.z) + double(uMeshOriginYZ.w) + double(uSSEyeRelative.z));
    double denominator = dot(vMeshPlane.xyz, dvec3(displayRay));
    if (denominator == 0.0LF) discard;
    double lambda = (vMeshPlane.w - dot(vMeshPlane.xyz, meshEye)) / denominator;
    if (!(lambda > 0.0LF && lambda < 1.0e15LF)) discard;
    hitFace = int(vMeshTileFace.y);
    physicalHit = dvec3(vMeshTileFace.x + double(vMeshPhysicalLocal.x) + double(uSSEyeRelative.x),
            double(uBaseYRelative) + double(vMeshPhysicalLocal.y),
            double(uStripZBoundsRelative.x) + double(vMeshPhysicalLocal.z));
    // The prism planner provides real panel boundary walls. Continuous shell
    // faces use the same hard material predicate in their bounded physical UVs.
    if (hitFace != FACE_PANEL_LEADING_SIDE && hitFace != FACE_PANEL_TRAILING_SIDE
            && !ssBoardMaterialAt(ssBoardProjection(physicalHit))) discard;
#else
    double lambda = ssBoardFirstHit(displayRay, 1.0e15LF, hitFace, physicalHit);
#endif
    if (!(lambda >= 0.0LF)) discard;
    // DH composites colour without updating Minecraft's depth attachment.
    // Its separately leased depth is therefore the authoritative distant
    // opaque boundary for this early board depth writer as well.
    if (lambda > double(ssDistantOpaqueLimit(displayRay))) discard;
    vec3 hit = vec3(physicalHit);
#ifdef SS_BOARD_MESH
    vec3 displayedHit = vec3(dvec3(uSSEyeRelative) + dvec3(displayRay) * lambda);
#else
    vec3 displayedHit = vec3(ssCurvedDisplayPoint(physicalHit));
#endif
    // The existing dot lattice takes a display-space differential basis.  Keep
    // its old finite-pixel fade rather than letting a curved far hit fabricate
    // an unbounded footprint.  Material identity and the emitted dot position
    // above always use the exact physical hit returned by board_query.
    float hitParameter = dot(displayedHit - nearPoint, ray) / max(dot(ray, ray), 0.000001);
    float lower = uBaseYRelative;

    vec4 clip = gl_ProjectionMatrix * gl_ModelViewMatrix * vec4(displayedHit, 1.0);
    if (!(clip.w > 0.0)) discard;
    float ndcDepth = clip.z / clip.w;
#ifdef SS_BOARD_MESH
    // Primitive clipping retains the hardware near plane; guard its rounded boundary too.
    if (ndcDepth < -1.0) discard;
    vec2 encodedMeshDistance = ssEncodeOwnMediaDistance(lambda).rg;
    if (uMeshDistancePass == 0) {
        gl_FragData[0] = vec4(encodedMeshDistance.x, 0.0, 0.0, 0.0);
        return;
    }
    ivec2 meshPixel = ivec2(gl_FragCoord.xy) - uMeshPixelOffset;
    if (encodedMeshDistance.x != texelFetch(uMeshNearestHigh, meshPixel, 0).r) discard;
    if (uMeshDistancePass == 1) {
        gl_FragData[0] = vec4(encodedMeshDistance.y, 0.0, 0.0, 0.0);
        return;
    }
    if (encodedMeshDistance.y != texelFetch(uMeshNearestLow, meshPixel, 0).r) discard;
#endif
    // Reject non-representable rays rather than emit undefined depth (GLSL 1.20 has no isnan).
    if (!(abs(ndcDepth) <= 3.402823e38)) discard;
    float physicalDepth = gl_DepthRange.near + (ndcDepth * 0.5 + 0.5) * gl_DepthRange.diff;
    gl_FragDepth = clamp(physicalDepth, min(gl_DepthRange.near, gl_DepthRange.far),
            max(gl_DepthRange.near, gl_DepthRange.far));

    vec3 baseColor = hitFace == FACE_TOP ? vec3(0.12)
            : hitFace == FACE_UNDERSIDE ? vec3(0.02) : vec3(0.05);
    // This is only the pre-existing pitch-level visual lattice. It is cheap
    // enough to find an anti-aliased circle candidate before the wider material
    // identity is needed; no world-scale position enters this calculation.
    // Keep identity arithmetic in physical FP64 until every value has been
    // reduced to its bounded period.  Casting the curved far hit before the
    // panel ordinal/cycle reduction makes dots jump while zooming or panning.
    double dotPitchD = double(uDotPitch);
    double dotProjectionD = ssBoardProjection(physicalHit);
    double dotPanelIndexD = uCoverage == 1
            ? floor((dotProjectionD - double(uDotNearbyLeadingRelative)) / double(uBandSpacing)) : 0.0LF;
    double dotPanelCoordinateD = ssBoardPositiveModulo(dotProjectionD - double(uDotNearbyLeadingRelative),
            double(uBandSpacing));
    double worldPhaseXD = ssBoardPositiveModulo(double(uDotObserverPhase.x) + physicalHit.x, dotPitchD);
    double worldPhaseZD = ssBoardPositiveModulo(double(uDotObserverPhase.y) + physicalHit.z, dotPitchD);
    double panelAnchorXD = ssBoardPositiveModulo(double(uDotNearbyPanelAnchorPhase.x)
            + dotPanelIndexD * double(uDotPanelStepPhase.x), dotPitchD);
    double panelAnchorZD = ssBoardPositiveModulo(double(uDotNearbyPanelAnchorPhase.y)
            + dotPanelIndexD * double(uDotPanelStepPhase.y), dotPitchD);
    float dotPanelCoordinate = float(dotPanelCoordinateD);
    vec2 dotCoordinates = vec2(float(ssBoardPositiveModulo(worldPhaseXD - panelAnchorXD, dotPitchD)),
            float(ssBoardPositiveModulo(worldPhaseZD - panelAnchorZD, dotPitchD)));
    float rowDelta = hit.y - (lower + float(uThickness) * 0.5);
    vec2 panelSideDirection = vec2(-uBandHeading.y, uBandHeading.x);
    // Do not project independently modulo-reduced X/Z coordinates. For an
    // arbitrary persisted heading, that differs from modulo(project(v,t)).
    // The CPU-reduced scalar tangent phase is the sole side-row lattice axis.
    double panelSideAlongD = ssBoardPositiveModulo(double(uDotTangentPhase)
            + physicalHit.x * double(panelSideDirection.x) + physicalHit.z * double(panelSideDirection.y), dotPitchD);
    float panelSideAlong = float(panelSideAlongD);
    float stripSideAlong = dotCoordinates.x;
    // Default to an entirely faded dot. The only alternative is a finite
    // analytic derivative of the actual Y, strip-Z, or periodic-panel plane.
    float undersideFootprint = uDotPitch;
    float panelSideFootprint = uDotPitch;
    float stripSideFootprint = uDotPitch;
    double dotCycleBlocksD = dotPitchD * double(DOT_CELL_PERIOD);
    double materialCycleXD = ssBoardPositiveModulo(double(uDotMaterialCyclePhase.x)
            + ssBoardPositiveModulo(physicalHit.x, dotCycleBlocksD)
            - ssBoardPositiveModulo(dotPanelIndexD * double(uDotPanelStepCyclePhase.x), dotCycleBlocksD),
            dotCycleBlocksD);
    double materialCycleZD = ssBoardPositiveModulo(double(uDotMaterialCyclePhase.y)
            + ssBoardPositiveModulo(physicalHit.z, dotCycleBlocksD)
            - ssBoardPositiveModulo(dotPanelIndexD * double(uDotPanelStepCyclePhase.y), dotCycleBlocksD),
            dotCycleBlocksD);
    float dotPanelResidue = float(ssBoardPositiveModulo(double(uDotNearbyPanelResidue) + dotPanelIndexD,
            double(DOT_CELL_PERIOD)));
    bool stripFace = hitFace == FACE_NEGATIVE_STRIP_SIDE || hitFace == FACE_POSITIVE_STRIP_SIDE;
    vec3 hitNormal = hitFace == FACE_TOP || hitFace == FACE_UNDERSIDE ? vec3(0.0, 1.0, 0.0)
            : stripFace ? vec3(0.0, 0.0, 1.0)
            : (hitFace == FACE_PANEL_SIDE || hitFace == FACE_PANEL_TRAILING_SIDE)
                    ? vec3(uBandHeading.x, 0.0, uBandHeading.y) : vec3(0.0);
    float normalRay = dot(hitNormal, ray);
    if (hitParameter > 0.0 && abs(normalRay) > 0.00001) {
        vec3 baseDx = nearDx + rayDx * hitParameter;
        vec3 baseDy = nearDy + rayDy * hitParameter;
        float hitParameterDx = -dot(hitNormal, baseDx) / normalRay;
        float hitParameterDy = -dot(hitNormal, baseDy) / normalRay;
        vec3 hitDx = baseDx + ray * hitParameterDx;
        vec3 hitDy = baseDy + ray * hitParameterDy;
        undersideFootprint = max(abs(hitDx.x) + abs(hitDy.x), abs(hitDx.z) + abs(hitDy.z));
        float rowFootprint = abs(hitDx.y) + abs(hitDy.y);
        panelSideFootprint = max(abs(dot(hitDx.xz, panelSideDirection))
                + abs(dot(hitDy.xz, panelSideDirection)), rowFootprint);
        stripSideFootprint = max(abs(hitDx.x) + abs(hitDy.x), rowFootprint);
    }
    float dotMask = 0.0;
    float dotWidthBrightness = 1.0;
    // Exact all-off avoids even the candidate lattice; all-on still takes the
    // cheap pitch path but skips wide-cycle ID/hash work entirely.
    if (uDotsEnabled != 0 && uDotBrightness > 0.0 && uDotOffProbability < 1.0) {
        float stripEdgeDistance = min(hit.z - uStripZBoundsRelative.x,
                uStripZBoundsRelative.y - hit.z);
        bool stripInterior = stripEdgeDistance >= uDotPerimeterInset;
        if (hitFace == FACE_UNDERSIDE) {
            bool panelInterior = true;
            if (uCoverage == 1) {
                panelInterior = min(dotPanelCoordinate, uPanelWidth - dotPanelCoordinate)
                        >= uDotPerimeterInset;
            }
            if (stripInterior && panelInterior) {
                float candidateMask = latticeCircle(dotCoordinates, undersideFootprint);
                if (candidateMask > 0.0) {
                    bool dotLit = true;
                    if (uDotOffProbability > 0.0) {
                        vec2 materialCycleCoordinates = vec2(float(materialCycleXD), float(materialCycleZD));
                        vec2 dotCell = vec2(nearestLatticeCell(materialCycleCoordinates.x),
                                nearestLatticeCell(materialCycleCoordinates.y));
                        dotLit = dotOutageSample(dotCell, dotPanelResidue, FACE_UNDERSIDE)
                                >= uDotOffProbability;
                    }
                    if (dotLit) {
                        dotMask = candidateMask;
                        float dotCenterZ = hit.z + nearestLatticeCenter(dotCoordinates.y) - dotCoordinates.y;
                        dotWidthBrightness = widthProfileBrightness(dotCenterZ);
                    }
                }
            }
        } else if (stripFace || hitFace == FACE_PANEL_SIDE
                || hitFace == FACE_PANEL_TRAILING_SIDE) {
            bool panelSide = hitFace == FACE_PANEL_SIDE || hitFace == FACE_PANEL_TRAILING_SIDE;
            bool sideInterior = panelSide ? stripInterior : true;
            if (stripFace && uCoverage == 1) {
                sideInterior = min(dotPanelCoordinate, uPanelWidth - dotPanelCoordinate)
                        >= uDotPerimeterInset;
            }
            if (sideInterior) {
                float alongFace = panelSide
                        ? panelSideAlong : stripSideAlong;
                float sideFootprint = panelSide
                        ? panelSideFootprint : stripSideFootprint;
                int sideFace = panelSide
                        ? (dot(ray.xz, uBandHeading) >= 0.0
                                ? FACE_PANEL_LEADING_SIDE : FACE_PANEL_TRAILING_SIDE)
                        : (hit.z < (uStripZBoundsRelative.x + uStripZBoundsRelative.y) * 0.5
                                ? FACE_NEGATIVE_STRIP_SIDE : FACE_POSITIVE_STRIP_SIDE);
                float candidateMask = sideCircle(alongFace, rowDelta, sideFootprint);
                if (candidateMask > 0.0) {
                    bool dotLit = true;
                    if (uDotOffProbability > 0.0) {
                        vec2 materialCycleCoordinates = vec2(float(materialCycleXD), float(materialCycleZD));
                        double materialSideAlongD = ssBoardPositiveModulo(double(uDotTangentCyclePhase)
                                + ssBoardPositiveModulo(physicalHit.x * double(panelSideDirection.x)
                                + physicalHit.z * double(panelSideDirection.y), dotCycleBlocksD), dotCycleBlocksD);
                        float materialSideAlong = float(materialSideAlongD);
                        float sideCell = panelSide
                                ? nearestLatticeCell(materialSideAlong)
                                : nearestLatticeCell(materialCycleCoordinates.x);
                        dotLit = dotOutageSample(vec2(sideCell, 0.0), dotPanelResidue, sideFace)
                                >= uDotOffProbability;
                    }
                    if (dotLit) {
                        dotMask = candidateMask;
                        float dotCenterZ = hit.z;
                        if (panelSide) {
                            dotCenterZ += panelSideDirection.y * (nearestLatticeCenter(panelSideAlong)
                                    - panelSideAlong);
                        }
                        dotWidthBrightness = widthProfileBrightness(dotCenterZ);
                    }
                }
            }
        }
    }
    // Visual light level 15: emission is independent of scene light, face
    // shading and board transmission. Later sightline air still attenuates it.
    // This is a material emitter, not Minecraft block-light propagation.
    vec3 dotEmission = uDotBrightness * dotWidthBrightness * uDotPulseBrightness * vec3(0.55, 0.80, 1.0);
    gl_FragData[0] = vec4(mix(baseColor, dotEmission, dotMask), 1.0);
    // lambda is the exact positive display-ray parameter returned by the
    // curved analytic material query above.  Preserve it in MRT without a
    // float round-trip so DH's later fragment stage can reject hidden LODs.
    gl_FragData[1] = ssEncodeOwnMediaDistance(lambda);
}
