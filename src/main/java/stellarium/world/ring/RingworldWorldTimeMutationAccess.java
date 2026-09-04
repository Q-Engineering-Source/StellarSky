package stellarium.world.ring;

/** Per-WorldInfo observability added by the common WorldInfo Mixin. */
public interface RingworldWorldTimeMutationAccess {
    long stellarium$getWorldTimeMutationRevision();
    boolean stellarium$isWorldTimeMutationRevisionSaturated();
}
