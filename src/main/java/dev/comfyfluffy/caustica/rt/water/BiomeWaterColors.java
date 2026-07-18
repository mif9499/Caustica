package dev.comfyfluffy.caustica.rt.water;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaConfig;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-biome water absorption overrides loaded from {@code config/caustica_water_colors.json}.
 * Default and swamp water are tuned via UI sliders ({@link CausticaConfig.Rt.Water}).
 * Additional per-biome overrides can be placed in the JSON file.
 */
public final class BiomeWaterColors {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final Path CONFIG_PATH = resolvePath();
    private static final Gson GSON = new Gson();

    private static volatile Map<String, float[]> overrides = Collections.emptyMap();

    private BiomeWaterColors() {
    }

    // ── Shared extinction resolver ──────────────────────────────────────────

    /**
     * Resolves the per-channel water extinction for the biome at the given world position.
     * Precedence: swamp config → JSON override → default config.
     * Returns {@code {r, g, b}} coefficients in {@code [0, 1]}.
     */
    public static float[] resolveExtinction(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return defaultExtinction();
        }
        return resolveExtinction(level.getBiome(pos));
    }

    /** Same as {@link #resolveExtinction(BlockPos)} but from an already-resolved biome holder. */
    public static float[] resolveExtinction(Holder<Biome> biomeHolder) {
        // Swamp and mangrove swamp: UI-tunable swamp config.
        Identifier swampId = Identifier.tryParse("minecraft:swamp");
        Identifier mangroveId = Identifier.tryParse("minecraft:mangrove_swamp");
        if ((swampId != null && biomeHolder.is(swampId))
                || (mangroveId != null && biomeHolder.is(mangroveId))) {
            return new float[]{
                    CausticaConfig.Rt.Water.Swamp.r(),
                    CausticaConfig.Rt.Water.Swamp.g(),
                    CausticaConfig.Rt.Water.Swamp.b()};
        }

        // Check JSON per-biome overrides by iterating the map.
        for (Map.Entry<String, float[]> entry : overrides.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id != null && biomeHolder.is(id)) {
                return entry.getValue();
            }
        }

        return defaultExtinction();
    }

    private static float[] defaultExtinction() {
        return new float[]{
                CausticaConfig.Rt.Water.r(),
                CausticaConfig.Rt.Water.g(),
                CausticaConfig.Rt.Water.b()};
    }

    // ── ARGB packing ────────────────────────────────────────────────────────

    /**
     * Packs per-channel absorption coefficients (each in {@code [0, 1]}) into an ARGB int
     * suitable for returning from {@code BiomeColors.getAverageWaterColor()} or for use as
     * a per-vertex fluid tint.
     */
    public static int packExtinction(float r, float g, float b) {
        int ir = Math.clamp(Math.round(r * 255f), 0, 255);
        int ig = Math.clamp(Math.round(g * 255f), 0, 255);
        int ib = Math.clamp(Math.round(b * 255f), 0, 255);
        return (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
    }

    /** Convenience: packs the result of {@link #resolveExtinction(BlockPos)} directly. */
    public static int packResolved(BlockPos pos) {
        float[] e = resolveExtinction(pos);
        return packExtinction(e[0], e[1], e[2]);
    }

    // ── JSON config file I/O ────────────────────────────────────────────────

    /** Loads (or reloads) the per-biome water color overrides from the JSON file. */
    public static synchronized void load() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            overrides = Collections.emptyMap();
            return;
        }
        Map<String, float[]> map = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String key = entry.getKey();
                if (Identifier.tryParse(key) == null) {
                    LOGGER.warn("BiomeWaterColors: skipping invalid biome key '{}'", key);
                    continue;
                }
                JsonObject color = entry.getValue().getAsJsonObject();
                float r = clamp(getFloat(color, "r"), 0f, 1f);
                float g = clamp(getFloat(color, "g"), 0f, 1f);
                float b = clamp(getFloat(color, "b"), 0f, 1f);
                map.put(key, new float[]{r, g, b});
            }
            overrides = Collections.unmodifiableMap(map);
            LOGGER.info("BiomeWaterColors: loaded {} per-biome water override(s)", map.size());
        } catch (IOException e) {
            LOGGER.warn("BiomeWaterColors: failed to read {}: {}", CONFIG_PATH, e.toString());
            overrides = Collections.emptyMap();
        } catch (Exception e) {
            LOGGER.warn("BiomeWaterColors: malformed JSON in {}: {}", CONFIG_PATH, e.toString());
            overrides = Collections.emptyMap();
        }
    }

    /** Returns a read-only view of all loaded JSON overrides (keyed by {@code "namespace:path"}). */
    public static Map<String, float[]> overrides() {
        return overrides;
    }

    /** Writes the default template file if it does not exist. */
    public static void saveDefaultIfMissing() {
        if (Files.isRegularFile(CONFIG_PATH)) {
            return;
        }
        JsonObject root = new JsonObject();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
            LOGGER.info("BiomeWaterColors: wrote empty template to {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.warn("BiomeWaterColors: failed to write default template: {}", e.toString());
        }
    }

    private static float getFloat(JsonObject obj, String member) {
        JsonElement el = obj.get(member);
        return el != null ? el.getAsFloat() : 0f;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static Path resolvePath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica_water_colors.json");
        } catch (Throwable t) {
            return Path.of("config", "caustica_water_colors.json");
        }
    }
}
