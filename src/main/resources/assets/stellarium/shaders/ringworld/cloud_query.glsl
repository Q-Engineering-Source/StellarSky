// Included after #version 120 and the Java-owned CloudLodLayout.glslDefines().
// The loader must also inject SS_CLOUD_MAX_STEPS from CloudRayBudget.MAX_STEPS.

// Absolute page origins expressed as camera-relative block coordinates.
// They are finite cache bounds, not world periods.
uniform vec2 uCloudOrigin0;
uniform vec2 uCloudOrigin1;
uniform vec2 uCloudOrigin2;
uniform vec2 uCloudOrigin3;
uniform vec2 uCloudOrigin4;
uniform vec2 uCloudOrigin5;
uniform vec2 uCloudOrigin6;
uniform vec2 uCloudOrigin7;
uniform vec2 uCloudOrigin8;
uniform vec2 uCloudOrigin9;
uniform vec2 uCloudOrigin10;
uniform vec2 uCloudOrigin11;
uniform vec2 uCloudOrigin12;
uniform vec3 uCloudGeometry; // finest cell XZ, finest cell Y, baseYRelative
uniform vec4 uCloudClip;     // lowerY, upperY, minZ, maxZ relative to feet
uniform float uCloudHorizon; // final 3D detail-tier end; CPU-proved, not a far cutoff
uniform sampler2D uCloudAtlas;
uniform int uCloudActive;
// LOD0..LOD3 respectively; 0.0 is double-sided, 1.0 removes back faces.
uniform vec4 uCloudCullFlags;
// 2 / (framebufferHeight * abs(projection[5])) from the frozen renderer frame.
// It is used only for the user-authorized sub-pixel far-tail stop.
uniform float uCloudPixelAngularSize;
// (terminal transition width in blocks, active 0/1, proxy ranks 0..64).
uniform vec3 uCloudTailPolicy;

const float SS_CLOUD_LARGE_T = float(SS_CLOUD_NUMERIC_TRACE_LIMIT);

float ssCloudDirectionalCell(float coordinate, float direction) {
    float cell = floor(coordinate);
    if (coordinate == cell && direction < 0.0) cell -= 1.0;
    return cell;
}

bool ssCloudClipAxis(float origin, float direction, float lower, float upper,
                     vec3 lowerNormal, vec3 upperNormal,
                     inout float fieldEnter, inout float fieldExit,
                     inout vec3 entryNormal, inout vec3 exitNormal) {
    if (direction == 0.0) return origin >= lower && origin < upper;
    float nearT;
    float farT;
    vec3 nearNormal;
    vec3 farNormal;
    if (direction > 0.0) {
        nearT = (lower - origin) / direction;
        farT = (upper - origin) / direction;
        nearNormal = lowerNormal;
        farNormal = upperNormal;
    } else {
        nearT = (upper - origin) / direction;
        farT = (lower - origin) / direction;
        nearNormal = upperNormal;
        farNormal = lowerNormal;
    }
    // Y is submitted first and consequently wins the deterministic slab tie.
    if (nearT > fieldEnter) {
        fieldEnter = nearT;
        entryNormal = nearNormal;
    }
    if (farT < fieldExit) {
        fieldExit = farT;
        exitNormal = farNormal;
    }
    return fieldEnter <= fieldExit;
}

vec4 ssCloudAtlasSample(vec3 cell, float width, float depth, float layers, float offsetY) {
    if (cell.x < 0.0 || cell.x >= width || cell.z < 0.0 || cell.z >= depth) return vec4(0.0);
    float atlasX = cell.x;
    float atlasZ = cell.z;
    float atlasY = offsetY + cell.y * depth + atlasZ;
    return texture2D(uCloudAtlas, vec2((atlasX + 0.5) / float(SS_CLOUD_ATLAS_WIDTH),
            (atlasY + 0.5) / float(SS_CLOUD_ATLAS_HEIGHT)));
}

bool ssCloudThreeDOccupied(vec3 cell, float width, float depth, float layers, float offsetY) {
    // CLAMP_TO_EDGE protects atlas rectangles, but never supplies a phantom
    // first/last volume layer to the DDA.
    if (cell.y < 0.0 || cell.y >= layers) return false;
    return ssCloudAtlasSample(cell, width, depth, layers, offsetY).a >= (128.0 / 255.0);
}

bool ssCloudPointInside(vec3 point, float lowerY, float upperY) {
    return point.y >= lowerY && point.y < upperY
            && point.z >= uCloudClip.z && point.z < uCloudClip.w;
}

vec3 ssCloudInitialLeavingNormal(vec3 coordinate, vec3 ordinaryCell, vec3 cellDirection) {
    if (coordinate.x == ordinaryCell.x && cellDirection.x < 0.0) return vec3(-1.0, 0.0, 0.0);
    if (coordinate.y == ordinaryCell.y && cellDirection.y < 0.0) return vec3(0.0, -1.0, 0.0);
    if (coordinate.z == ordinaryCell.z && cellDirection.z < 0.0) return vec3(0.0, 0.0, -1.0);
    return vec3(0.0);
}

bool ssCloudCullBackFace(vec3 outwardNormal, vec3 direction, float cullFlag) {
    return cullFlag > 0.5 && dot(outwardNormal, direction) > 0.0;
}

