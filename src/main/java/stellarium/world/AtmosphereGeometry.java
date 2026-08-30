package stellarium.world;

import net.minecraft.world.World;

/** Shared observer-height and apparent-horizon calculations for both renderers. */
public final class AtmosphereGeometry {
    private AtmosphereGeometry() { }

    public static double resolveHeight(World world, double observerY,
            PerDimensionSettings settings) {
        double sourceHeight;
        switch(settings.getAtmosphereHeightMode()) {
        case OBSERVER_ALTITUDE:
            stellarium.api.observer.ObserverSkyContext context =
                    ObserverSkyState.getClientContext(world);
            sourceHeight = context == null ? 0.0
                    : context.getAltitude() / settings.getAtmospherePhysicalScaleHeight();
            break;
        case FIXED:
            sourceHeight = 0.0;
            break;
        case MINECRAFT_Y:
        default:
            sourceHeight = (observerY - world.getHorizon()) / world.getHeight();
            break;
        }

        double height = settings.getHeightOffset()
                + sourceHeight * settings.getHeightIncScale();
        return Math.max(0.0, Math.min(height,
                Math.max(0.0, settings.getOuterRadius()
                        - settings.getInnerRadius() - 0.001)));
    }

    public static double horizonDepressionDegrees(double height, double innerRadius) {
        if(height <= 0.0 || innerRadius <= 0.0)
            return 0.0;
        double ratio = innerRadius / (innerRadius + height);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, ratio))));
    }
}
