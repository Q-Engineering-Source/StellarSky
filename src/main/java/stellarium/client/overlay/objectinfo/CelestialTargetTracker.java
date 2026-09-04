package stellarium.client.overlay.objectinfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import stellarapi.api.SAPICapabilities;
import stellarapi.api.celestials.CelestialObject;
import stellarapi.api.lib.math.Matrix3;
import stellarapi.api.lib.math.SpCoord;
import stellarapi.api.lib.math.Vector3;
import stellarapi.api.view.ICCoordinates;
import stellarapi.api.world.ICelestialWorld;
import stellarium.StellarSky;
import stellarium.render.extended.ExtendedSkyRenderer;
import stellarium.stellars.StellarManager;
import stellarium.stellars.deepsky.DeepSkyObject;
import stellarium.stellars.layer.StellarCollection;
import stellarium.stellars.layer.StellarObject;
import stellarium.stellars.system.SolarObject;
import stellarium.stellars.system.Moon;
import stellarium.time.StellarSkyTime;
import stellarium.util.MCUtil;
import stellarium.world.StellarScene;

final class CelestialTargetTracker {
    private static final Matrix3 EQUATORIAL_TO_ECLIPTIC = new Matrix3()
            .setAsRotation(1.0, 0.0, 0.0, -0.4090926);
    private static final Matrix3 ECLIPTIC_TO_EQUATORIAL =
            new Matrix3(EQUATORIAL_TO_ECLIPTIC).transpose();

    private CelestialTarget target;
    private SpCoord cursorHorizontal = new SpCoord();
    private int ticksUntilQuery;

    void update(Minecraft mc, double configuredTolerance) {
        if(mc.world == null || mc.getRenderViewEntity() == null) {
            target = null;
            return;
        }
        if(ticksUntilQuery-- > 0)
            return;
        ticksUntilQuery = 4;

        ICelestialWorld celestialWorld = mc.world.getCapability(
                SAPICapabilities.CELESTIAL_CAPABILITY, null);
        if(celestialWorld == null || celestialWorld.getCoordinate() == null) {
            target = null;
            return;
        }
        ICCoordinates coordinate = celestialWorld.getCoordinate();
        Entity viewer = mc.getRenderViewEntity();
        Vec3d minecraftLook = viewer.getLook(1.0f);
        Vector3 groundLook = new Vector3(minecraftLook.x, -minecraftLook.z, minecraftLook.y).normalize();
        cursorHorizontal = new SpCoord().setWithVec(groundLook);
        StellarScene scene = StellarScene.getScene(mc.world);
        boolean hideBelowHorizon = scene != null && scene.getSettings().hideObjectsUnderHorizon();
        // Objects below the horizon are not part of the rendered sky when the
        // active sky set hides them. Do this before catalogue lookup so a
        // hidden object cannot be selected merely because its ray is close.
        if(hideBelowHorizon && cursorHorizontal.y < 0.0) {
            target = null;
            return;
        }
        Vector3 eclipticLook = new Matrix3(coordinate.getProjectionToGround()).transpose()
                .transform(new Vector3(groundLook));
        Vector3 equatorialLook = ECLIPTIC_TO_EQUATORIAL.transform(new Vector3(eclipticLook));

        float fov = MCUtil.getFOVModifier(mc.entityRenderer, 1.0f, true);
        double tolerance = Math.max(0.05, configuredTolerance * fov / 70.0);
        Candidate best = findLegacy(mc, coordinate, groundLook, tolerance, hideBelowHorizon,
                StellarSky.PROXY.getClientSettings().lowPowerRenderer,
                StellarSky.PROXY.getClientSettings().renderMoon);

        double epochYears = StellarSkyTime.getAstronomicalYear(mc.world,
                mc.world.getWorldTime()) - 2016.0;
        ExtendedSkyRenderer.ExtendedTarget extended = ExtendedSkyRenderer.INSTANCE
                .findTarget(equatorialLook, tolerance, epochYears);
        if(extended != null) {
            Vector3 eclipticDirection = EQUATORIAL_TO_ECLIPTIC.transform(
                    new Vector3(extended.equatorialDirection));
            Vector3 groundDirection = coordinate.getProjectionToGround().transform(eclipticDirection);
            if(!isUsableDirection(groundDirection)
                    || (hideBelowHorizon && new SpCoord().setWithVec(groundDirection).y < 0.0))
                extended = null;
            else {
                Candidate candidate = new Candidate(extended.identifier,
                        extended.englishName, extended.chineseName, extended.type,
                        extended.magnitude, groundDirection, extended.equatorialDirection,
                        extended.separationDegrees, null, 1, false);
                if(isBetter(candidate, best))
                best = candidate;
            }
        }
        target = best == null ? null : best.toTarget();
    }