// state: 0 natural miss, 1 physical/LOD-boundary hit, 2 impossible DDA guard,
// 3 eye-in-solid detail censorship.  Tiers after LOD0 intentionally sample
// their own grouped occupancy at their distance boundary: that transition is a
// stable, declared LOD approximation rather than an exact fine voxel surface.
float ssCloudTraceThreeDStraight(vec3 origin, vec3 direction, float segmentStart, float segmentEnd,
                         float width, float depth, float layers, float offsetY,
                         float xzScale, float yScale, vec2 cacheOrigin, float cullFlag, bool classifyEye,
                         out vec3 outwardNormal, out vec3 baseColor, out int state) {
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    state = 0;
    if (segmentEnd <= segmentStart) return -1.0;

    float cellXZ = uCloudGeometry.x * xzScale;
    float cellY = uCloudGeometry.y * yScale;
    float lowerY = max(uCloudGeometry.z, uCloudClip.x);
    float upperY = min(uCloudGeometry.z + cellY * layers, uCloudClip.y);
    if (upperY <= lowerY || uCloudClip.w <= uCloudClip.z) return -1.0;

    float fieldEnter = -SS_CLOUD_LARGE_T;
    float fieldExit = SS_CLOUD_LARGE_T;
    vec3 entryNormal = vec3(0.0);
    vec3 exitNormal = vec3(0.0);
    if (!ssCloudClipAxis(origin.y, direction.y, lowerY, upperY,
            vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0),
            fieldEnter, fieldExit, entryNormal, exitNormal)
            || !ssCloudClipAxis(origin.z, direction.z, uCloudClip.z, uCloudClip.w,
            vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0),
            fieldEnter, fieldExit, entryNormal, exitNormal)
            || fieldExit < segmentStart) return -1.0;

    float startT = max(max(fieldEnter, segmentStart), 0.0);
    float traceEnd = min(fieldExit, segmentEnd);
    if (traceEnd < startT) return -1.0;
    vec3 startPoint = origin + direction * startT;
    vec3 coordinate = vec3((startPoint.x - cacheOrigin.x) / cellXZ,
            (startPoint.y - uCloudGeometry.z) / cellY,
            (startPoint.z - cacheOrigin.y) / cellXZ);
    vec3 cellDirection = vec3(direction.x / cellXZ, direction.y / cellY, direction.z / cellXZ);
    vec3 cell = vec3(ssCloudDirectionalCell(coordinate.x, cellDirection.x),
            ssCloudDirectionalCell(coordinate.y, cellDirection.y),
            ssCloudDirectionalCell(coordinate.z, cellDirection.z));
    vec3 originCoordinate = vec3((origin.x - cacheOrigin.x) / cellXZ,
            (origin.y - uCloudGeometry.z) / cellY,
            (origin.z - cacheOrigin.y) / cellXZ);
    vec3 ordinaryOriginCell = floor(originCoordinate);
    bool originWasSolid = classifyEye && ssCloudPointInside(origin, lowerY, upperY)
            && ssCloudThreeDOccupied(ordinaryOriginCell, width, depth, layers, offsetY);
    bool solid = ssCloudThreeDOccupied(cell, width, depth, layers, offsetY);
    vec4 lastSolid = originWasSolid
            ? ssCloudAtlasSample(ordinaryOriginCell, width, depth, layers, offsetY) : vec4(0.0);
    if (!originWasSolid && solid) {
        outwardNormal = startT == fieldEnter ? entryNormal : -direction;
        baseColor = ssCloudAtlasSample(cell, width, depth, layers, offsetY).rgb;
        if (!ssCloudCullBackFace(outwardNormal, direction, cullFlag)) {
            state = 1;
            return startT;
        }
        originWasSolid = true;
    }
    if (originWasSolid && !solid) {
        outwardNormal = ssCloudInitialLeavingNormal(originCoordinate, ordinaryOriginCell, cellDirection);
        baseColor = lastSolid.rgb;
        if (!ssCloudCullBackFace(outwardNormal, direction, cullFlag)) {
            state = 1;
            return startT;
        }
        // The eye-side exit was culled. It is now air for this ray, so keep
        // traversing rather than losing a later front-facing re-entry.
        originWasSolid = false;
    }

    float nextX = cellDirection.x > 0.0 ? (cell.x + 1.0 - coordinate.x) / cellDirection.x
            : (cellDirection.x < 0.0 ? (cell.x - coordinate.x) / cellDirection.x : SS_CLOUD_LARGE_T);
    float nextY = cellDirection.y > 0.0 ? (cell.y + 1.0 - coordinate.y) / cellDirection.y
            : (cellDirection.y < 0.0 ? (cell.y - coordinate.y) / cellDirection.y : SS_CLOUD_LARGE_T);
    float nextZ = cellDirection.z > 0.0 ? (cell.z + 1.0 - coordinate.z) / cellDirection.z
            : (cellDirection.z < 0.0 ? (cell.z - coordinate.z) / cellDirection.z : SS_CLOUD_LARGE_T);
    float deltaX = cellDirection.x == 0.0 ? SS_CLOUD_LARGE_T : abs(1.0 / cellDirection.x);
    float deltaY = cellDirection.y == 0.0 ? SS_CLOUD_LARGE_T : abs(1.0 / cellDirection.y);
    float deltaZ = cellDirection.z == 0.0 ? SS_CLOUD_LARGE_T : abs(1.0 / cellDirection.z);
    nextX += startT;
    nextY += startT;
    nextZ += startT;

    bool reachedTraceEnd = false;
    for (int step = 0; step < SS_CLOUD_MAX_STEPS; step++) {
        if (solid) lastSolid = ssCloudAtlasSample(cell, width, depth, layers, offsetY);
        float boundaryT = min(nextX, min(nextY, nextZ));
        if (boundaryT >= traceEnd) {
            reachedTraceEnd = true;
            break;
        }
        bool stepX = nextX == boundaryT;
        bool stepY = nextY == boundaryT;
        bool stepZ = nextZ == boundaryT;
        vec3 leavingNormal = stepX ? vec3(cellDirection.x > 0.0 ? 1.0 : -1.0, 0.0, 0.0)
                : (stepY ? vec3(0.0, cellDirection.y > 0.0 ? 1.0 : -1.0, 0.0)
                : vec3(0.0, 0.0, cellDirection.z > 0.0 ? 1.0 : -1.0));
        if (stepX) { cell.x += cellDirection.x > 0.0 ? 1.0 : -1.0; nextX += deltaX; }
        if (stepY) { cell.y += cellDirection.y > 0.0 ? 1.0 : -1.0; nextY += deltaY; }
        if (stepZ) { cell.z += cellDirection.z > 0.0 ? 1.0 : -1.0; nextZ += deltaZ; }
        solid = ssCloudThreeDOccupied(cell, width, depth, layers, offsetY);
        if (!originWasSolid && solid) {
            outwardNormal = -leavingNormal;
            baseColor = ssCloudAtlasSample(cell, width, depth, layers, offsetY).rgb;
            if (!ssCloudCullBackFace(outwardNormal, direction, cullFlag)) {
                state = 1;
                return boundaryT;
            }
            originWasSolid = true;
        }
        if (originWasSolid && !solid) {
            outwardNormal = leavingNormal;
            baseColor = lastSolid.rgb;
            if (!ssCloudCullBackFace(outwardNormal, direction, cullFlag)) {
                state = 1;
                return boundaryT;
            }
            originWasSolid = false;
        }
    }

    if (originWasSolid) {
        baseColor = lastSolid.rgb;
        if (!reachedTraceEnd) {
            state = 2;
            return traceEnd;
        }
        if (segmentEnd < fieldExit) {
            // Only the final configured detail end has CENSORED_SOLID
            // meaning. Earlier fixed LOD boundaries deliberately hand the
            // ray to their grouped successor rather than behaving like a
            // hidden 512/2048/8192 distance cutoff.
            if (segmentEnd >= uCloudHorizon) {
                state = 3;
                return segmentEnd;
            }
            state = 0;
            return -1.0;
        }
        outwardNormal = exitNormal;
        if (ssCloudCullBackFace(outwardNormal, direction, cullFlag)) {
            state = 0;
            return -1.0;
        }
        state = 1;
        return fieldExit;
    }
    if (!reachedTraceEnd) {
        state = 2;
        return traceEnd;
    }
    return -1.0;
}

#ifdef SS_CLOUD_RAW_TRACE
#include "cloud_style_raw.glsl"
#else
#include "cloud_style.glsl"
#endif

float ssCloudTraceDetailStraight(vec3 origin, vec3 direction, out vec3 outwardNormal,
                         out vec3 baseColor, out int state) {
    float hit;
    int tierState;
    vec3 boundaries = min(vec3(uCloudHorizon), ssCloudTransitionBoundaries());
    float fineEnd = boundaries.x;
    hit = ssCloudTraceThreeDStraight(origin, direction, 0.0, fineEnd,
            float(SS_CLOUD_LOD0_WIDTH), float(SS_CLOUD_LOD0_DEPTH), float(SS_CLOUD_LOD0_LAYERS),
            float(SS_CLOUD_LOD0_OFFSET_Y), float(SS_CLOUD_LOD0_XZ_SCALE), float(SS_CLOUD_LOD0_Y_SCALE),
            uCloudOrigin0,
            uCloudCullFlags.x,
            true, outwardNormal, baseColor, tierState);
    if (hit >= 0.0 || tierState == 2 || tierState == 3) { state = tierState; return hit; }
    float midEnd = boundaries.y;
    hit = ssCloudTraceThreeDStraight(origin, direction, fineEnd, midEnd,
            float(SS_CLOUD_LOD1_WIDTH), float(SS_CLOUD_LOD1_DEPTH), float(SS_CLOUD_LOD1_LAYERS),
            float(SS_CLOUD_LOD1_OFFSET_Y), float(SS_CLOUD_LOD1_XZ_SCALE), float(SS_CLOUD_LOD1_Y_SCALE),
            uCloudOrigin1,
            uCloudCullFlags.y,
            false, outwardNormal, baseColor, tierState);
    if (hit >= 0.0 || tierState == 2) { state = tierState; return hit; }
    float lowEnd = boundaries.z;
    hit = ssCloudTraceThreeDStraight(origin, direction, midEnd, lowEnd,
            float(SS_CLOUD_LOD2_WIDTH), float(SS_CLOUD_LOD2_DEPTH), float(SS_CLOUD_LOD2_LAYERS),
            float(SS_CLOUD_LOD2_OFFSET_Y), float(SS_CLOUD_LOD2_XZ_SCALE), float(SS_CLOUD_LOD2_Y_SCALE),
            uCloudOrigin2,
            uCloudCullFlags.z,
            false, outwardNormal, baseColor, tierState);
    if (hit >= 0.0 || tierState == 2) { state = tierState; return hit; }
    hit = ssCloudTraceThreeDStraight(origin, direction, lowEnd, uCloudHorizon,
            float(SS_CLOUD_LOD3_WIDTH), float(SS_CLOUD_LOD3_DEPTH), float(SS_CLOUD_LOD3_LAYERS),
            float(SS_CLOUD_LOD3_OFFSET_Y), float(SS_CLOUD_LOD3_XZ_SCALE), float(SS_CLOUD_LOD3_Y_SCALE),
            uCloudOrigin3,
            uCloudCullFlags.w,
            false, outwardNormal, baseColor, tierState);
    state = tierState;
    return hit;
}

