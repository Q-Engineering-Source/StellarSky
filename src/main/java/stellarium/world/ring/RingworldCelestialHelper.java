package stellarium.world.ring;

import stellarapi.api.celestials.CelestialObject;
import stellarapi.api.view.IAtmosphereEffect;
import stellarapi.api.view.ICCoordinates;
import stellarapi.example.CelestialHelperSimple;

/**
 * The unoccluded ring sun stays at zenith. Local shade is applied by the light
 * readers, never to this world-global base or the global client lightmap.
 */
public final class RingworldCelestialHelper extends CelestialHelperSimple {
    public RingworldCelestialHelper(float sunlightMultiplier, CelestialObject sun, CelestialObject moon,
                                   ICCoordinates coordinates, IAtmosphereEffect atmosphere) {
        super(sunlightMultiplier, 1.0f, sun, moon, coordinates, atmosphere);
    }

    @Override
    public float calculateCelestialAngle(long worldTime, float partialTicks) {
        return 0.0f;
    }

    @Override
    public float getSunHeightFactor(float partialTicks) {
        return 1.0f;
    }
}
