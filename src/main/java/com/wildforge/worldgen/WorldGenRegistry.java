package com.wildforge.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unified, validated registry for the generated worldgen catalogs.
 * The legacy fixed-size TerrainMesh is intentionally NOT replaced here.
 */
public record WorldGenRegistry(
        WorldGenSettings terrain,
        BiomeCatalog surfaceBiomes,
        UndergroundBiomeCatalog undergroundBiomes,
        UndergroundGenSettings undergroundSettings) {

    public WorldGenRegistry {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(surfaceBiomes, "surfaceBiomes");
        Objects.requireNonNull(undergroundBiomes, "undergroundBiomes");
        Objects.requireNonNull(undergroundSettings, "undergroundSettings");
    }

    public WorldGenerator generator(long seed) {
        return new WorldGenerator(seed, terrain, surfaceBiomes,
                undergroundBiomes, undergroundSettings);
    }

    public static WorldGenRegistry loadBuiltIn() throws IOException {
        WorldGenRegistry data = new WorldGenRegistry(
                WorldGenConfigLoader.loadBuiltIn(),
                BiomeCatalog.loadBuiltIn(),
                UndergroundBiomeCatalog.loadBuiltIn(),
                UndergroundGenSettings.loadBuiltIn());
        validateTextures(data);
        return data;
    }

    /** Catch missing/mismatched biome texture metadata before game startup. */
    private static void validateTextures(WorldGenRegistry registry) throws IOException {
        String index = "data/wildforge/texture_index.json";
        try (InputStream stream = WorldGenRegistry.class.getClassLoader().getResourceAsStream(index)) {
            if (stream == null) throw new IOException("Missing generated texture index");
            JsonObject catalog = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray allTextures = catalog.getAsJsonArray("textures");
            if (allTextures == null) throw new IOException("Missing textures array");
            Map<String, String> hints = new HashMap<>();
            for (JsonElement item : allTextures) {
                JsonObject tex = item.getAsJsonObject();
                String id = tex.get("id").getAsString();
                if (hints.putIfAbsent(id, tex.get("model_hint").getAsString()) != null) {
                    throw new IOException("Duplicate texture ID " + id);
                }
            }
            for (BiomeProfile surface : registry.surfaceBiomes().all().values()) {
                requireTexture(hints, surface.topTexture());
                requireTexture(hints, surface.fillerTexture());
            }
            for (UndergroundBiomeProfile underground : registry.undergroundBiomes().all().values()) {
                for (String texture : underground.existingTextures()) requireTexture(hints, texture);
                for (String texture : underground.newTextures()) {
                    String hint = requireTexture(hints, texture);
                    if (hint == null || hint.isBlank()) {
                        throw new IOException("Missing model hint for " + texture);
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid generated texture index", e);
        }
    }

    private static String requireTexture(Map<String, String> hints, String name) throws IOException {
        String hint = hints.get("wildforge:" + name);
        if (hint == null) throw new IOException("Biome references unindexed texture wildforge:" + name);
        return hint;
    }
}