bool ssCloudTwoDFootprint(vec2 point, vec2 cacheOrigin, float width, float depth, float offsetY,
                          float xzScale, out vec3 color) {
    float cellWidth = uCloudGeometry.x * xzScale;
    vec3 cell = vec3(floor((point.x - cacheOrigin.x) / cellWidth), 0.0,
            floor((point.y - cacheOrigin.y) / cellWidth));
    vec4 atlasSample = ssCloudAtlasSample(cell, width, depth, 1.0, offsetY);
    color = atlasSample.rgb;
    return atlasSample.a >= (128.0 / 255.0);
}

bool ssCloudTailFootprint(vec2 point, float distance, out vec3 color) {
    if (distance < float(SS_CLOUD_LOD_TAIL8_END)) return ssCloudTwoDFootprint(point, uCloudOrigin8,
            float(SS_CLOUD_LOD8_2D_TAIL_WIDTH), float(SS_CLOUD_LOD8_2D_TAIL_DEPTH),
            float(SS_CLOUD_LOD8_2D_TAIL_OFFSET_Y), float(SS_CLOUD_LOD8_2D_TAIL_XZ_SCALE), color);
    if (distance < float(SS_CLOUD_LOD_TAIL9_END)) return ssCloudTwoDFootprint(point, uCloudOrigin9,
            float(SS_CLOUD_LOD9_2D_TAIL_WIDTH), float(SS_CLOUD_LOD9_2D_TAIL_DEPTH),
            float(SS_CLOUD_LOD9_2D_TAIL_OFFSET_Y), float(SS_CLOUD_LOD9_2D_TAIL_XZ_SCALE), color);
    if (distance < float(SS_CLOUD_LOD_TAIL10_END)) return ssCloudTwoDFootprint(point, uCloudOrigin10,
            float(SS_CLOUD_LOD10_2D_TAIL_WIDTH), float(SS_CLOUD_LOD10_2D_TAIL_DEPTH),
            float(SS_CLOUD_LOD10_2D_TAIL_OFFSET_Y), float(SS_CLOUD_LOD10_2D_TAIL_XZ_SCALE), color);
    if (distance < float(SS_CLOUD_LOD_TAIL11_END)) return ssCloudTwoDFootprint(point, uCloudOrigin11,
            float(SS_CLOUD_LOD11_2D_TAIL_WIDTH), float(SS_CLOUD_LOD11_2D_TAIL_DEPTH),
            float(SS_CLOUD_LOD11_2D_TAIL_OFFSET_Y), float(SS_CLOUD_LOD11_2D_TAIL_XZ_SCALE), color);
    return ssCloudTwoDFootprint(point, uCloudOrigin12,
            float(SS_CLOUD_LOD12_2D_TAIL_WIDTH), float(SS_CLOUD_LOD12_2D_TAIL_DEPTH),
            float(SS_CLOUD_LOD12_2D_TAIL_OFFSET_Y), float(SS_CLOUD_LOD12_2D_TAIL_XZ_SCALE), color);
}

