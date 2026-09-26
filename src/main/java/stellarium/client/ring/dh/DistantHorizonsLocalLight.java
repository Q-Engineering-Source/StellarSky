package stellarium.client.ring.dh;

import stellarium.world.ring.RingworldDisplaySnapshot;
import stellarium.world.ring.RingworldRenderObserver;
import stellarium.world.ring.RingworldStripBounds;
import stellarium.world.ring.RingworldSunshade;

/** Frozen, origin-relative parameters; never reads mutable world time or changes LOD data. */
public record DistantHorizonsLocalLight(int mode, double[] band, double[] bounds, double[] direction) {
    public static DistantHorizonsLocalLight from(RingworldDisplaySnapshot snapshot, RingworldRenderObserver origin) {
        var shade = snapshot.sunshade();
        var phase = snapshot.phase();
        var bands = shade.cameraRelativeBands(phase == null ? shade.phase(0, 0, 0) : phase, origin.x(), origin.z());
        return new DistantHorizonsLocalLight(shade.isEmpty() ? 0 : phase == null ? 1 : 2,
                new double[]{bands.spacingBlocks(), bands.panelWidthBlocks(),
                        bands.edgeRelativeToRenderOriginBlocks() + bands.edgeOrientation() * bands.panelWidthBlocks() * 0.5,
                        shade.featherBlocks()},
                new double[]{snapshot.sunshadeHeightBlocks() - origin.y(),
                        (double) snapshot.sunshadeHeightBlocks() + snapshot.sunshadeThicknessBlocks() - origin.y(),
                        RingworldStripBounds.BOARD_MIN_Z - origin.z(), RingworldStripBounds.BOARD_MAX_Z_EXCLUSIVE - origin.z()},
                new double[]{bands.directionX(), bands.directionZ(), shade.sideFeatherBlocks(),
                        bands.coverage() == RingworldSunshade.BandCoverage.FULL ? 1 : 0});
    }
}
