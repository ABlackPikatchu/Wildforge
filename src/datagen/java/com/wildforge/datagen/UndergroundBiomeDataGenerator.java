package com.wildforge.datagen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the author's underground-biome design source into stable, indexed
 * runtime JSON without duplicating the separately generated surface biomes.
 * No new block/item IDs are assigned here: texture/model support comes later.
 */
public final class UndergroundBiomeDataGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATA_ROOT = "data/wildforge/worldgen/underground_biomes/";
    private static final Set<String> RULES = Set.of(
            "FOREST_ROOTS", "WET_ROCK", "AQUIFER_CHANNEL", "LIMESTONE",
            "COAL_STRATA", "EVAPORITE", "GLACIAL", "FAULT", "MINERAL_VEIN",
            "SPORE_CHAMBER", "VOLCANIC", "DEEP_AQUIFER");

    private static final Map<String, String> SELECTION_RULES = Map.ofEntries(
            Map.entry("rootlace_hollows", "FOREST_ROOTS"),
            Map.entry("seepstone_grottos", "WET_ROCK"),
            Map.entry("underflow_channels", "AQUIFER_CHANNEL"),
            Map.entry("hollowstone_vaults", "LIMESTONE"),
            Map.entry("coalshade_galleries", "COAL_STRATA"),
            Map.entry("saltveil_chambers", "EVAPORITE"),
            Map.entry("frostvault_crevasses", "GLACIAL"),
            Map.entry("ironfault_chasms", "FAULT"),
            Map.entry("crystal_seam_chambers", "MINERAL_VEIN"),
            Map.entry("sporelight_hollows", "SPORE_CHAMBER"),
            Map.entry("cindervein_tubes", "VOLCANIC"),
            Map.entry("stillwater_abyss", "DEEP_AQUIFER"));

    private UndergroundBiomeDataGenerator() { }

    public static Map<String, String> textureModelHints(Path manifestPath) throws IOException {
        JsonObject source = loadObject(manifestPath);
        Map<String, String> hints = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String name = entry.getKey();
            JsonObject metadata = entry.getValue().getAsJsonObject();
            String kind = metadata.get("intended_rendering").getAsString();
            String hint = switch (kind) {
                case "block", "unique_block_top" -> "cube_face";
                case "transparent_cutout_vertical_2_blocks" -> "ceiling_cutout_tall";
                case "transparent_cutout_hanging" -> "ceiling_cutout";
                case "transparent_cutout_wall_decal" -> "wall_cutout";
                case "transparent_cutout_floor_decor" -> "floor_cutout";
                case "transparent_cutout_underwater_plant" -> "underwater_cutout";
                default -> throw new IOException("Unknown underground model: " + kind + " for " + name);
            };
            if (hints.put(name, hint) != null) {
                throw new IOException("Duplicate underground texture " + name);
            }
        }
        if (hints.size() != 33) throw new IOException("Expected 33 underground textures, got " + hints.size());
        return Map.copyOf(hints);
    }

    public static void write(Path outputResources, Path sourcePath, Path assetManifest,
                             Path textureDirectory) throws IOException {
        JsonObject source = loadObject(sourcePath);
        JsonArray sourceBiomes = source.getAsJsonArray("underground_biomes");
        if (sourceBiomes == null || sourceBiomes.size() != 12) {
            throw new IOException("Expected 12 underground source biomes");
        }
        Map<String, String> newAssetHints = textureModelHints(assetManifest);
        Set<String> seenIds = new HashSet<>();
        Set<String> usedNewAssets = new HashSet<>();
        List<JsonObject> output = new ArrayList<>();
        List<String> filenames = new ArrayList<>();

        for (JsonElement raw : sourceBiomes) {
            JsonObject json = raw.getAsJsonObject().deepCopy();
            String name = json.get("id").getAsString();
            if (!name.matches("[a-z][a-z0-9_]*") || !seenIds.add(name)) {
                throw new IOException("Invalid/duplicate underground biome: " + name);
            }
            if (!SELECTION_RULES.containsKey(name)) {
                throw new IOException("Underground biome has no selection rule: " + name);
            }
            String rule = SELECTION_RULES.get(name);
            if (!RULES.contains(rule)) throw new IOException("Unrecognized selection rule " + rule);
            requireNormalizedPair(json.getAsJsonArray("depth_fraction"), name + ".depth_fraction");
            requirePositivePair(json.getAsJsonArray("typical_chamber_radius_blocks"),
                    name + ".typical_chamber_radius_blocks");
            int weight = json.get("suggested_weight_relative").getAsInt();
            if (weight < 1 || weight > 1000) throw new IOException("Bad weight in " + name);
            requireNormalizedNumber(json.get("suggested_moisture_preference").getAsDouble(), name + ".moisture");
            requireNormalizedNumber(json.get("suggested_water_proximity_preference").getAsDouble(), name + ".water");

            for (String existing : strings(json.getAsJsonArray("reuse_existing_textures"))) {
                requireTexture(textureDirectory, existing);
                if (newAssetHints.containsKey(existing)) {
                    throw new IOException("Texture marked both existing and new: " + existing);
                }
            }
            for (String fresh : strings(json.getAsJsonArray("proposed_new_textures"))) {
                requireTexture(textureDirectory, fresh);
                if (!newAssetHints.containsKey(fresh) || !usedNewAssets.add(fresh)) {
                    throw new IOException("Missing, duplicated or unrecognized new texture " + fresh);
                }
            }
            for (String transition : strings(json.getAsJsonArray("transitions"))) {
                if (!SELECTION_RULES.containsKey(transition)) {
                    throw new IOException("Unknown transition from " + name + " to " + transition);
                }
            }

            // Preserve author's placement descriptions and worldbuilding notes.
            // Typed selection_rule provides a stable runtime implementation contract.
            json.addProperty("selection_rule", rule);
            json.addProperty("id", "wildforge:" + name);
            output.add(json);
            filenames.add(name);
        }
        if (!seenIds.equals(SELECTION_RULES.keySet()) || !usedNewAssets.equals(newAssetHints.keySet())) {
            throw new IOException("Underground source, selection rules, or 33 texture assets disagree");
        }
        Path target = outputResources.resolve(DATA_ROOT);
        Files.createDirectories(target);
        try (var files = Files.list(target)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.delete(file);
            }
        }
        JsonArray index = new JsonArray();
        for (int i = 0; i < output.size(); i++) {
            String path = filenames.get(i) + ".json";
            Files.writeString(target.resolve(path), GSON.toJson(output.get(i)) + "\n", StandardCharsets.UTF_8);
            index.add(DATA_ROOT + path);
        }
        JsonObject generatedIndex = new JsonObject();
        generatedIndex.addProperty("schema", "wildforge-underground-profiles-v1");
        generatedIndex.add("biomes", index);
        Files.writeString(target.resolve("index.json"), GSON.toJson(generatedIndex) + "\n", StandardCharsets.UTF_8);
        JsonObject settings = new JsonObject();
        settings.addProperty("region_scale", 180.0);
        settings.addProperty("groundwater_scale", 260.0);
        settings.addProperty("fault_scale", 440.0);
        settings.addProperty("mineral_scale", 240.0);
        settings.addProperty("volcanic_scale", 420.0);
        settings.addProperty("fault_threshold", 0.11);
        settings.addProperty("mineral_threshold", 0.16);
        settings.addProperty("groundwater_threshold", 0.63);
        settings.addProperty("cold_temperature_threshold", 0.32);
        Path settingsPath = outputResources.resolve("data/wildforge/worldgen/underground_settings.json");
        Files.createDirectories(settingsPath.getParent());
        Files.writeString(settingsPath, GSON.toJson(settings) + "\n", StandardCharsets.UTF_8);
        System.out.println("Generated 12 underground biome profiles; validated 33 new and all reused textures");
    }

    private static JsonObject loadObject(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Missing datagen source: " + path);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid source JSON: " + path, e);
        }
    }

    private static List<String> strings(JsonArray array) throws IOException {
        if (array == null) throw new IOException("Missing texture/transition list");
        List<String> values = new ArrayList<>();
        for (JsonElement value : array) values.add(value.getAsString());
        return values;
    }

    private static void requireTexture(Path directory, String name) throws IOException {
        if (!name.matches("[a-z0-9_]+")) throw new IOException("Invalid texture ID " + name);
        Path path = directory.resolve(name + ".png");
        if (!Files.isRegularFile(path)) throw new IOException("Missing underground texture " + path);
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null || img.getWidth() != 32 || (img.getHeight() != 32 && img.getHeight() != 64)) {
            throw new IOException("Invalid underground texture dimensions: " + path);
        }
    }

    private static void requireNormalizedPair(JsonArray pair, String label) throws IOException {
        if (pair == null || pair.size() != 2) throw new IOException("Expected two bounds in " + label);
        double min = pair.get(0).getAsDouble();
        double max = pair.get(1).getAsDouble();
        requireNormalizedNumber(min, label);
        requireNormalizedNumber(max, label);
        if (min > max) throw new IOException("Reversed bounds in " + label);
    }

    private static void requirePositivePair(JsonArray pair, String label) throws IOException {
        if (pair == null || pair.size() != 2) throw new IOException("Expected two radii in " + label);
        int min = pair.get(0).getAsInt();
        int max = pair.get(1).getAsInt();
        if (min <= 0 || max < min || max > 128) throw new IOException("Invalid radii in " + label);
    }

    private static void requireNormalizedNumber(double value, String label) throws IOException {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IOException("Not a normalized value in " + label + ": " + value);
        }
    }
}