// 2D tiers are authorized far-shape approximations. Exact-horizontal rays
// outside the slab miss geometrically; inside the slab they get a directional
// vertical silhouette proxy at the detail boundary so clouds do not vanish.
float ssCloudTraceTwoDStraight(vec3 origin, vec3 direction, out vec3 outwardNormal, out vec3 baseColor, out float farKind) {
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    farKind = 3.0;
    if (uCloudActive == 0 || uCloudHorizon <= 0.0) return -1.0;
    float lowerY = max(uCloudGeometry.z, uCloudClip.x);
    float upperY = min(uCloudGeometry.z + uCloudGeometry.y
            * float(SS_CLOUD_LOD0_LAYERS * SS_CLOUD_LOD0_Y_SCALE), uCloudClip.y);
    float midY = (lowerY + upperY) * 0.5;
    if (upperY <= lowerY || uCloudClip.w <= uCloudClip.z) return -1.0;

    float hitT;
    vec3 hitPoint;
    if (direction.y == 0.0) {
        if (!ssCloudPointInside(origin, lowerY, upperY)) return -1.0;
        hitT = uCloudHorizon;
        hitPoint = origin + direction * hitT;
        outwardNormal = normalize(vec3(-direction.x, 0.0, -direction.z));
    } else {
        float verticalDistance = midY - origin.y;
        if (abs(verticalDistance) > abs(direction.y) * SS_CLOUD_LARGE_T) return -1.0;
        hitT = verticalDistance / direction.y;
        if (hitT != hitT || abs(hitT) >= SS_CLOUD_LARGE_T || hitT < uCloudHorizon) return -1.0;
        hitPoint = origin + direction * hitT;
        outwardNormal = vec3(0.0, direction.y > 0.0 ? -1.0 : 1.0, 0.0);
    }
    if (hitPoint.x != hitPoint.x || hitPoint.z != hitPoint.z
            || abs(hitPoint.x) >= SS_CLOUD_LARGE_T || abs(hitPoint.z) >= SS_CLOUD_LARGE_T
            || hitPoint.z < uCloudClip.z || hitPoint.z >= uCloudClip.w) return -1.0;

    vec2 normalCoordinate = vec2(hitPoint.x, hitPoint.z);
    bool occupied;
    if (hitT < float(SS_CLOUD_LOD_HIGH_2D_END)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin4,
                float(SS_CLOUD_LOD4_2D_HIGH_WIDTH), float(SS_CLOUD_LOD4_2D_HIGH_DEPTH),
                float(SS_CLOUD_LOD4_2D_HIGH_OFFSET_Y), float(SS_CLOUD_LOD4_2D_HIGH_XZ_SCALE), baseColor);
    } else if (hitT < float(SS_CLOUD_LOD_MID_2D_END)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin5,
                float(SS_CLOUD_LOD5_2D_MID_WIDTH), float(SS_CLOUD_LOD5_2D_MID_DEPTH),
                float(SS_CLOUD_LOD5_2D_MID_OFFSET_Y), float(SS_CLOUD_LOD5_2D_MID_XZ_SCALE), baseColor);
    } else if (hitT < float(SS_CLOUD_LOD_LOW_2D_SPLIT)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin6,
                float(SS_CLOUD_LOD6_2D_LOW_WIDTH), float(SS_CLOUD_LOD6_2D_LOW_DEPTH),
                float(SS_CLOUD_LOD6_2D_LOW_OFFSET_Y), float(SS_CLOUD_LOD6_2D_LOW_XZ_SCALE), baseColor);
    } else if (hitT < float(SS_CLOUD_LOD_ENLARGE_START)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin7,
                float(SS_CLOUD_LOD7_2D_LOWER_WIDTH), float(SS_CLOUD_LOD7_2D_LOWER_DEPTH),
                float(SS_CLOUD_LOD7_2D_LOWER_OFFSET_Y), float(SS_CLOUD_LOD7_2D_LOWER_XZ_SCALE), baseColor);
    } else {
        // No cache-boundary miss is treated as empty. The finite tail can be
        // omitted only after the same frozen projection says it is sub-pixel.
        if (hitT < float(SS_CLOUD_LOD_TAIL11_END)) {
            occupied = ssCloudTailFootprint(normalCoordinate, hitT, baseColor);
        } else {
            // Preserve the old numeric support limit; never manufacture a clamped hit near 1e30.
            if (hitT >= SS_CLOUD_LARGE_T) return -1.0;
            float verticalExtent = max(abs(midY - origin.y), upperY - lowerY);
            float verticalDistance = abs(midY - origin.y);
            // Equivalent to angularExtent <= pixelAngle, without another large t division.
            if (uCloudPixelAngularSize > 0.0 && verticalExtent * abs(direction.y)
                    <= uCloudPixelAngularSize * verticalDistance) return -1.0;

            float cellWidth = uCloudGeometry.x * float(SS_CLOUD_LOD12_2D_TAIL_XZ_SCALE);
            vec2 local = floor((normalCoordinate - uCloudOrigin12) / cellWidth);
            bool addressable = hitT <= float(SS_CLOUD_MAX_CACHED_TAIL_DISTANCE)
                    && local.x >= 0.0 && local.x < float(SS_CLOUD_LOD12_2D_TAIL_WIDTH)
                    && local.y >= 0.0 && local.y < float(SS_CLOUD_LOD12_2D_TAIL_DEPTH);
            if (uCloudTailPolicy.y > 0.5 || !addressable) {
                // Only the degraded terminal region erodes an ambiguous float
                // edge. Never smear/dither finite strip coverage into vacuum.
                float edgeError = float(SS_CLOUD_TAIL_EDGE_ERROR_FACTOR) * (abs(origin.z)
                        + abs(direction.z) * hitT + max(abs(uCloudClip.z), abs(uCloudClip.w)) + 1.0);
                if (hitPoint.z < uCloudClip.z + edgeError || hitPoint.z >= uCloudClip.w - edgeError) return -1.0;
            }
            vec3 cachedColor = vec3(0.0);
            float pCache = 0.0;
            float margin = 0.0;
            if (addressable) {
                vec4 cached = ssCloudAtlasSample(vec3(local.x, 0.0, local.y),
                        float(SS_CLOUD_LOD12_2D_TAIL_WIDTH), float(SS_CLOUD_LOD12_2D_TAIL_DEPTH),
                        1.0, float(SS_CLOUD_LOD12_2D_TAIL_OFFSET_Y));
                cachedColor = cached.rgb;
                pCache = cached.a >= (128.0 / 255.0) ? 1.0 : 0.0;
                // CPU freezes W from the actual published page clearance and
                // pixel angle; clamp only protects malformed uniform input.
                float width = clamp(uCloudTailPolicy.x, cellWidth, 8.0 * cellWidth);
                margin = min(min(normalCoordinate.x - uCloudOrigin12.x,
                        uCloudOrigin12.x + float(SS_CLOUD_LOD12_2D_TAIL_WIDTH) * cellWidth - normalCoordinate.x),
                        min(normalCoordinate.y - uCloudOrigin12.y,
                        uCloudOrigin12.y + float(SS_CLOUD_LOD12_2D_TAIL_DEPTH) * cellWidth - normalCoordinate.y));
                margin = min(margin, float(SS_CLOUD_MAX_CACHED_TAIL_DISTANCE) - hitT);
                float handoff = uCloudTailPolicy.y > 0.5 ? 1.0 - smoothstep(0.0, width, margin) : 0.0;
                float pProxy = clamp(uCloudTailPolicy.z, 0.0, 64.0) / 64.0;
                float p = mix(pCache, pProxy, handoff);
                float acceptedRanks = floor(64.0 * p + 0.5);
                if (ssCloudPixelRank() >= acceptedRanks / 64.0 || acceptedRanks <= 0.0) return -1.0;
                baseColor = ((1.0 - handoff) * pCache * cachedColor + handoff * pProxy * vec3(1.0)) / p;
                farKind = handoff > 0.0 ? 4.0 : 3.0;
                occupied = true;
            } else {
                // A terminal page outside its explicit rect is never an atlas edge sample.
                float pProxy = clamp(uCloudTailPolicy.z, 0.0, 64.0) / 64.0;
                float acceptedRanks = floor(64.0 * pProxy + 0.5);
                if (ssCloudPixelRank() >= acceptedRanks / 64.0 || acceptedRanks <= 0.0) return -1.0;
                baseColor = vec3(1.0);
                farKind = 4.0;
                occupied = true;
            }
        }
        // This is the explicitly authorized far-tail screen-size stop, not a
        // distance disappearance. At this point the low 2D proxy's vertical
        // extent projects to no more than one pixel under the frozen camera.
        float angularExtent = max(abs(midY - origin.y), upperY - lowerY) / hitT;
        if (uCloudPixelAngularSize > 0.0 && angularExtent <= uCloudPixelAngularSize) return -1.0;
    }
    return occupied ? hitT : -1.0;
}

// Public query. direction is a normalized eye-origin ray. censored=0 exact
// 3D, 1 inside-solid detail censorship (not a surface), 2 DDA guard failure,
// 3 cached 2D LOD, 4 terminal coverage handoff/proxy.
float ssCloudTraceStraight(vec3 origin, vec3 direction, out vec3 outwardNormal,
                   out vec3 baseColor, out float censored) {
    censored = 0.0;
    if (uCloudActive == 0) {
        outwardNormal = vec3(0.0);
        baseColor = vec3(0.0);
        return -1.0;
    }
    int detailState;
    float detailHit = ssCloudTraceDetailStraight(origin, direction, outwardNormal, baseColor, detailState);
    if (detailState == 2) {
        censored = 2.0;
        return detailHit;
    }
    if (detailState == 3) {
        censored = 1.0;
        return detailHit;
    }
    if (detailHit >= 0.0) return detailHit;
    float farKind;
    float farHit = ssCloudTraceTwoDStraight(origin, direction, outwardNormal, baseColor, farKind);
    if (farHit >= 0.0) {
        censored = farKind;
        return farHit;
    }
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    return -1.0;
}

// A display ray bends through the physical cloud field.  The three DDA axes
// are consequently event surfaces, rather than divisions by a constant
// physical direction.  Every event below is a physical voxel plane expressed
// as its exact display-ray lambda.  This keeps the finite atlas and all four
// detail tiers authoritative; a curved ray may not skip an occupied thin cell
// merely because its display-space chord is almost horizontal.
const double SS_CLOUD_CURVED_NONE = 1.797693134862315708145274237317043567981e308LF;

double ssCloudCurvedEventAfter(double lambda) {
    return lambda + max(0.000001LF, abs(lambda) * 0.000000000001LF);
}

bool ssCloudCurvedUsableEvent(double hit, double after, double axis) {
    return hit > ssCloudCurvedEventAfter(after) && hit < axis;
}

int ssCloudCurvedSign(double value) {
    return value > 0.0LF ? 1 : (value < 0.0LF ? -1 : 0);
}

