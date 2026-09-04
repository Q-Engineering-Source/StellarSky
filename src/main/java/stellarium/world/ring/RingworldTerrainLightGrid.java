package stellarium.world.ring;

import java.util.Objects;

/**
 * One render-time lightmap sample for a vanilla 16-cube {@code RenderChunk}.
 *
 * <p>Vanilla's fixed-function terrain path can translate a lightmap texture
 * matrix per rendered chunk, not per baked vertex. Its receiver is therefore
 * the actual section centre ({@code origin + 8}) from the immutable display
 * field captured for this render. Origins become doubles before the addition
 * so extreme chunk coordinates cannot overflow an {@code int} first.</p>
 */
public final class RingworldTerrainLightGrid {
    private static final double SECTION_HALF_SIZE = 8.0;

    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final int sectionSkySubtraction;

    public RingworldTerrainLightGrid(RingworldDisplayLightField field, int originX, int originY, int originZ) {
        Objects.requireNonNull(field, "field");
        centerX = sectionCenter(originX);
        centerY = sectionCenter(originY);
        centerZ = sectionCenter(originZ);
        sectionSkySubtraction = field.skySubtraction(centerX, centerY, centerZ);
    }

    /** Allocation-free hot path used once per actual terrain-section draw. */
    public static int sectionSkySubtraction(RingworldDisplayLightField field,
                                            int originX, int originY, int originZ) {
        Objects.requireNonNull(field, "field");
        return field.skySubtraction(sectionCenter(originX), sectionCenter(originY), sectionCenter(originZ));
    }

    private static double sectionCenter(int origin) {
        return (double) origin + SECTION_HALF_SIZE;
    }

    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    public double centerZ() {
        return centerZ;
    }

    public int sectionSkySubtraction() {
        return sectionSkySubtraction;
    }
}
