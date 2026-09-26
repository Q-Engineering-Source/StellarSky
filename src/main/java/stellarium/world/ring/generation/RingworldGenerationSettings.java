package stellarium.world.ring.generation;

/** Immutable generation choice, independent of visual ring radius or moving sunshade state. */
public record RingworldGenerationSettings(boolean enabled) {
    /** Only used when no persisted policy exists; existing worlds need a separate migration. */
    public static RingworldGenerationSettings forUnrecordedWorld(boolean alreadyInitialized, boolean requested) {
        return new RingworldGenerationSettings(requested && !alreadyInitialized);
    }
}