double ssCloudCurvedNextHeight(vec3 ray, double physicalY, double after, double axis) {
    dvec2 roots;
    int count = ssCurvedHeightRoots(ray, physicalY, roots);
    double candidate = SS_CLOUD_CURVED_NONE;
    if (count >= 1 && ssCloudCurvedUsableEvent(roots.x, after, axis)) candidate = roots.x;
    if (count == 2 && ssCloudCurvedUsableEvent(roots.y, after, axis)) candidate = min(candidate, roots.y);
    return candidate;
}

// Physical Y has one possible extremum along the inverse cylindrical ray.
// It is a traversal event even though it is not a voxel-plane crossing: after
// it, the next Y plane lies in the opposite direction.  Without this event a
// ray that turns inside a layer could miss every descending thin voxel.
double ssCloudCurvedYTurn(vec3 ray, double after, double axis) {
    double dx = double(ray.x);
    double dy = double(ray.y);
    double denominator = dx * dx + dy * dy;
    if (denominator == 0.0LF) return SS_CLOUD_CURVED_NONE;
    double eyeDistance = ssCurvedRadius() - (ssCurvedOriginY() + double(uSSEyeRelative.y));
    double turn = eyeDistance * dy / denominator;
    return ssCloudCurvedUsableEvent(turn, after, axis) ? turn : SS_CLOUD_CURVED_NONE;
}

double ssCloudCurvedNextX(vec3 ray, float cell, float cellWidth, vec2 cacheOrigin,
                           int direction, double after, double axis) {
    if (direction == 0) return SS_CLOUD_CURVED_NONE;
    double plane = double(cacheOrigin.x) + double(direction > 0 ? cell + 1.0 : cell) * double(cellWidth);
    double hit = ssCurvedLongitudeHit(ray, plane);
    return ssCloudCurvedUsableEvent(hit, after, axis) ? hit : SS_CLOUD_CURVED_NONE;
}

void ssCloudCurvedNextY(vec3 ray, float cell, float cellHeight,
                        int direction, double after, double axis,
                        out double event, out bool isTurn) {
    double plane = SS_CLOUD_CURVED_NONE;
    if (direction != 0) {
        double relativePlane = double(uCloudGeometry.z) + double(direction > 0 ? cell + 1.0 : cell) * double(cellHeight);
        plane = ssCloudCurvedNextHeight(ray, ssCurvedOriginY() + relativePlane, after, axis);
    }
    double turn = ssCloudCurvedYTurn(ray, after, axis);
    // A tangent cell-face contact is a direction reversal, never a Y cell
    // crossing.  If it also ties X/Z, those real voxel planes still advance.
    isTurn = turn <= plane;
    event = min(plane, turn);
}

double ssCloudCurvedNextZ(vec3 ray, float cell, float cellWidth, vec2 cacheOrigin,
                           int direction, double after, double axis) {
    if (direction == 0) return SS_CLOUD_CURVED_NONE;
    double plane = double(cacheOrigin.y) + double(direction > 0 ? cell + 1.0 : cell) * double(cellWidth);
    double hit = ssCurvedZHit(ray, plane);
    return ssCloudCurvedUsableEvent(hit, after, axis) ? hit : SS_CLOUD_CURVED_NONE;
}

vec3 ssCloudCurvedPhysicalDirection(vec3 ray, double lambda) {
    return vec3(ssCurvedRayDerivative(ray, lambda));
}

float ssCloudTraceThreeDCurvedInterval(vec3 displayRay, float segmentStart, float segmentEnd,
                                float width, float depth, float layers, float offsetY,
                                float xzScale, float yScale, vec2 cacheOrigin, float cullFlag, bool classifyEye,
                                bool clippedEntry, vec3 entryNormal, bool clippedExit, vec3 exitNormal,
                                out vec3 outwardNormal, out vec3 baseColor, out int state) {
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    state = 0;
    if (segmentEnd <= segmentStart) return -1.0;

    float cellXZ = uCloudGeometry.x * xzScale;
    float cellY = uCloudGeometry.y * yScale;
    float lowerY = max(uCloudGeometry.z, uCloudClip.x);
    float upperY = min(uCloudGeometry.z + cellY * layers, uCloudClip.y);
    if (upperY <= lowerY || uCloudClip.w <= uCloudClip.z) return -1.0;

    double axis = ssCurvedAxisLambda(displayRay);
    double start = max(double(segmentStart), 0.0LF);
    double traceEnd = min(double(segmentEnd), axis);
    if (!(traceEnd >= start)) return -1.0;
    dvec3 startPhysicalD = ssCurvedRayPoint(displayRay, start);
    vec3 startPhysical = vec3(startPhysicalD);
    vec3 startDerivative = ssCloudCurvedPhysicalDirection(displayRay, start);
    vec3 startForwardDerivative = ssCloudCurvedPhysicalDirection(displayRay, ssCloudCurvedEventAfter(start));
    vec3 coordinate = vec3((startPhysical.x - cacheOrigin.x) / cellXZ,
            (startPhysical.y - uCloudGeometry.z) / cellY,
            (startPhysical.z - cacheOrigin.y) / cellXZ);
    int directionX = ssCloudCurvedSign(double(startDerivative.x));
    int directionY = ssCloudCurvedSign(double(startForwardDerivative.y));
    int directionZ = ssCloudCurvedSign(double(startDerivative.z));
    vec3 cell = vec3(ssCloudDirectionalCell(coordinate.x, float(directionX)),
            ssCloudDirectionalCell(coordinate.y, float(directionY)),
            ssCloudDirectionalCell(coordinate.z, float(directionZ)));
    vec3 ordinaryOriginCell = floor(coordinate);
    bool originWasSolid = classifyEye && ssCloudPointInside(startPhysical, lowerY, upperY)
            && ssCloudThreeDOccupied(ordinaryOriginCell, width, depth, layers, offsetY);
    bool solid = ssCloudThreeDOccupied(cell, width, depth, layers, offsetY);
    vec4 lastSolid = originWasSolid ? ssCloudAtlasSample(ordinaryOriginCell, width, depth, layers, offsetY) : vec4(0.0);
    if (!originWasSolid && solid) {
        outwardNormal = clippedEntry ? entryNormal : -normalize(startDerivative);
        baseColor = ssCloudAtlasSample(cell, width, depth, layers, offsetY).rgb;
        if (!ssCloudCullBackFace(outwardNormal, startDerivative, cullFlag)) {
            state = 1;
            return float(start);
        }
        originWasSolid = true;
    }
    if (originWasSolid && !solid) {
        outwardNormal = ssCloudInitialLeavingNormal(coordinate, ordinaryOriginCell,
                vec3(float(directionX), float(directionY), float(directionZ)));
        baseColor = lastSolid.rgb;
        if (!ssCloudCullBackFace(outwardNormal, startDerivative, cullFlag)) {
            state = 1;
            return float(start);
        }
        originWasSolid = false;
    }

    double nextX = ssCloudCurvedNextX(displayRay, cell.x, cellXZ, cacheOrigin, directionX, start, axis);
    double nextY;
    bool nextYIsTurn;
    ssCloudCurvedNextY(displayRay, cell.y, cellY, directionY, start, axis, nextY, nextYIsTurn);
    double nextZ = ssCloudCurvedNextZ(displayRay, cell.z, cellXZ, cacheOrigin, directionZ, start, axis);
    bool reachedTraceEnd = false;
    for (int step = 0; step < SS_CLOUD_MAX_STEPS;) {
        if (solid) lastSolid = ssCloudAtlasSample(cell, width, depth, layers, offsetY);
        double boundary = min(nextX, min(nextY, nextZ));
        if (boundary >= traceEnd || boundary == SS_CLOUD_CURVED_NONE) {
            reachedTraceEnd = true;
            break;
        }
        bool stepX = nextX == boundary;
        bool stepY = nextY == boundary;
        bool stepZ = nextZ == boundary;
        // X/Z voxel steps already know their normal. Only Y events need the
        // derivative before occupancy is known; empty-to-empty traversal must
        // not pay for the full FP64 path metric at every longitudinal plane.
        vec3 derivative = vec3(0.0);
        if (stepY) derivative = ssCloudCurvedPhysicalDirection(displayRay, boundary);
        bool yTurn = stepY && nextYIsTurn;
        vec3 leavingNormal = stepX ? vec3(float(directionX), 0.0, 0.0)
                : (stepY ? vec3(0.0, float(ssCloudCurvedSign(double(derivative.y))), 0.0)
                : vec3(0.0, 0.0, float(directionZ)));
        if (stepX) {
            cell.x += float(directionX);
            nextX = ssCloudCurvedNextX(displayRay, cell.x, cellXZ, cacheOrigin, directionX, boundary, axis);
        }
        if (stepY) {
            int nextDirectionY = ssCloudCurvedSign(double(ssCloudCurvedPhysicalDirection(
                    displayRay, ssCloudCurvedEventAfter(boundary)).y));
            if (!yTurn && nextDirectionY != 0) cell.y += float(nextDirectionY);
            directionY = nextDirectionY;
            ssCloudCurvedNextY(displayRay, cell.y, cellY, directionY, boundary, axis, nextY, nextYIsTurn);
        }
        if (stepZ) {
            cell.z += float(directionZ);
            nextZ = ssCloudCurvedNextZ(displayRay, cell.z, cellXZ, cacheOrigin, directionZ, boundary, axis);
        }
        // A height turning point alone does not leave a voxel.  It only
        // changes which physical Y plane is next; a coincident X/Z event
        // remains a normal voxel transition.
        if (yTurn && !stepX && !stepZ) continue;
        step++;
        solid = ssCloudThreeDOccupied(cell, width, depth, layers, offsetY);
        if (originWasSolid != solid && !stepY)
            derivative = ssCloudCurvedPhysicalDirection(displayRay, boundary);
        if (!originWasSolid && solid) {
            outwardNormal = -leavingNormal;
            baseColor = ssCloudAtlasSample(cell, width, depth, layers, offsetY).rgb;
            if (!ssCloudCullBackFace(outwardNormal, derivative, cullFlag)) {
                state = 1;
                return float(boundary);
            }
            originWasSolid = true;
        }
        if (originWasSolid && !solid) {
            outwardNormal = leavingNormal;
            baseColor = lastSolid.rgb;
            if (!ssCloudCullBackFace(outwardNormal, derivative, cullFlag)) {
                state = 1;
                return float(boundary);
            }
            originWasSolid = false;
        }
    }
    if (originWasSolid) {
        baseColor = lastSolid.rgb;
        if (!reachedTraceEnd) { state = 2; return float(traceEnd); }
        if (clippedExit) {
            outwardNormal = exitNormal;
            if (ssCloudCullBackFace(outwardNormal, ssCloudCurvedPhysicalDirection(displayRay, traceEnd), cullFlag)) {
                return -1.0;
            }
            state = 1;
            return float(traceEnd);
        }
        if (segmentEnd >= uCloudHorizon) { state = 3; return segmentEnd; }
        return -1.0;
    }
    if (!reachedTraceEnd) { state = 2; return float(traceEnd); }
    return -1.0;
}

