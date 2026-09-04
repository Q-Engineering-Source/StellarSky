package stellarium.world.ring;

import java.util.Objects;

/** Immutable lighting input shared by a world's readers for one sampled time. */
public record RingworldLightFrame(RingworldSunshade sunshade,
                                  int sunshadeHeightBlocks,
                                  int sunshadeThicknessBlocks,
                                  long worldTime) {
    public RingworldLightFrame {
        Objects.requireNonNull(sunshade, "sunshade");
        if (sunshadeHeightBlocks < 0) {
            throw new IllegalArgumentException("sunshadeHeightBlocks must be non-negative");
        }
        if (sunshadeThicknessBlocks <= 0) {
            throw new IllegalArgumentException("sunshadeThicknessBlocks must be greater than zero");
        }
        try {
            Math.addExact(sunshadeHeightBlocks, sunshadeThicknessBlocks);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("sunshade upper face exceeds integer coordinates", exception);
        }
    }

    /** The top face is exclusive: receivers at it and above are sun-facing. */
    public int sunshadeUpperFaceY() {
        return RingworldPackedLight.upperFaceY(sunshadeHeightBlocks, sunshadeThicknessBlocks);
    }

    /**
     * Attenuates the sky remaining after vanilla weather/darkening. Stored sky
     * propagation and block-light values are not part of this transient frame.
     */
    public int skySubtraction(int vanillaSubtraction, double x, int y, double z) {
        int nativeSubtraction = lightLevel(vanillaSubtraction);
        if (y >= sunshadeUpperFaceY()) {
            return nativeSubtraction;
        }
        if (y >= sunshadeHeightBlocks) {
            // The material is opaque even where its lower projected light field
            // uses a soft edge. Gaps retain the caller's weather/darkening.
            RingworldSunshade.EdgeSample sample = sunshade.sample(worldTime, x, z);
            return sample.materialOccupied() ? 15 : nativeSubtraction;
        }
        int remainingSky = 15 - nativeSubtraction;
        return 15 - (int) Math.round(remainingSky * sunshade.transmittance(worldTime, x, z));
    }

    public int effectiveSkyLight(int rawSky, int vanillaSubtraction, double x, int y, double z) {
        return Math.max(0, lightLevel(rawSky) - skySubtraction(vanillaSubtraction, x, y, z));
    }

    public int combinedLight(int rawSky, int blockLight, int vanillaSubtraction, double x, int y, double z) {
        return Math.max(lightLevel(blockLight), effectiveSkyLight(rawSky, vanillaSubtraction, x, y, z));
    }

    /** The client lightmap applies global weather; only the local shade is packed here. */
    public int shadedPackedLight(int originalPackedLight, double x, int y, double z) {
        if (y < 0 || y >= sunshadeUpperFaceY()) {
            return originalPackedLight;
        }
        int rawSky = RingworldPackedLight.sky(originalPackedLight);
        int sky = effectiveSkyLight(rawSky, 0, x, y, z);
        return RingworldPackedLight.withSky(originalPackedLight, sky);
    }

    private static int lightLevel(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
