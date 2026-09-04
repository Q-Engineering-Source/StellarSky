package stellarium.world.ring;

/** Immutable render-entity origin, not a second clock or an eye/third-person camera offset. */
public record RingworldRenderObserver(double x, double y, double z) {
    public RingworldRenderObserver {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Ringworld render observer must be finite");
        }
    }

    /** Uses the renderer's actual partial tick, without clamping or reading Minecraft's timer. */
    public static RingworldRenderObserver interpolate(double previousX, double previousY, double previousZ,
                                                       double currentX, double currentY, double currentZ,
                                                       float partialTicks) {
        if (!Float.isFinite(partialTicks)) {
            throw new IllegalArgumentException("Render partial ticks must be finite");
        }
        return new RingworldRenderObserver(previousX + (currentX - previousX) * partialTicks,
                previousY + (currentY - previousY) * partialTicks,
                previousZ + (currentZ - previousZ) * partialTicks);
    }
}
