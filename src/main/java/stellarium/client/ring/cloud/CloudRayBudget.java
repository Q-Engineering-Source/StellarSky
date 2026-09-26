package stellarium.client.ring.cloud;

import java.util.Objects;

/**
 * CPU proof for the four finite 3D cloud-query LOD intervals.
 *
 * <p>The requested horizon is never shortened to fit a shader loop. Fixed
 * tiers are part of the visual contract, so an unsafe user supplied final 3D
 * end fails at uniform preparation instead of quietly changing distance. Far
 * 2D tiers are fixed-cost atlas lookups and are outside this DDA proof.</p>
 */
public final class CloudRayBudget {
    public static final int MAX_STEPS = 2048;
    /** Original one-field entry/vertical reserve, retained as a named contract. */
    public static final int FIXED_ALLOWANCE = 16;
    /** Three remaining LOD boundaries reserve the same finite entry/vertical work. */
    public static final int LOD_TRANSITION_ALLOWANCE = 3 * FIXED_ALLOWANCE;
    public static final int TOTAL_ALLOWANCE = FIXED_ALLOWANCE + LOD_TRANSITION_ALLOWANCE;

    public static final int DEFAULT_REQUESTED_HORIZON = (int) CloudLodLayout.VERY_LOW_3D_END_DISTANCE;
    public static final int MIN_REQUESTED_HORIZON = (int) CloudLodLayout.LOW_3D_END_DISTANCE;
    public static final int MAX_REQUESTED_HORIZON = 65_536;

    private static final double DIAGONAL_CELL_CROSSINGS_PER_BLOCK = StrictMath.sqrt(2.0D);

    private CloudRayBudget() {
    }

    /**
     * Validates the full weighted 3D DDA plan and returns the unmodified
     * requested end. {@code cellXZ} is the finest-level cell width; the next
     * three tiers are 2x, 4x and 8x that width respectively.
     */
    public static double effectiveHorizon(int requestedHorizon, double cellXZ) {
        validateRequestedHorizon(requestedHorizon);
        validateCellSize(cellXZ);
        int proofSteps = requiredDdaSteps(requestedHorizon, cellXZ);
        if (proofSteps > MAX_STEPS) {
            throw new IllegalArgumentException("requestedHorizon requires " + proofSteps
                    + " DDA steps but the fixed cloud query limit is " + MAX_STEPS);
        }
        return requestedHorizon;
    }

    /** Prepares the exact fixed-tier end and matching shader loop proof. */
    public static PreparedHorizon prepare(int requestedHorizon, CloudGeometrySettings geometry) {
        Objects.requireNonNull(geometry, "geometry");
        double effectiveHorizon = effectiveHorizon(requestedHorizon, geometry.cellSizeBlocks());
        return new PreparedHorizon(requestedHorizon, effectiveHorizon, MAX_STEPS,
                requiredDdaSteps(requestedHorizon, geometry.cellSizeBlocks()));
    }

    /** All three boundaries shifted outwards maximize work in the finer tiers. */
    public static PreparedHorizon prepare(int requestedHorizon, CloudGeometrySettings geometry,
                                          CloudLodTransition transition) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(transition, "transition");
        validateRequestedHorizon(requestedHorizon);
        validateCellSize(geometry.cellSizeBlocks());
        double fine = Math.min(requestedHorizon, transition.upperBoundary(0));
        double mid = Math.min(requestedHorizon, transition.upperBoundary(1));
        double low = Math.min(requestedHorizon, transition.upperBoundary(2));
        double weightedBlocks = fine + (mid - fine) / 2.0 + (low - mid) / 4.0
                + (requestedHorizon - low) / 8.0;
        double crossings = StrictMath.ceil(weightedBlocks * DIAGONAL_CELL_CROSSINGS_PER_BLOCK
                / geometry.cellSizeBlocks());
        if (crossings > MAX_STEPS - TOTAL_ALLOWANCE) {
            throw new IllegalArgumentException("Cloud LOD transition exceeds fixed DDA step budget");
        }
        return new PreparedHorizon(requestedHorizon, requestedHorizon, MAX_STEPS,
                (int) crossings + TOTAL_ALLOWANCE);
    }

    /** Exact conservative aggregate DDA crossings for the four 3D tiers. */
    public static int requiredDdaSteps(int requestedHorizon, double cellXZ) {
        validateRequestedHorizon(requestedHorizon);
        validateCellSize(cellXZ);
        double weightedCells = CloudLodLayout.FINE_3D_END_DISTANCE
                / (cellXZ * CloudLodLayout.LOD0_FINE_3D.xzScale())
                + (CloudLodLayout.MID_3D_END_DISTANCE - CloudLodLayout.FINE_3D_END_DISTANCE)
                / (cellXZ * CloudLodLayout.LOD1_MID_3D.xzScale())
                + (CloudLodLayout.LOW_3D_END_DISTANCE - CloudLodLayout.MID_3D_END_DISTANCE)
                / (cellXZ * CloudLodLayout.LOD2_LOW_3D.xzScale())
                + (requestedHorizon - CloudLodLayout.LOW_3D_END_DISTANCE)
                / (cellXZ * CloudLodLayout.LOD3_VERY_LOW_3D.xzScale());
        double crossings = StrictMath.ceil(weightedCells * DIAGONAL_CELL_CROSSINGS_PER_BLOCK);
        if (crossings > Integer.MAX_VALUE - TOTAL_ALLOWANCE) return Integer.MAX_VALUE;
        return (int) crossings + TOTAL_ALLOWANCE;
    }

    /** Runtime-ready values; {@code proofSteps} must not exceed the macro loop bound. */
    public record PreparedHorizon(int requestedHorizon, double effectiveHorizon,
                                  int maximumDdaSteps, int proofSteps) {
        public PreparedHorizon {
            validateRequestedHorizon(requestedHorizon);
            if (!Double.isFinite(effectiveHorizon) || effectiveHorizon != requestedHorizon) {
                throw new IllegalArgumentException("effectiveHorizon must preserve the fixed requested tiers");
            }
            if (maximumDdaSteps != MAX_STEPS) {
                throw new IllegalArgumentException("maximumDdaSteps must match the shader DDA contract");
            }
            if (proofSteps < 1 || proofSteps > MAX_STEPS) {
                throw new IllegalArgumentException("proofSteps must fit the shader DDA contract");
            }
        }
    }

    private static void validateRequestedHorizon(int requestedHorizon) {
        if (requestedHorizon < MIN_REQUESTED_HORIZON || requestedHorizon > MAX_REQUESTED_HORIZON) {
            throw new IllegalArgumentException("requestedHorizon must be within [" + MIN_REQUESTED_HORIZON
                    + ", " + MAX_REQUESTED_HORIZON + "]");
        }
    }

    private static void validateCellSize(double cellXZ) {
        if (!Double.isFinite(cellXZ) || cellXZ <= 0.0D) {
            throw new IllegalArgumentException("cellXZ must be finite and greater than zero");
        }
    }
}