    private static Candidate findLegacy(Minecraft mc, ICCoordinates coordinate,
            Vector3 groundLook, double toleranceDegrees, boolean hideBelowHorizon,
            boolean lowPowerRenderer, boolean renderMoon) {
        StellarManager manager;
        try {
            manager = StellarManager.getManager(mc.world);
        } catch(IllegalStateException exception) {
            return null;
        }
        if(manager.getCelestialManager() == null)
            return null;

        Candidate best = null;
        Set<StellarObject> visited = Collections.newSetFromMap(
                new IdentityHashMap<StellarObject, Boolean>());
        String[] identifiers = { "system", "star", "messier" };
        for(StellarCollection<?> collection : manager.getCelestialManager().getLayers()) {
            for(String identifier : identifiers) {
                for(StellarObject object : collection.getLoadedObjects(identifier)) {
                    if(object instanceof Moon && !renderMoon)
                        continue;
                    Vector3 currentPosition = object.getCurrentPos();
                    // The observer's home planet is represented by a zero
                    // relative vector. It is not a pointable sky target, and
                    // normalizing it would turn all displayed coordinates into NaN.
                    if(!visited.add(object) || !isUsableDirection(currentPosition))
                        continue;
                    Vector3 equatorial;
                    Vector3 ecliptic;
                    if(object instanceof DeepSkyObject) {
                        equatorial = new Vector3(currentPosition).normalize();
                        ecliptic = EQUATORIAL_TO_ECLIPTIC.transform(new Vector3(equatorial));
                    } else {
                        ecliptic = new Vector3(currentPosition).normalize();
                        equatorial = ECLIPTIC_TO_EQUATORIAL.transform(new Vector3(ecliptic));
                    }
                    Vector3 ground = coordinate.getProjectionToGround().transform(ecliptic);
                    if(!isUsableDirection(ground)
                            || (hideBelowHorizon && new SpCoord().setWithVec(ground).y < 0.0))
                        continue;
                    double separation = angularDistanceDegrees(groundLook, ground);
                    double objectTolerance = toleranceDegrees;
                    if(object instanceof SolarObject) {
                        double angularRadius = Math.toDegrees(
                                ((SolarObject) object).getAngularRadiusRadians());
                        // Low-power Sun/Moon billboards intentionally use the
                        // vanilla texture's four-times content scale.
                        if(lowPowerRenderer)
                            angularRadius *= 4.0;
                        objectTolerance = Math.max(objectTolerance, angularRadius);
                    }
                    if(separation > objectTolerance)
                        continue;
                    Double phase = object instanceof SolarObject && object.getObjectType()
                            != stellarapi.api.celestials.EnumObjectType.Star
                            ? ((SolarObject) object).getCurrentPhase() : null;
                    Candidate candidate = new Candidate(identifier(object), englishName(object),
                            chineseName(object), objectTypeName(object),
                            object.getStandardMagnitude(), ground, equatorial, separation,
                            phase, object instanceof SolarObject ? 0 : 1, object instanceof Moon);
                    if(isBetter(candidate, best))
                        best = candidate;
                }
            }
        }
        return best;
    }