const int SS_CLOUD_CURVED_CLIP_CUTS = 8;

bool ssCloudCurvedClipInside(dvec3 point, float lowerY, float upperY) {
    return point.y >= double(lowerY) && point.y < double(upperY)
            && point.z >= double(uCloudClip.z) && point.z < double(uCloudClip.w);
}

void ssCloudCurvedInsertClipCut(inout double cuts[SS_CLOUD_CURVED_CLIP_CUTS], inout int count,
                                double value, double minimum, double maximum) {
    if (!(value > minimum && value < maximum) || count >= SS_CLOUD_CURVED_CLIP_CUTS) return;
    for (int index = 0; index < SS_CLOUD_CURVED_CLIP_CUTS; index++) {
        if (index >= count) break;
        if (abs(cuts[index] - value) <= 1.0e-12LF * max(1.0LF, abs(value))) return;
    }
    int insertion = count;
    for (int index = 0; index < SS_CLOUD_CURVED_CLIP_CUTS; index++) {
        if (index >= count) break;
        if (value < cuts[index]) { insertion = index; break; }
    }
    for (int index = SS_CLOUD_CURVED_CLIP_CUTS - 1; index > 0; index--) {
        if (index > insertion && index <= count) cuts[index] = cuts[index - 1];
    }
    cuts[insertion] = value;
    count++;
}

void ssCloudCurvedAddClipHeightCuts(vec3 ray, double physicalY, inout double cuts[SS_CLOUD_CURVED_CLIP_CUTS],
                                    inout int count, double minimum, double maximum) {
    dvec2 roots;
    // A tangent merely touches the clip surface and does not divide an inside
    // interval.  Inserting it would manufacture a false cloud entry/exit.
    if (ssCurvedHeightRoots(ray, physicalY, roots) == 2) {
        ssCloudCurvedInsertClipCut(cuts, count, roots.x, minimum, maximum);
        ssCloudCurvedInsertClipCut(cuts, count, roots.y, minimum, maximum);
    }
}

bool ssCloudCurvedClipBoundary(vec3 ray, double lambda, double minimum, double maximum,
                               float lowerY, float upperY, bool entering, out vec3 normal) {
    normal = vec3(0.0);
    double epsilon = min(lambda - minimum, maximum - lambda);
    epsilon = min(epsilon * 0.5LF, max(0.000001LF, abs(lambda) * 0.000000000001LF));
    if (!(epsilon > 0.0LF)) return false;
    dvec3 before = ssCurvedRayPoint(ray, lambda - epsilon);
    dvec3 after = ssCurvedRayPoint(ray, lambda + epsilon);
    bool insideBefore = ssCloudCurvedClipInside(before, lowerY, upperY);
    bool insideAfter = ssCloudCurvedClipInside(after, lowerY, upperY);
    if (entering ? !(insideAfter && !insideBefore) : !(insideBefore && !insideAfter)) return false;
    // Keep the established Y-before-Z tie ownership when two clip faces meet.
    if (entering) {
        if (after.y >= double(lowerY) && before.y < double(lowerY)) normal = vec3(0.0, -1.0, 0.0);
        else if (after.y < double(upperY) && before.y >= double(upperY)) normal = vec3(0.0, 1.0, 0.0);
        else if (after.z >= double(uCloudClip.z) && before.z < double(uCloudClip.z)) normal = vec3(0.0, 0.0, -1.0);
        else if (after.z < double(uCloudClip.w) && before.z >= double(uCloudClip.w)) normal = vec3(0.0, 0.0, 1.0);
    } else {
        if (before.y >= double(lowerY) && after.y < double(lowerY)) normal = vec3(0.0, -1.0, 0.0);
        else if (before.y < double(upperY) && after.y >= double(upperY)) normal = vec3(0.0, 1.0, 0.0);
        else if (before.z >= double(uCloudClip.z) && after.z < double(uCloudClip.z)) normal = vec3(0.0, 0.0, -1.0);
        else if (before.z < double(uCloudClip.w) && after.z >= double(uCloudClip.w)) normal = vec3(0.0, 0.0, 1.0);
    }
    return normal.x != 0.0 || normal.y != 0.0 || normal.z != 0.0;
}

