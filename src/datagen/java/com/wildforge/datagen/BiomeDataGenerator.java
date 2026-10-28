package com.wildforge.datagen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compact, authoritative 12-biome surface catalog. This produces JSON resources,
 * NOT world blocks. The chunk engine will consume these after migration.
 * Geology is deliberately not listed as the default stone for each biome.
 */
public final class BiomeDataGenerator {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final List<Spec> biomes = new ArrayList<>();

    private record Feature(String texture, int weight) { }
    private record Spec(String id, String name, String color, double[] temp, double[] rain,
                        int[] height, List<String> geology, boolean coast, boolean floodplain,
                        int priority, String top, String filler, double trees, double plants,
                        int maxTreeSlope, int maxPlantSlope,
                        List<Feature> treeSpecies, List<Feature> plantSpecies) { }

    private BiomeDataGenerator() { }

    public static void write(Path generatedResources, Path textureDir) throws IOException {
        BiomeDataGenerator generator = new BiomeDataGenerator();
        generator.register();
        generator.generate(generatedResources, textureDir);
    }

    private void add(String id, String name, String color, double[] temp, double[] rain,
                     int[] height, String geology, boolean coast, boolean floodplain,
                     int priority, String top, String filler, double trees, double plants,
                     int maxTreeSlope, int maxPlantSlope, String treeSpecies, String plantSpecies) {
        biomes.add(new Spec(id, name, color, temp, rain, height,
                geology.isBlank() ? List.of() : List.of(geology.split(",")), coast, floodplain,
                priority, top, filler, trees, plants, maxTreeSlope, maxPlantSlope,
                features(treeSpecies), features(plantSpecies)));
    }

    /** Feature syntax: "oak:7,maple:2" or "fern:5,small_grass:2". */
    private static List<Feature> features(String spec) {
        if (spec.isBlank()) return List.of();
        List<Feature> features = new ArrayList<>();
        for (String entry : spec.split(",")) {
            String[] parts = entry.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid feature spec " + entry);
            features.add(new Feature(parts[0], Integer.parseInt(parts[1])));
        }
        return List.copyOf(features);
    }

    private void register() {
        // Climate numbers are normalized 0-1, elevations are relative to sea level.
        // Geology restrictions apply only to special landscapes; no surface biome
        // owns every underground rock layer in its area.
        add("greenheart_forest", "Greenheart Forest", "478b48", r(.38,.78), r(.54,1), h(3,48), "", false,false,
                12, "forest_loam","fertile_loam", .82,.75,4,5,
                "oak:8,maple:2,willow:1", "fern:6,small_grass:7,clover:2,flower_bluebell:2,forest_mushroom:2,tall_grass:2");
        add("needlewood_taiga", "Needlewood Taiga", "4e7865", r(0,.46), r(.30,1), h(9,87), "", false,false,
                14, "pine_needle_litter","dirt", .85,.42,5,5,
                "pine:9,oak:1", "evergreen_shrub:5,fern:3,small_grass:2,flower_snowdrop:1");
        add("emberleaf_woodland", "Emberleaf Woodland", "b58a43", r(.32,.78), r(.28,.77), h(7,66), "",false,false,
                11, "autumn_leaf_litter","forest_loam", .68,.65,5,5,
                "maple:9,oak:1", "small_grass:5,flower_goldenrod:3,fern:3,meadow_thistle:1,flower_foxglove:1");
        add("sunmeadow_plains", "Sunmeadow Plains", "a9bc65", r(0,1), r(0,1), h(-160,190), "",false,false,
                1, "grass_top","dirt", .09,.74,3,4,
                "oak:2,maple:1", "small_grass:9,tall_grass:5,clover:3,flower_daisy:3,flower_cornflower:2,flower_lavender:2,wild_wheat:2");
        add("frostspire_highlands", "Frostspire Highlands", "a5aab2", r(0,.65), r(0,1), h(50,190), "",false,false,
                35, "scree","stone", .04,.13,4,4,
                "pine:1", "frost_shrub:3,snow_grass:3,flower_snowdrop:1");
        add("frostmoor_tundra", "Frostmoor Tundra", "c0c7b5", r(0,.30), r(0,.83), h(-2,82), "",false,false,
                22, "permafrost","frozen_peat", .0,.22,3,4,
                "", "frost_shrub:4,snow_grass:6,lichen_patch:3,flower_snowdrop:1");
        add("redscar_badlands", "Redscar Badlands", "b75f43", r(.53,1), r(0,.48), h(8,92), "SANDSTONE,SHALE",false,false,
                31, "red_sand","red_clay", .0,.18,3,4,
                "", "dry_scrub:5,dead_bush:5,desert_succulent:1");
        add("suncrack_desert", "Suncrack Desert", "dfc27d", r(.64,1), r(0,.43), h(-4,60), "",false,false,
                24, "sand","sandstone", .0,.16,3,4,
                "", "dune_grass:4,desert_succulent:3,dry_scrub:2,dead_bush:4");
        add("mistfen_wetlands", "Mistfen Wetlands", "668f76", r(.27,.9), r(.64,1), h(-5,22), "",false,true,
                30, "peat","wet_mud", .46,.90,2,2,
                "willow:6,cypress:5", "cattails:7,marsh_reeds:8,flower_orchid:2,small_grass:2");
        add("whitecliff_coast", "Whitecliff Coast", "e0dfc7", r(0,1), r(0,1), h(-10,23), "LIMESTONE",true,false,
                45, "chalk","limestone", .02,.24,4,5,
                "oak:1", "small_grass:5,flower_daisy:2,flower_lavender:1");
        add("cinderfall_volcanic_fields", "Cinderfall Volcanic Fields", "655a57", r(0,1), r(0,1), h(0,150), "VOLCANIC",false,false,
                50, "volcanic_ash","basalt", .0,.06,3,4,
                "", "dry_scrub:2,dead_bush:1");
        add("hollowstone_karst", "Hollowstone Karst", "bcbba4", r(.20,.87), r(.22,.98), h(8,98), "LIMESTONE",false,false,
                34, "weathered_limestone","limestone", .17,.37,3,4,
                "oak:3,maple:1", "fern:4,lichen_patch:3,forest_mushroom:2,small_grass:2");
    }

