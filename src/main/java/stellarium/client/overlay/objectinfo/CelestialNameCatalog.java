package stellarium.client.overlay.objectinfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import stellarium.StellarSky;
import stellarium.build.StellarBuildProfile;

/** Names shared by the object picker and the extended Stellarium catalogues. */
public final class CelestialNameCatalog {
    private static final Map<Integer, String> ENGLISH_STARS = loadStars(
            "/assets/stellarium/catalog/names/common_star_names.fab");
    private static final Map<Integer, String> CHINESE_STARS = loadStars(
            "/assets/stellarium/catalog/names/star_names.zh_CN.fab");
    private static final Map<String, String> CHINESE_DSO = StellarBuildProfile.INCLUDE_DEEP_SKY
            ? loadDso("/assets/stellarium/catalog/names/dso_names.zh_CN.fab")
            : Collections.<String, String>emptyMap();

    private static final Map<String, String> ENGLISH_DSO;
    static {
        Map<String, String> names = new HashMap<String, String>();
        if(StellarBuildProfile.INCLUDE_DEEP_SKY) {
            names.put("M31", "Andromeda Galaxy");
            names.put("M42", "Orion Nebula");
            names.put("M45", "Pleiades");
            names.put("M44", "Beehive Cluster");
            names.put("M7", "Ptolemy Cluster");
            names.put("M13", "Hercules Cluster");
            names.put("M51", "Whirlpool Galaxy");
            names.put("M57", "Ring Nebula");
            names.put("M81", "Bode's Galaxy");
            names.put("M82", "Cigar Galaxy");
            names.put("M87", "Virgo A");
            names.put("M101", "Pinwheel Galaxy");
            names.putAll(loadEnglishDso(
                    "/assets/stellarium/catalog/names/modern_iau.json"));
        }
        ENGLISH_DSO = Collections.unmodifiableMap(names);
    }

    private CelestialNameCatalog() { }

    public static LocalizedName resolveStar(int hip, long gaiaId) {
        String identifier = hip > 0 ? "HIP " + hip
                : "Gaia DR3 " + Long.toUnsignedString(gaiaId);
        String english = hip > 0 ? ENGLISH_STARS.get(hip) : null;
        String chinese = hip > 0 ? CHINESE_STARS.get(hip) : null;
        return new LocalizedName(identifier, english, chinese);
    }

    public static LocalizedName resolveDso(String designation) {
        String identifier = designation == null ? "DSO" : designation.trim();
        String key = normalizeDso(identifier);
        return new LocalizedName(identifier, ENGLISH_DSO.get(key), CHINESE_DSO.get(key));
    }

    private static String normalizeDso(String value) {
        return value.toUpperCase(Locale.ROOT).replace(" ", "");
    }

    private static Map<Integer, String> loadStars(String resource) {
        Map<Integer, String> result = new HashMap<Integer, String>();
        try {
            InputStream stream = CelestialNameCatalog.class.getResourceAsStream(resource);
            if(stream == null) return result;
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8))) {
                String line;
                while((line = reader.readLine()) != null) {
                    int pipe = line.indexOf('|');
                    int marker = line.indexOf("_(\"");
                    int end = marker < 0 ? -1 : line.indexOf("\")", marker + 3);
                    if(pipe <= 0 || marker < pipe || end <= marker + 3) continue;
                    try {
                        int hip = Integer.parseInt(line.substring(0, pipe).trim());
                        String name = line.substring(marker + 3, end).trim();
                        if(!name.isEmpty() && !result.containsKey(hip))
                            result.put(hip, name);
                    } catch(NumberFormatException ignored) { }
                }
            }
        } catch(IOException exception) {
            StellarSky.INSTANCE.getLogger().warn("Unable to load star names {}", resource,
                    exception);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> loadDso(String resource) {
        Map<String, String> result = new HashMap<String, String>();
        try {
            InputStream stream = CelestialNameCatalog.class.getResourceAsStream(resource);
            if(stream == null) return result;
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8))) {
                String line;
                while((line = reader.readLine()) != null) {
                    int pipe = line.indexOf('|');
                    int marker = line.indexOf("_(\"");
                    int end = marker < 0 ? -1 : line.indexOf("\")", marker + 3);
                    if(pipe <= 0 || marker < pipe || end <= marker + 3) continue;
                    String key = normalizeDso(line.substring(0, pipe).trim());
                    String name = line.substring(marker + 3, end).trim();
                    if(!key.isEmpty() && !name.isEmpty()) result.put(key, name);
                }
            }
        } catch(IOException exception) {
            StellarSky.INSTANCE.getLogger().warn("Unable to load deep-sky names {}", resource,
                    exception);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> loadEnglishDso(String resource) {
        Map<String, String> result = new HashMap<String, String>();
        try {
            InputStream stream = CelestialNameCatalog.class.getResourceAsStream(resource);
            if(stream == null) return result;
            try(InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
                JsonObject commonNames = root.getAsJsonObject("common_names");
                if(commonNames == null) return result;
                for(Map.Entry<String, JsonElement> entry : commonNames.entrySet()) {
                    String key = normalizeDso(entry.getKey());
                    if(key.startsWith("HIP") || !entry.getValue().isJsonArray()) continue;
                    JsonArray values = entry.getValue().getAsJsonArray();
                    if(values.size() == 0 || !values.get(0).isJsonObject()) continue;
                    JsonObject value = values.get(0).getAsJsonObject();
                    if(value.has("english"))
                        result.put(key, value.get("english").getAsString());
                    else if(value.has("native"))
                        result.put(key, value.get("native").getAsString());
                }
            }
        } catch(IOException | RuntimeException exception) {
            StellarSky.INSTANCE.getLogger().warn("Unable to load English deep-sky names {}",
                    resource, exception);
        }
        return result;
    }

    public static final class LocalizedName {
        public final String identifier;
        public final String english;
        public final String chinese;

        LocalizedName(String identifier, String english, String chinese) {
            this.identifier = identifier;
            this.english = english;
            this.chinese = chinese;
        }
    }
}