float ssCloudTraceThreeDCurved(vec3 displayRay, float segmentStart, float segmentEnd,
                                float width, float depth, float layers, float offsetY,
                                float xzScale, float yScale, vec2 cacheOrigin, float cullFlag, bool classifyEye,
                                out vec3 outwardNormal, out vec3 baseColor, out int state) {
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    state = 0;
    float cellY = uCloudGeometry.y * yScale;
    float lowerY = max(uCloudGeometry.z, uCloudClip.x);
    float upperY = min(uCloudGeometry.z + cellY * layers, uCloudClip.y);
    if (segmentEnd <= segmentStart || upperY <= lowerY || uCloudClip.w <= uCloudClip.z) return -1.0;
    double axis = ssCurvedAxisLambda(displayRay);
    double rangeStart = max(double(segmentStart), 0.0LF);
    double rangeEnd = min(double(segmentEnd), axis);
    if (!(rangeEnd > rangeStart)) return -1.0;

    double cuts[SS_CLOUD_CURVED_CLIP_CUTS];
    int cutCount = 2;
    cuts[0] = rangeStart;
    cuts[1] = rangeEnd;
    ssCloudCurvedAddClipHeightCuts(displayRay, ssCurvedOriginY() + double(lowerY), cuts, cutCount, rangeStart, rangeEnd);
    ssCloudCurvedAddClipHeightCuts(displayRay, ssCurvedOriginY() + double(upperY), cuts, cutCount, rangeStart, rangeEnd);
    double zCut = ssCurvedZHit(displayRay, double(uCloudClip.z));
    if (zCut > 0.0LF) ssCloudCurvedInsertClipCut(cuts, cutCount, zCut, rangeStart, rangeEnd);
    zCut = ssCurvedZHit(displayRay, double(uCloudClip.w));
    if (zCut > 0.0LF) ssCloudCurvedInsertClipCut(cuts, cutCount, zCut, rangeStart, rangeEnd);

    for (int interval = 0; interval < SS_CLOUD_CURVED_CLIP_CUTS - 1; interval++) {
        if (interval + 1 >= cutCount) break;
        double start = cuts[interval];
        double end = cuts[interval + 1];
        if (!(end > start) || !ssCloudCurvedClipInside(ssCurvedRayPoint(displayRay, (start + end) * 0.5LF), lowerY, upperY)) continue;
        vec3 entryNormal = vec3(0.0);
        vec3 exitNormal = vec3(0.0);
        bool clippedEntry = start > rangeStart
                && ssCloudCurvedClipBoundary(displayRay, start, rangeStart, rangeEnd, lowerY, upperY, true, entryNormal);
        bool clippedExit = end < rangeEnd
                && ssCloudCurvedClipBoundary(displayRay, end, rangeStart, rangeEnd, lowerY, upperY, false, exitNormal);
        int intervalState;
        float hit = ssCloudTraceThreeDCurvedInterval(displayRay, float(start), float(end), width, depth, layers, offsetY,
                xzScale, yScale, cacheOrigin, cullFlag, classifyEye && start == rangeStart,
                clippedEntry, entryNormal, clippedExit, exitNormal, outwardNormal, baseColor, intervalState);
        if (hit >= 0.0 || intervalState == 2 || intervalState == 3) {
            state = intervalState;
            return hit;
        }
    }
    return -1.0;
}

float ssCloudTraceDetailCurved(vec3 displayRay, out vec3 outwardNormal, out vec3 baseColor, out int state) {
    vec3 boundaries = min(vec3(uCloudHorizon), ssCloudTransitionBoundaries());
    float hit;
    int tierState;
    hit = ssCloudTraceThreeDCurved(displayRay, 0.0, boundaries.x,
            float(SS_CLOUD_LOD0_WIDTH), float(SS_CLOUD_LOD0_DEPTH), float(SS_CLOUD_LOD0_LAYERS),
            float(SS_CLOUD_LOD0_OFFSET_Y), float(SS_CLOUD_LOD0_XZ_SCALE), float(SS_CLOUD_LOD0_Y_SCALE),
            uCloudOrigin0, uCloudCullFlags.x, true, outwardNormal, baseColor, tierState);
    if (hit >= 0.0 || tierState == 2 || tierState == 3) { state = tierState; return hit; }
    hit = ssCloudTraceThreeDCurved(displayRay, boundaries.x, boundaries.y,
            float(SS_CLOUD_LOD1_WIDTH), float(SS_CLOUD_LOD1_DEPTH), float(SS_CLOUD_LOD1_LAYERS),
            float(SS_CLOUD_LOD1_OFFSET_Y), float(SS_CLOUD_LOD1_XZ_SCALE), float(SS_CLOUD_LOD1_Y_SCALE),
            uCloudOrigin1, uCloudCullFlags.y, false, outwardNormal, baseColor, tierState);
    if (hit >= 0.0 || tierState == 2) { state = tierState; return hit; }
    hit = ssCloudTraceThreeDCurved(displayRay, boundaries.y, boundaries.z,
            float(SS_CLOUD_LOD2_WIDTH), float(SS_CLOUD_LOD2_DEPTH), float(SS_CLOUD_LOD2_LAYERS),
            float(SS_CLOUD_LOD2_OFFSET_Y), float(SS_CLOUD_LOD2_XZ_SCALE), float(SS_CLOUD_LOD2_Y_SCALE),
            uCloudOrigin2, uCloudCullFlags.z, false, outwardNormal, baseColor, tierState);
    if (hit >= 0.0 || tierState == 2) { state = tierState; return hit; }
    hit = ssCloudTraceThreeDCurved(displayRay, boundaries.z, uCloudHorizon,
            float(SS_CLOUD_LOD3_WIDTH), float(SS_CLOUD_LOD3_DEPTH), float(SS_CLOUD_LOD3_LAYERS),
            float(SS_CLOUD_LOD3_OFFSET_Y), float(SS_CLOUD_LOD3_XZ_SCALE), float(SS_CLOUD_LOD3_Y_SCALE),
            uCloudOrigin3, uCloudCullFlags.w, false, outwardNormal, baseColor, tierState);
    state = tierState;
    return hit;
}