    private static double angularDistanceDegrees(Vector3 first, Vector3 second) {
        Vector3 a = new Vector3(first).normalize();
        Vector3 b = new Vector3(second).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, a.dot(b)))));
    }

    private static boolean isUsableDirection(Vector3 direction) {
        if(direction == null || direction.size2() < 1.0e-20)
            return false;
        return Double.isFinite(direction.getX()) && Double.isFinite(direction.getY())
                && Double.isFinite(direction.getZ());
    }

    private static boolean isBetter(Candidate candidate, Candidate current) {
        if(current == null) return true;
        // A visible solar-system disc is the foreground target. Catalogue
        // stars behind the Sun, Moon or a planet must not steal the cursor.
        if(candidate.priority == 0 && current.priority != 0) return true;
        if(candidate.priority != 0 && current.priority == 0) return false;
        if(candidate.separation < current.separation - 1.0e-7) return true;
        return Math.abs(candidate.separation - current.separation) <= 1.0e-7
                && candidate.priority < current.priority;
    }

    private static String identifier(CelestialObject object) {
        if(object instanceof SolarObject)
            return object.getName().getPath().toUpperCase(Locale.ROOT);
        if(object instanceof stellarium.stellars.star.BgStar)
            return "HR " + ((stellarium.stellars.star.BgStar) object).getCatalogNumber();
        if(object instanceof DeepSkyObject)
            return ((DeepSkyObject) object).getCatalogName();
        return object.getName().getPath().replace('_', ' ');
    }

    private static String englishName(CelestialObject object) {
        if(object instanceof SolarObject)
            return solarEnglishName(object.getName().getPath());
        if(object instanceof DeepSkyObject)
            return ((DeepSkyObject) object).getCommonName();
        if(object instanceof stellarium.stellars.star.BgStar)
            return ((stellarium.stellars.star.BgStar) object).getCatalogDesignation().trim();
        return null;
    }

    private static String chineseName(CelestialObject object) {
        if(object instanceof SolarObject)
            return solarChineseName(object.getName().getPath());
        if(object instanceof DeepSkyObject)
            return CelestialNameCatalog.resolveDso(((DeepSkyObject) object).getCatalogName()).chinese;
        return null;
    }

    private static String solarEnglishName(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if(value.equals("sun")) return "Sun";
        if(value.equals("moon")) return "Moon";
        if(value.equals("mercury")) return "Mercury";
        if(value.equals("venus")) return "Venus";
        if(value.equals("earth")) return "Earth";
        if(value.equals("mars")) return "Mars";
        if(value.equals("jupiter")) return "Jupiter";
        if(value.equals("saturn")) return "Saturn";
        if(value.equals("uranus")) return "Uranus";
        if(value.equals("neptune")) return "Neptune";
        return id;
    }

    private static String solarChineseName(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        if(value.equals("sun")) return "太阳";
        if(value.equals("moon")) return "月球";
        if(value.equals("mercury")) return "水星";
        if(value.equals("venus")) return "金星";
        if(value.equals("earth")) return "地球";
        if(value.equals("mars")) return "火星";
        if(value.equals("jupiter")) return "木星";
        if(value.equals("saturn")) return "土星";
        if(value.equals("uranus")) return "天王星";
        if(value.equals("neptune")) return "海王星";
        return null;
    }

    private static String objectTypeName(CelestialObject object) {
        switch(object.getObjectType()) {
        case DeepSkyObject: return "Deep-sky object";
        case Satellite: return "Satellite";
        case Planet: return "Planet";
        case Asteroid: return "Asteroid";
        case AsteroidGroup: return "Asteroid group";
        case Comet: return "Comet";
        case Star: default: return "Star";
        }
    }

    CelestialTarget getTarget(boolean renderMoon) {
        // A config change can precede the next throttled target query.
        return target != null && target.moon && !renderMoon ? null : target;
    }
    SpCoord getCursorHorizontal() { return cursorHorizontal; }

    private static final class Candidate {
        final String identifier, englishName, chineseName, type;
        final double magnitude, separation;
        final Vector3 ground, equatorial;
        final Double phase;
        final int priority;
        final boolean moon;

        Candidate(String identifier, String englishName, String chineseName, String type,
                double magnitude, Vector3 ground, Vector3 equatorial, double separation,
                Double phase, int priority, boolean moon) {
            this.identifier = identifier; this.englishName = englishName;
            this.chineseName = chineseName; this.type = type; this.magnitude = magnitude;
            this.ground = ground; this.equatorial = equatorial;
            this.separation = separation; this.phase = phase; this.priority = priority;
            this.moon = moon;
        }

        CelestialTarget toTarget() {
            SpCoord horizontal = new SpCoord().setWithVec(ground);
            SpCoord eq = new SpCoord().setWithVec(equatorial);
            return new CelestialTarget(identifier, englishName, chineseName, type, magnitude, horizontal.y,
                    normalizeDegrees(90.0 - horizontal.x), eq.x, eq.y, phase, moon);
        }

        private static double normalizeDegrees(double degrees) {
            double result = degrees % 360.0;
            return result < 0.0 ? result + 360.0 : result;
        }
    }
}
