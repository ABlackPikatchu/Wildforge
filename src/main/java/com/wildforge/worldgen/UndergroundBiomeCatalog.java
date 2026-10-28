package com.wildforge.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JAR-safe index loader for 12 generated underground-biome JSON profiles. */
public final class UndergroundBiomeCatalog {
    private static final String PREFIX = "data/wildforge/worldgen/underground_biomes/";
    private static final String INDEX = PREFIX + "index.json";
    private final Map<UndergroundBiome, UndergroundBiomeProfile> profiles;

    private UndergroundBiomeCatalog(Map<UndergroundBiome, UndergroundBiomeProfile> values) {
        EnumMap<UndergroundBiome, UndergroundBiomeProfile> copy = new EnumMap<>(UndergroundBiome.class);
        copy.putAll(values);
        if (copy.size() != UndergroundBiome.values().length) {
            throw new IllegalArgumentException("Missing underground profiles");
        }
        for (UndergroundBiome biome : UndergroundBiome.values()) {
            if (!copy.containsKey(biome)) throw new IllegalArgumentException("Missing " + biome);
        }
        profiles = Map.copyOf(copy);
    }

    public UndergroundBiomeProfile get(UndergroundBiome biome) {
        return profiles.get(biome);
    }

    public Map<UndergroundBiome, UndergroundBiomeProfile> all() {
        return profiles;
    }

    public static UndergroundBiomeCatalog loadBuiltIn() throws IOException {
        return load(UndergroundBiomeCatalog.class.getClassLoader());
    }

    public static UndergroundBiomeCatalog load(ClassLoader loader) throws IOException {
        JsonObject index = read(loader, INDEX);
        if (!"wildforge-underground-profiles-v1".equals(index.get("schema").getAsString())) {
            throw new IOException("Unsupported underground biome index version");
        }
        JsonArray paths = index.getAsJsonArray("biomes");
        if (paths == null || paths.size() != 12) throw new IOException("Expected 12 underground biome index entries");
        Map<UndergroundBiome, UndergroundBiomeProfile> profiles = new EnumMap<>(UndergroundBiome.class);
        Set<String> indexed = new HashSet<>();
        for (JsonElement value : paths) {
            String path = value.getAsString();
            if (!path.startsWith(PREFIX) || !path.endsWith(".json")
                    || path.contains("..") || path.equals(INDEX) || !indexed.add(path)) {
                throw new IOException("Invalid or duplicated underground profile path: " + path);
            }
            UndergroundBiomeProfile profile = parse(read(loader, path));
            String requiredPath = PREFIX + profile.biome().name().toLowerCase(java.util.Locale.ROOT) + ".json";
            if (!requiredPath.equals(path)) throw new IOException("Biome ID/path mismatch in " + path);
            if (profiles.putIfAbsent(profile.biome(), profile) != null) {
                throw new IOException("Duplicate underground biome " + profile.biome());
            }
        }
        if (profiles.size() != 12) throw new IOException("Not all underground biomes were indexed");
        for (UndergroundBiomeProfile profile : profiles.values()) {
            for (UndergroundBiome neighbor : profile.transitions()) {
                if (!profiles.containsKey(neighbor) || neighbor == profile.biome()) {
                    throw new IOException("Invalid transition for " + profile.biome() + " -> " + neighbor);
                }
            }
        }
        return new UndergroundBiomeCatalog(profiles);
    }

    public static UndergroundBiomeCatalog loadBuiltInUnchecked() {
        try {
            return loadBuiltIn();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not load generated underground biomes", failure);
        }
    }

    private static JsonObject read(ClassLoader loader, String path) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing underground biome resource: " + path);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (RuntimeException failure) {
            throw new IOException("Invalid underground biome JSON: " + path, failure);
        }
    }

    private static UndergroundBiomeProfile parse(JsonObject json) throws IOException {
        try {
            String fullId = json.get("id").getAsString();
            if (!fullId.startsWith("wildforge:")) throw new IllegalArgumentException("Unnamespaced biome ID");
            UndergroundBiome type = UndergroundBiome.valueOf(fullId.substring(10)
                    .toUpperCase(java.util.Locale.ROOT));
            JsonArray depth = json.getAsJsonArray("depth_fraction");
            JsonArray radius = json.getAsJsonArray("typical_chamber_radius_blocks");
            return new UndergroundBiomeProfile(type, json.get("name").getAsString(),
                    new BiomeProfile.Range(depth.get(0).getAsDouble(), depth.get(1).getAsDouble()),
                    json.get("rarity").getAsString(),
                    UndergroundBiomeProfile.Rule.valueOf(json.get("selection_rule").getAsString()),
                    json.get("suggested_weight_relative").getAsInt(),
                    radius.get(0).getAsInt(), radius.get(1).getAsInt(),
                    json.get("suggested_moisture_preference").getAsDouble(),
                    json.get("suggested_water_proximity_preference").getAsDouble(),
                    strings(json.getAsJsonArray("host_rocks")),
                    strings(json.getAsJsonArray("reuse_existing_textures")),
                    strings(json.getAsJsonArray("proposed_new_textures")),
                    transitions(json.getAsJsonArray("transitions")),
                    json.get("placement").getAsString(),
                    json.get("geometry").getAsString(),
                    json.get("description").getAsString());
        } catch (RuntimeException failure) {
            throw new IOException("Invalid underground biome fields: " + json, failure);
        }
    }

    private static List<String> strings(JsonArray list) {
        if (list == null) throw new IllegalArgumentException("Missing list");
        List<String> result = new ArrayList<>();
        for (JsonElement entry : list) result.add(entry.getAsString());
        return List.copyOf(result);
    }

    private static List<UndergroundBiome> transitions(JsonArray list) {
        List<UndergroundBiome> result = new ArrayList<>();
        for (String id : strings(list)) result.add(UndergroundBiome.valueOf(id.toUpperCase(java.util.Locale.ROOT)));
        return List.copyOf(result);
    }
}