float ssCloudTraceTwoDCurved(vec3 displayRay, out vec3 outwardNormal, out vec3 baseColor, out float farKind) {
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    farKind = 3.0;
    if (uCloudActive == 0 || uCloudHorizon <= 0.0) return -1.0;
    float lowerY = max(uCloudGeometry.z, uCloudClip.x);
    float upperY = min(uCloudGeometry.z + uCloudGeometry.y
            * float(SS_CLOUD_LOD0_LAYERS * SS_CLOUD_LOD0_Y_SCALE), uCloudClip.y);
    float midY = (lowerY + upperY) * 0.5;
    if (upperY <= lowerY || uCloudClip.w <= uCloudClip.z) return -1.0;
    double axis = ssCurvedAxisLambda(displayRay);
    double hit = double(uCloudHorizon);
    dvec3 pointD = ssCurvedRayPoint(displayRay, hit);
    if (pointD.y < double(lowerY) || pointD.y >= double(upperY)) {
        hit = ssCloudCurvedNextHeight(displayRay, ssCurvedOriginY() + double(midY), hit, axis);
        if (hit == SS_CLOUD_CURVED_NONE || hit >= double(SS_CLOUD_LARGE_T)) return -1.0;
        pointD = ssCurvedRayPoint(displayRay, hit);
    }
    vec3 hitPoint = vec3(pointD);
    float hitT = float(hit);
    vec3 physicalDirection = ssCloudCurvedPhysicalDirection(displayRay, hit);
    outwardNormal = abs(physicalDirection.y) > 0.000001
            ? vec3(0.0, physicalDirection.y > 0.0 ? -1.0 : 1.0, 0.0)
            : normalize(vec3(-physicalDirection.x, 0.0, -physicalDirection.z));
    if (hitPoint.x != hitPoint.x || hitPoint.z != hitPoint.z
            || abs(hitPoint.x) >= SS_CLOUD_LARGE_T || abs(hitPoint.z) >= SS_CLOUD_LARGE_T
            || hitPoint.z < uCloudClip.z || hitPoint.z >= uCloudClip.w) return -1.0;
    vec2 normalCoordinate = hitPoint.xz;
    bool occupied;
    if (hitT < float(SS_CLOUD_LOD_HIGH_2D_END)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin4, float(SS_CLOUD_LOD4_2D_HIGH_WIDTH),
                float(SS_CLOUD_LOD4_2D_HIGH_DEPTH), float(SS_CLOUD_LOD4_2D_HIGH_OFFSET_Y), float(SS_CLOUD_LOD4_2D_HIGH_XZ_SCALE), baseColor);
    } else if (hitT < float(SS_CLOUD_LOD_MID_2D_END)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin5, float(SS_CLOUD_LOD5_2D_MID_WIDTH),
                float(SS_CLOUD_LOD5_2D_MID_DEPTH), float(SS_CLOUD_LOD5_2D_MID_OFFSET_Y), float(SS_CLOUD_LOD5_2D_MID_XZ_SCALE), baseColor);
    } else if (hitT < float(SS_CLOUD_LOD_LOW_2D_SPLIT)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin6, float(SS_CLOUD_LOD6_2D_LOW_WIDTH),
                float(SS_CLOUD_LOD6_2D_LOW_DEPTH), float(SS_CLOUD_LOD6_2D_LOW_OFFSET_Y), float(SS_CLOUD_LOD6_2D_LOW_XZ_SCALE), baseColor);
    } else if (hitT < float(SS_CLOUD_LOD_ENLARGE_START)) {
        occupied = ssCloudTwoDFootprint(normalCoordinate, uCloudOrigin7, float(SS_CLOUD_LOD7_2D_LOWER_WIDTH),
                float(SS_CLOUD_LOD7_2D_LOWER_DEPTH), float(SS_CLOUD_LOD7_2D_LOWER_OFFSET_Y), float(SS_CLOUD_LOD7_2D_LOWER_XZ_SCALE), baseColor);
    } else {
        if (hitT < float(SS_CLOUD_LOD_TAIL11_END)) {
            occupied = ssCloudTailFootprint(normalCoordinate, hitT, baseColor);
        } else {
            if (hitT >= SS_CLOUD_LARGE_T) return -1.0;
            float verticalExtent = max(abs(midY - float(ssCurvedRayPoint(displayRay, 0.0LF).y)), upperY - lowerY);
            float verticalDistance = abs(midY - float(ssCurvedRayPoint(displayRay, 0.0LF).y));
            if (uCloudPixelAngularSize > 0.0 && verticalExtent * abs(displayRay.y)
                    <= uCloudPixelAngularSize * verticalDistance) return -1.0;
            float cellWidth = uCloudGeometry.x * float(SS_CLOUD_LOD12_2D_TAIL_XZ_SCALE);
            vec2 local = floor((normalCoordinate - uCloudOrigin12) / cellWidth);
            bool addressable = hitT <= float(SS_CLOUD_MAX_CACHED_TAIL_DISTANCE)
                    && local.x >= 0.0 && local.x < float(SS_CLOUD_LOD12_2D_TAIL_WIDTH)
                    && local.y >= 0.0 && local.y < float(SS_CLOUD_LOD12_2D_TAIL_DEPTH);
            if (uCloudTailPolicy.y > 0.5 || !addressable) {
                float edgeError = float(SS_CLOUD_TAIL_EDGE_ERROR_FACTOR) * (abs(float(pointD.z))
                        + abs(displayRay.z) * hitT + max(abs(uCloudClip.z), abs(uCloudClip.w)) + 1.0);
                if (hitPoint.z < uCloudClip.z + edgeError || hitPoint.z >= uCloudClip.w - edgeError) return -1.0;
            }
            vec3 cachedColor = vec3(0.0);
            float pCache = 0.0;
            float margin = 0.0;
            if (addressable) {
                vec4 cached = ssCloudAtlasSample(vec3(local.x, 0.0, local.y), float(SS_CLOUD_LOD12_2D_TAIL_WIDTH),
                        float(SS_CLOUD_LOD12_2D_TAIL_DEPTH), 1.0, float(SS_CLOUD_LOD12_2D_TAIL_OFFSET_Y));
                cachedColor = cached.rgb;
                pCache = cached.a >= (128.0 / 255.0) ? 1.0 : 0.0;
                float width = clamp(uCloudTailPolicy.x, cellWidth, 8.0 * cellWidth);
                margin = min(min(normalCoordinate.x - uCloudOrigin12.x,
                        uCloudOrigin12.x + float(SS_CLOUD_LOD12_2D_TAIL_WIDTH) * cellWidth - normalCoordinate.x),
                        min(normalCoordinate.y - uCloudOrigin12.y,
                        uCloudOrigin12.y + float(SS_CLOUD_LOD12_2D_TAIL_DEPTH) * cellWidth - normalCoordinate.y));
                margin = min(margin, float(SS_CLOUD_MAX_CACHED_TAIL_DISTANCE) - hitT);
                float handoff = uCloudTailPolicy.y > 0.5 ? 1.0 - smoothstep(0.0, width, margin) : 0.0;
                float pProxy = clamp(uCloudTailPolicy.z, 0.0, 64.0) / 64.0;
                float p = mix(pCache, pProxy, handoff);
                float acceptedRanks = floor(64.0 * p + 0.5);
                if (ssCloudPixelRank() >= acceptedRanks / 64.0 || acceptedRanks <= 0.0) return -1.0;
                baseColor = ((1.0 - handoff) * pCache * cachedColor + handoff * pProxy * vec3(1.0)) / p;
                farKind = handoff > 0.0 ? 4.0 : 3.0;
                occupied = true;
            } else {
                float pProxy = clamp(uCloudTailPolicy.z, 0.0, 64.0) / 64.0;
                float acceptedRanks = floor(64.0 * pProxy + 0.5);
                if (ssCloudPixelRank() >= acceptedRanks / 64.0 || acceptedRanks <= 0.0) return -1.0;
                baseColor = vec3(1.0);
                farKind = 4.0;
                occupied = true;
            }
        }
        float angularExtent = max(abs(midY - float(ssCurvedRayPoint(displayRay, 0.0LF).y)), upperY - lowerY) / hitT;
        if (uCloudPixelAngularSize > 0.0 && angularExtent <= uCloudPixelAngularSize) return -1.0;
    }
    return occupied ? hitT : -1.0;
}

// Public query.  Its return remains a display-ray lambda even when every
// atlas lookup and culling decision occurred in physical ringworld space.
float ssCloudTrace(vec3 origin, vec3 direction, out vec3 outwardNormal,
                   out vec3 baseColor, out float censored) {
    if (uSSCurvatureEnabled == 0) return ssCloudTraceStraight(origin, direction, outwardNormal, baseColor, censored);
    censored = 0.0;
    if (uCloudActive == 0) {
        outwardNormal = vec3(0.0);
        baseColor = vec3(0.0);
        return -1.0;
    }
    int detailState;
    float detailHit = ssCloudTraceDetailCurved(direction, outwardNormal, baseColor, detailState);
    if (detailState == 2) { censored = 2.0; return detailHit; }
    if (detailState == 3) { censored = 1.0; return detailHit; }
    if (detailHit >= 0.0) return detailHit;
    float farKind;
    float farHit = ssCloudTraceTwoDCurved(direction, outwardNormal, baseColor, farKind);
    if (farHit >= 0.0) { censored = farKind; return farHit; }
    outwardNormal = vec3(0.0);
    baseColor = vec3(0.0);
    return -1.0;
}
