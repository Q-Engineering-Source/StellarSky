package stellarium.world.ring;

import java.util.Objects;

/**
 * Client-only local SKY adjustment derived from one immutable display snapshot.
 *
 * <p>This deliberately samples the committed display phase instead of either
 * client world time or the server's world-owned lighting frame. The caller's
 * packed BLOCK value, minimum-light result, and global client lightmap remain
 * untouched.</p>
 */
public record RingworldDisplayLightField(RingworldDisplaySnapshot snapshot) {
    public RingworldDisplayLightField {
        Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * Returns only the snapshot-local SKY subtraction for a receiver centre.
     *
     * <p>The display field deliberately has no client clock or mutable world
     * fallback: an absent phase is neutral. Receiver height stays a double so
     * render-time section centres above the old vanilla build range retain the
     * same board-plane semantics.</p>
     */
    public int skySubtraction(double x, double y, double z) {
        RingworldSunshade.Phase phase = snapshot.phase();
        if (phase == null || y >= sunshadeUpperFaceY()) {
            return 0;
        }
        if (y >= snapshot.sunshadeHeightBlocks()) {
            return snapshot.sunshade().materialOccupied(phase, x, z) ? 15 : 0;
        }
        return 15 - (int) Math.round(15.0 * snapshot.sunshade().transmittance(phase, x, z));
    }

    /** Changes only the packed SKY nibble for this receiver position. */
    public int shadedPackedLight(int originalPackedLight, double x, double y, double z) {
        int rawSky = RingworldPackedLight.sky(originalPackedLight);
        return RingworldPackedLight.withSky(originalPackedLight,
                Math.max(0, rawSky - skySubtraction(x, y, z)));
    }

    private int sunshadeUpperFaceY() {
        return RingworldPackedLight.upperFaceY(snapshot.sunshadeHeightBlocks(),
                snapshot.sunshadeThicknessBlocks());
    }
}
