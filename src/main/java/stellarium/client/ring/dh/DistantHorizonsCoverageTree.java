package stellarium.client.ring.dh;

/** Render-tree lifetime, distinct from world identity and from individual buffer uploads. */
public interface DistantHorizonsCoverageTree {
    DistantHorizonsCoverageTransfer.Generation stellarium$coverageGeneration();
}
