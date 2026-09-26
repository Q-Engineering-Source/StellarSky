package stellarium.client.ring;

import javax.annotation.Nullable;

import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldSpatialQueries;

/**
 * Immutable client-only decision made once for a render scope.  It deliberately
 * contains the frozen display snapshot rather than a Settings reference: a GUI
 * change while stereo eyes are being drawn must not split their optical model.
 */
public final class RingworldSpatialAirFrameOptics {
    public enum Mode {
        LEGACY,
        SPATIAL_AIR
    }

    private final @Nullable RingworldDisplaySnapshot snapshot;
    private final Mode mode;
    private final @Nullable RingworldSpatialQueries queries;
    private final double legacyAtmosphereFade;

    private RingworldSpatialAirFrameOptics(@Nullable RingworldDisplaySnapshot snapshot, Mode mode,
                                           @Nullable RingworldSpatialQueries queries,
                                           double legacyAtmosphereFade) {
        this.snapshot = snapshot;
        this.mode = mode;
        this.queries = queries;
        this.legacyAtmosphereFade = legacyAtmosphereFade;
    }

    public static RingworldSpatialAirFrameOptics freeze(@Nullable RingworldDisplaySnapshot snapshot,
                                                          boolean renderAtmosphere, boolean lowPower) {
		return freeze(snapshot, renderAtmosphere, lowPower, true);
	}

	/** Water, lava and blindness retain vanilla/legacy media semantics for this release. */
	public static RingworldSpatialAirFrameOptics freeze(@Nullable RingworldDisplaySnapshot snapshot,
															  boolean renderAtmosphere, boolean lowPower, boolean normalAirMedium) {
        double fade = snapshot == null ? 1.0 : snapshot.atmosphereFade();
        if (snapshot == null || snapshot.phase() == null || !renderAtmosphere || lowPower || !normalAirMedium) {
            return new RingworldSpatialAirFrameOptics(snapshot, Mode.LEGACY, null, fade);
        }
        RingworldSpatialQueries queries = new RingworldSpatialQueries(snapshot.airProfile(), snapshot.sunshade(),
                snapshot.phase(), snapshot.sunshadeHeightBlocks(), snapshot.sunshadeThicknessBlocks());
        // B owns atmospheric extinction/refraction/scatter.  Existing vacuum RGB32F -> RGBE -> tonemap remains.
        return new RingworldSpatialAirFrameOptics(snapshot, Mode.SPATIAL_AIR, queries, 0.0);
    }

    public @Nullable RingworldDisplaySnapshot snapshot() { return snapshot; }
    public Mode mode() { return mode; }
    public boolean usesSpatialAir() { return mode == Mode.SPATIAL_AIR; }
    public @Nullable RingworldSpatialQueries queries() { return queries; }
    public double legacyAtmosphereFade() { return legacyAtmosphereFade; }
}
