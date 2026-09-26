package stellarium.client.ring.cloud;

import java.util.Objects;
import java.util.concurrent.Executor;

/** One CPU builder, one coalesced request, and immutable completed packages. */
public final class CloudWindowStreamer implements AutoCloseable {
    private final Executor executor;
    private final PackageBuilder builder;
    private Target latest;
    private Ready ready;
    private Failure failure;
    private Object runner;
    private long serial;
    private long epoch;
    private boolean closed;

    public CloudWindowStreamer(Executor executor) {
        this(executor, CloudWindowStreamer::buildPackage);
    }

    /** Internal scheduling seam: tests control when real production packages finish. */
    CloudWindowStreamer(Executor executor, PackageBuilder builder) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    public synchronized void request(Target target) {
        Objects.requireNonNull(target, "target");
        if (closed) return;
        latest = target;
        serial++;
        failure = null;
        if (runner != null) return;
        Object ticket = new Object();
        runner = ticket;
        try {
            executor.execute(() -> runLatest(ticket));
        } catch (RuntimeException | Error exception) {
            if (runner == ticket) runner = null;
            failure = new Failure(target, exception);
            if (exception instanceof Error error) throw error;
        }
    }

    private void runLatest(Object ticket) {
        while (true) {
            Target target;
            Ready previous;
            long buildSerial;
            long buildEpoch;
            synchronized (this) {
                if (closed || latest == null || runner != ticket) {
                    if (runner == ticket) runner = null;
                    return;
                }
                target = latest;
                buildSerial = serial;
                buildEpoch = epoch;
                previous = ready;
            }
            Ready candidate;
            try {
                CloudWorldCache oldCache = previous != null && previous.target().sameSpecification(target)
                        ? previous.cache() : null;
                candidate = builder.build(buildSerial, target, oldCache);
                if (candidate == null || !candidate.target().equals(target)) {
                    throw new IllegalStateException("Cloud builder returned a package for a different target");
                }
                if (target.rasterRadius() > 0.0) {
                    CloudLodGeometrySet oldGeometry = previous != null && previous.target().sameSpecification(target)
                            ? previous.rasterGeometry() : null;
                    CloudLodGeometrySet geometry = CloudLodGeometrySet.build(candidate.cache(), target.geometry(),
                            target.cloudBaseY(), target.clipBounds(), target.rasterRadius(), oldGeometry,
                            () -> obsolete(buildEpoch,target), true);
                    candidate = new Ready(target, candidate.cache(), candidate.meshKey(), candidate.mesh(), geometry);
                }
            } catch (RuntimeException | Error exception) {
                synchronized (this) {
                    if (runner != ticket) return;
                    if (exception instanceof Error) {
                        // Fatal allocation/linkage errors remain visible even if the camera moved.
                        failure = new Failure(latest == null ? target : latest, exception);
                        runner = null;
                    } else if (!closed && buildEpoch == epoch && target.equals(latest)) {
                        failure = new Failure(target, exception);
                        runner = null;
                        return;
                    } else if (closed || latest == null) {
                        runner = null;
                        return;
                    }
                }
                if (exception instanceof Error error) throw error;
                continue; // An obsolete specification's failure cannot poison the new request.
            }
            synchronized (this) {
                if (closed || runner != ticket || latest == null) {
                    if (runner == ticket) runner = null;
                    return;
                }
                if (buildEpoch == epoch && target.sameSpecification(latest)) {
                    // Retain completed compatible work even if its anchor is old.
                    // The renderer checks coverage; the next build reuses the completed cache.
                    ready = candidate;
                    failure = null;
                }
                if (buildEpoch == epoch && target.equals(latest)) {
                    runner = null;
                    return;
                }
            }
        }
    }

    static Ready buildPackage(long serial, Target target, CloudWorldCache previous) {
        CloudWorldCache cache = CloudWorldCache.around(serial, target.settings(), target.geometry(),
                target.anchorCellX(), target.anchorCellZ(), previous);
        CloudMeshCacheKey key = CloudMeshCacheKey.at(target.anchorCellX(), target.anchorCellZ(), cache.fineMask(),
                target.geometry(), target.cloudBaseY(), target.clipBounds());
        return new Ready(target, cache, key, CloudMeshBuilder.build(key, target.closeWindow()));
    }

    private synchronized boolean obsolete(long buildEpoch, Target target) {
        return closed || epoch!=buildEpoch || latest==null || !target.sameSpecification(latest);
    }