    private static double[] r(double a, double b) { return new double[]{a,b}; }
    private static int[] h(int a, int b) { return new int[]{a,b}; }

    private void generate(Path destination, Path textureDir) throws IOException {
        if (biomes.size() != 12) throw new IllegalStateException("Expected exactly twelve surface biomes");
        Path output = destination.resolve("data/wildforge/worldgen/biomes");
        Files.createDirectories(output);
        Set<String> names = new HashSet<>();
        JsonArray index = new JsonArray();
        // Keep the output stable and reject stale biome files after renames.
        try (var files = Files.list(output)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) Files.delete(file);
        }
        for (Spec biome : biomes) {
            if (!names.add(biome.id)) throw new IllegalArgumentException("Duplicate biome " + biome.id);
            requireTexture(textureDir, biome.top);
            requireTexture(textureDir, biome.filler);
            for (Feature tree : biome.treeSpecies) {
                for (String tex : List.of(tree.texture + "_log_side", tree.texture + "_log_top", tree.texture + "_leaves")) {
                    requireTexture(textureDir, tex);
                }
            }
            for (Feature plant : biome.plantSpecies) {
                requireTexture(textureDir, plant.texture);
                BufferedImage sprite = ImageIO.read(textureDir.resolve(plant.texture + ".png").toFile());
                if (!sprite.getColorModel().hasAlpha()) {
                    throw new IOException("Plant/decoration texture must have transparency: " + plant.texture);
                }
                boolean hasTransparentPixel = false;
                for (int py = 0; py < sprite.getHeight() && !hasTransparentPixel; py++) {
                    for (int px = 0; px < sprite.getWidth(); px++) {
                        if ((sprite.getRGB(px, py) >>> 24) < 255) {
                            hasTransparentPixel = true;
                            break;
                        }
                    }
                }
                if (!hasTransparentPixel) {
                    throw new IOException("Plant/decoration texture has no transparent pixels: " + plant.texture);
                }
            }
            JsonObject json = new JsonObject();
            json.addProperty("id", "wildforge:" + biome.id);
            json.addProperty("name", biome.name);
            json.addProperty("map_color", biome.color);
            JsonObject criteria = new JsonObject();
            criteria.add("temperature", pair(biome.temp));
            criteria.add("rainfall", pair(biome.rain));
            criteria.add("height_above_sea", pair(biome.height));
            JsonArray geology = new JsonArray();
            for (String rock : biome.geology) geology.add(rock);
            criteria.add("geology", geology);
            criteria.addProperty("coastal", biome.coast);
            criteria.addProperty("floodplain", biome.floodplain);
            criteria.addProperty("priority", biome.priority);
            json.add("criteria", criteria);
            JsonObject surface = new JsonObject();
            surface.addProperty("top", biome.top);
            surface.addProperty("filler", biome.filler);
            json.add("surface", surface);
            JsonObject decorations = new JsonObject();
            decorations.addProperty("tree_density", biome.trees);
            decorations.addProperty("plant_density", biome.plants);
            decorations.addProperty("max_tree_slope", biome.maxTreeSlope);
            decorations.addProperty("max_plant_slope", biome.maxPlantSlope);
            decorations.add("trees", jsonFeatures(biome.treeSpecies));
            decorations.add("plants", jsonFeatures(biome.plantSpecies));
            json.add("decorations", decorations);
            String path = "data/wildforge/worldgen/biomes/" + biome.id + ".json";
            Files.writeString(output.resolve(biome.id + ".json"), JSON.toJson(json) + "\n", StandardCharsets.UTF_8);
            index.add(path);
        }
        JsonObject idx = new JsonObject();
        idx.add("biomes", index);
        Files.writeString(output.resolve("index.json"), JSON.toJson(idx) + "\n", StandardCharsets.UTF_8);
        System.out.println("Generated 12 surface biome profiles with validated textures");
    }

    private static void requireTexture(Path textureDir, String name) throws IOException {
        Path path = textureDir.resolve(name + ".png");
        if (!Files.isRegularFile(path)) throw new IOException("Biome references missing texture " + path);
        // Texture manifest already validates sizes; check readability for direct datagen.
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Unreadable biome texture " + path);
    }

    private static JsonArray jsonFeatures(List<Feature> features) {
        JsonArray array = new JsonArray();
        for (Feature feature : features) {
            JsonObject item = new JsonObject();
            item.addProperty("id", feature.texture);
            item.addProperty("weight", feature.weight);
            array.add(item);
        }
        return array;
    }

    private static JsonArray pair(double[] vals) {
        JsonArray array = new JsonArray();
        for (double v : vals) array.add(v);
        return array;
    }
    private static JsonArray pair(int[] vals) {
        JsonArray array = new JsonArray();
        for (int v : vals) array.add(v);
        return array;
    }
}
