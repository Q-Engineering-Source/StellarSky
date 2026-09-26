package stellarium.world.ring;

import java.util.Objects;
import java.util.Optional;

/**
 * Frozen, allocation-light spatial queries shared by future cloud, scatter,
 * and GPU-uniform preparation consumers. It reads no mutable settings.
 */
public final class RingworldSpatialQueries {
    private static final double UNIT_TOLERANCE = 1.0e-12;

    private final RingworldAirProfile airProfile;
    private final RingworldSunshade sunshade;
    private final RingworldSunshade.Phase phase;
    private final double baseY;
    private final double upperBoardY;

    /**
     * Creates queries for one frozen display phase and physical board slab.
     * {@code baseY <= y < baseY + thickness} is the board material slab.
     */
    public RingworldSpatialQueries(RingworldAirProfile airProfile,
                                   RingworldSunshade sunshade,
                                   RingworldSunshade.Phase phase,
                                   double baseY,
                                   double thickness) {
        this.airProfile = Objects.requireNonNull(airProfile, "airProfile");
        this.sunshade = Objects.requireNonNull(sunshade, "sunshade");
        this.phase = Objects.requireNonNull(phase, "phase");
        requireFinite(baseY, "baseY");
        requireFinite(thickness, "thickness");
        if (thickness <= 0.0) {
            throw new IllegalArgumentException("thickness must be greater than zero");
        }
        this.baseY = baseY;
        this.upperBoardY = baseY + thickness;
        if (!Double.isFinite(upperBoardY)) {
            throw new IllegalArgumentException("board upper face must be finite");
        }
    }

    public double densityAt(double y, double z) {
        return airProfile.densityAt(y, z);
    }