    public synchronized Ready poll(Target target) {
        return ready != null && ready.target().equals(target) ? ready : null;
    }

    public synchronized Ready ready() { return ready; }

    public synchronized Throwable failure(Target target) {
        return failure != null && failure.target().equals(target) ? failure.cause() : null;
    }

    public synchronized void invalidate() {
        serial++;
        epoch++;
        latest = null;
        ready = null;
        failure = null;
        // The existing worker keeps its slot until it observes invalidation.
    }

    @Override public synchronized void close() {
        closed = true;
        invalidate();
    }

    public record Target(CloudFieldSettings settings, CloudGeometrySettings geometry, long anchorCellX,
                         long anchorCellZ, double cloudBaseY, CloudClipBounds clipBounds,
                         boolean closeWindow, long resourceGeneration, double rasterRadius) {
        public Target(CloudFieldSettings settings, CloudGeometrySettings geometry, long anchorCellX,
                      long anchorCellZ, double cloudBaseY, CloudClipBounds clipBounds,
                      boolean closeWindow, long resourceGeneration) {
            this(settings, geometry, anchorCellX, anchorCellZ, cloudBaseY, clipBounds, closeWindow, resourceGeneration, 0.0);
        }
        public Target {
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(clipBounds, "clipBounds");
            if (!Double.isFinite(cloudBaseY)) throw new IllegalArgumentException("cloudBaseY must be finite");
            if (!Double.isFinite(rasterRadius) || rasterRadius < 0.0) throw new IllegalArgumentException("Invalid raster radius");
        }

        public boolean sameSpecification(Target other) {
            return other != null && settings.equals(other.settings) && geometry.equals(other.geometry)
                    && Double.compare(cloudBaseY, other.cloudBaseY) == 0 && clipBounds.equals(other.clipBounds)
                    && closeWindow == other.closeWindow && resourceGeneration == other.resourceGeneration
                    && rasterRadius == other.rasterRadius;
        }
    }

    public record Ready(Target target, CloudWorldCache cache, CloudMeshCacheKey meshKey, CloudMesh mesh,
                        CloudLodGeometrySet rasterGeometry) {
        public Ready(Target target, CloudWorldCache cache, CloudMeshCacheKey meshKey, CloudMesh mesh) {
            this(target, cache, meshKey, mesh, null);
        }
        public Ready {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(cache, "cache");
            Objects.requireNonNull(meshKey, "meshKey");
            Objects.requireNonNull(mesh, "mesh");
            if (rasterGeometry != null && (rasterGeometry.cache() != cache || rasterGeometry.radius() != target.rasterRadius()))
                throw new IllegalArgumentException("Raster cloud geometry must belong to the same atlas and radius");
            if (meshKey.mask() != cache.fineMask() || !meshKey.geometry().equals(target.geometry())
                    || meshKey.anchorCellX() != target.anchorCellX() || meshKey.anchorCellZ() != target.anchorCellZ()
                    || !meshKey.clipBounds().equals(target.clipBounds())
                    || Double.compare(meshKey.cloudBaseY(), target.cloudBaseY()) != 0) {
                throw new IllegalArgumentException("Cloud cache, mesh and target must be one package");
            }
        }

        public boolean covers(Target current, int anchorMargin) {
            // PackageBuilder creates an internal draft before raster generation. Only complete packages are drawable.
            if (target.rasterRadius()>0.0D && rasterGeometry==null) return false;
            if (!target.sameSpecification(current) || anchorMargin < 0) return false;
            long dx, dz;
            try {
                dx = Math.subtractExact(target.anchorCellX(), current.anchorCellX());
                dz = Math.subtractExact(target.anchorCellZ(), current.anchorCellZ());
            } catch (ArithmeticException tooDistant) {
                return false;
            }
            if (dx < -anchorMargin || dx > anchorMargin || dz < -anchorMargin || dz > anchorMargin) return false;
            CloudMask mask = cache.fineMask();
            long x = current.anchorCellX() - mask.originX();
            long z = current.anchorCellZ() - mask.originZ();
            int radius = current.geometry().visibleCellRadius();
            return !mask.repeating() && x - radius >= 0 && x + radius < mask.width()
                    && z - radius >= 0 && z + radius < mask.depth();
        }
    }

    @FunctionalInterface
    interface PackageBuilder {
        Ready build(long serial, Target target, CloudWorldCache previous);
    }

    private record Failure(Target target, Throwable cause) { }
}