    /**
     * Returns direct solar transmission at the receiver's physical height.
     * Above the board is fully open; material within the board slab is opaque;
     * board gaps are open; below it uses the sunshade's existing soft field.
     */
    public double directTransmissionAt(double x, double y, double z) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        if (y >= upperBoardY) return 1.0;
        if (y >= baseY) return sunshade.materialOccupied(phase, x, z) ? 0.0 : 1.0;
        return sunshade.transmittance(phase, x, z);
    }

    /**
     * Clips a unit-length ray to the half-open spatial-air prism and a finite
     * non-negative distance budget. An empty Optional means no positive-length
     * air segment; this API deliberately never encodes absence as infinity.
     * The returned interval represents {@code entry <= t < exit}; an entry on
     * an excluded upper face means the ray enters immediately after that point.
     */
    public Optional<RayInterval> clipAirRay(Ray normalizedRay, double maxDistance) {
        Objects.requireNonNull(normalizedRay, "normalizedRay");
        requireFinite(maxDistance, "maxDistance");
        if (maxDistance < 0.0) throw new IllegalArgumentException("maxDistance must be non-negative");
        return clipToSlab(normalizedRay, maxDistance, airProfile.lowerY(), airProfile.upperY(),
                RingworldStripBounds.BOARD_MIN_Z, RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
    }

    /**
     * Finds the first material contact in the finite board slab analytically.
     * An empty Optional means no hit inside the finite budget; no stepping or
     * synthetic infinity sentinel is used. The hit distance is the earliest
     * parameter at which the periodic material interval is reached.
     */
    public Optional<BoardHit> firstBoardHit(Ray normalizedRay, double maxDistance) {
        Objects.requireNonNull(normalizedRay, "normalizedRay");
        requireFinite(maxDistance, "maxDistance");
        if (maxDistance < 0.0) throw new IllegalArgumentException("maxDistance must be non-negative");
        Optional<RayInterval> boardInterval = clipToSlab(normalizedRay, maxDistance, baseY, upperBoardY,
                RingworldStripBounds.BOARD_MIN_Z, RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE);
        if (boardInterval.isEmpty() || sunshade.isEmpty()) return Optional.empty();

        RayInterval interval = boardInterval.get();
        RingworldSunshade.CameraRelativeBands bands = sunshade.cameraRelativeBands(phase, 0.0, 0.0);
        if (bands.coverage() == RingworldSunshade.BandCoverage.FULL) {
            return Optional.of(new BoardHit(interval.entryDistance()));
        }
        double projectionAtEntry = (normalizedRay.originX() + normalizedRay.directionX() * interval.entryDistance())
                * bands.directionX()
                + (normalizedRay.originZ() + normalizedRay.directionZ() * interval.entryDistance()) * bands.directionZ();
        double oriented = bands.edgeOrientation() * (projectionAtEntry - bands.edgeRelativeToRenderOriginBlocks());
        double position = modulo(oriented, bands.spacingBlocks());
        double slope = bands.edgeOrientation()
                * (normalizedRay.directionX() * bands.directionX() + normalizedRay.directionZ() * bands.directionZ());
        double hitOffset;
        if (position <= bands.panelWidthBlocks()) {
            hitOffset = 0.0;
        } else if (slope > 0.0) {
            hitOffset = (bands.spacingBlocks() - position) / slope;
        } else if (slope < 0.0) {
            hitOffset = (position - bands.panelWidthBlocks()) / -slope;
        } else {
            return Optional.empty();
        }
        double hitDistance = interval.entryDistance() + hitOffset;
        return hitDistance < interval.exitDistance() ? Optional.of(new BoardHit(hitDistance)) : Optional.empty();
    }

    private static Optional<RayInterval> clipToSlab(Ray ray, double budget,
                                                     double minY, double maxY, double minZ, double maxZ) {
        double entry = 0.0;
        double exit = budget;
        double[] y = clipAxis(ray.originY(), ray.directionY(), minY, maxY, entry, exit);
        if (y == null) return Optional.empty();
        entry = y[0];
        exit = y[1];
        double[] z = clipAxis(ray.originZ(), ray.directionZ(), minZ, maxZ, entry, exit);
        if (z == null || !(z[1] > z[0])) return Optional.empty();
        return Optional.of(new RayInterval(z[0], z[1]));
    }

    private static double[] clipAxis(double origin, double direction, double min, double max,
                                     double entry, double exit) {
        if (direction == 0.0) {
            return origin >= min && origin < max ? new double[] {entry, exit} : null;
        }
        double first = (min - origin) / direction;
        double second = (max - origin) / direction;
        double low = Math.min(first, second);
        double high = Math.max(first, second);
        double clippedEntry = Math.max(entry, low);
        double clippedExit = Math.min(exit, high);
        return clippedExit > clippedEntry ? new double[] {clippedEntry, clippedExit} : null;
    }

    private static double modulo(double value, double period) {
        double result = value % period;
        return result < 0.0 ? result + period : result;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    /** A finite, normalized spatial ray. */
    public record Ray(double originX, double originY, double originZ,
                      double directionX, double directionY, double directionZ) {
        public Ray {
            requireFinite(originX, "originX");
            requireFinite(originY, "originY");
            requireFinite(originZ, "originZ");
            requireFinite(directionX, "directionX");
            requireFinite(directionY, "directionY");
            requireFinite(directionZ, "directionZ");
            double squaredLength = directionX * directionX + directionY * directionY + directionZ * directionZ;
            if (!Double.isFinite(squaredLength) || Math.abs(squaredLength - 1.0) > UNIT_TOLERANCE) {
                throw new IllegalArgumentException("Ray direction must be normalized");
            }
        }
    }

    /** Finite ray distance interval with an inclusive entry and exclusive exit. */
    public record RayInterval(double entryDistance, double exitDistance) {
        public RayInterval {
            requireFinite(entryDistance, "entryDistance");
            requireFinite(exitDistance, "exitDistance");
            if (entryDistance < 0.0 || exitDistance <= entryDistance) {
                throw new IllegalArgumentException("Ray interval must have positive finite length");
            }
        }
    }

    /** First analytic material-contact distance on the frozen board phase. */
    public record BoardHit(double distance) {
        public BoardHit {
            requireFinite(distance, "distance");
            if (distance < 0.0) throw new IllegalArgumentException("distance must be non-negative");
        }
    }
}
