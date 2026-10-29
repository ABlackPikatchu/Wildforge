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
 * Authoritative 35-biome surface catalog. This produces JSON resources,
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
                "oak:6,birch:3,maple:2,willow:1", "fern:6,small_grass:7,clover:2,flower_bluebell:2,forest_mushroom:2,tall_grass:2");
        add("needlewood_taiga", "Needlewood Taiga", "4e7865", r(0,.46), r(.30,1), h(9,87), "", false,false,
                14, "pine_needle_litter","dirt", .85,.42,5,5,
                "pine:6,spruce:7,birch:1", "evergreen_shrub:5,fern:3,small_grass:2,flower_snowdrop:1");
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
                "birch:2,oak:1", "small_grass:5,flower_daisy:2,flower_lavender:1");
        add("cinderfall_volcanic_fields", "Cinderfall Volcanic Fields", "655a57", r(0,1), r(0,1), h(0,150), "VOLCANIC",false,false,
                50, "volcanic_ash","basalt", .0,.06,3,4,
                "", "dry_scrub:2,dead_bush:1");
        add("hollowstone_karst", "Hollowstone Karst", "bcbba4", r(.20,.87), r(.22,.98), h(8,98), "LIMESTONE",false,false,
                34, "weathered_limestone","limestone", .17,.37,3,4,
                "oak:2,birch:2,maple:1", "fern:4,lichen_patch:3,forest_mushroom:2,small_grass:2");
        // Forest: Rare old-growth oak groves with vaulted crowns, broad root buttresses, mossed logs and shaded floor.
        add("elderroot_grove", "Elderroot Grove", "386843", r(0.32,0.72), r(0.65,1), h(8,70), "", false,false,
                14, "forest_loam","rich_humus", 0.57,0.48,4,5,
                "oak:10,maple:1", "fern:7,forest_mushroom:5,small_grass:2,lichen_patch:2");
        // Forest: Dense, low hardwood thicket with tangled saplings, fern carpets and narrow sightlines.
        add("brackenwood_thicket", "Brackenwood Thicket", "477946", r(0.32,0.84), r(0.52,0.92), h(4,48), "", false,false,
                12, "grass_top","dirt", 0.7,0.9,3,4,
                "oak:5,birch:5,maple:3", "fern:12,small_grass:8,clover:2,flower_bluebell:1");
        // Forest: Very wet lowland forest with draping foliage, moss on wood and abundant shade-loving plants.
        add("mossveil_rainwood", "Mossveil Rainwood", "296b53", r(0.45,0.92), r(0.78,1), h(3,52), "", false,false,
                16, "moss_block","forest_loam", 0.85,0.95,3,4,
                "oak:3,alder:4,willow:4,birch:1", "fern:8,forest_mushroom:4,flower_orchid:2,dripping_lichen:2,small_grass:3");
        // Forest: Steep and shadowed, stream-carved fern gullies beneath mixed hardwood forest.
        add("fernshade_ravine", "Fernshade Ravine", "3f715e", r(0.22,0.76), r(0.58,1), h(5,85), "", false,false,
                14, "wet_rock","stone", 0.26,0.83,2,5,
                "oak:5,maple:3,willow:2", "fern:14,dripping_lichen:3,small_grass:3,forest_mushroom:2");
        // Forest: Sparse upland pine woodland on thin stony soils with pale lichen and open, windy ridges.
        add("silverpine_barrens", "Silverpine Barrens", "6a806c", r(0.06,0.56), r(0.21,0.61), h(20,115), "", false,false,
                14, "lichen_stone","gravel", 0.44,0.28,5,5,
                "pine:5,spruce:8,birch:1", "evergreen_shrub:5,lichen_patch:5,dry_scrub:2,fern:1");
        // Forest: Snow-laden pine forest below permanent alpine tundra, with snowdrifts and pockets of frozen soil.
        add("snowmantle_woods", "Snowmantle Woods", "9ab3b7", r(0.02,0.29), r(0.4,0.87), h(18,98), "", false,false,
                17, "snow_crust","permafrost", 0.69,0.25,4,4,
                "pine:5,spruce:8", "snow_grass:5,frost_shrub:3,flower_snowdrop:2,evergreen_shrub:1");
        // Grassland: Broad dry temperate grassland with sun-faded grasses, shallow soils and very few trees.
        add("goldgrass_steppe", "Goldgrass Steppe", "b7a46a", r(0.48,0.9), r(0.16,0.48), h(1,65), "", false,false,
                14, "grass_top","dirt", 0.13,0.69,3,4,
                "acacia:4", "dune_grass:7,wild_wheat:6,tall_grass:4,dry_scrub:2,small_grass:3");
        // Grassland: Windy upland heath of poor soils, heathy scrub, scattered flowers and eroded rock.
        add("briarfield_heath", "Briarfield Heath", "957b79", r(0.18,0.7), r(0.3,0.77), h(15,94), "", false,false,
                13, "peat","dirt", 0.05,0.57,5,4,
                "pine:1,oak:1", "dry_scrub:7,meadow_thistle:5,flower_lavender:5,tundra_grass:2");
        // Grassland: Warm semiarid mosaic of open hardpan, hardy shrubs and isolated gnarled oaks.
        add("thornscrub_savanna", "Thornscrub Savanna", "b29a63", r(0.65,1), r(0.18,0.54), h(0,62), "", false,false,
                14, "cracked_earth","dirt", 0.09,0.49,3,4,
                "acacia:7,oak:1", "dry_scrub:7,dune_grass:5,desert_succulent:3,small_grass:2");
        // Wetland: Flower-rich, moist riverside meadow with dew, low grasses, reeds and almost no canopy.
        add("mistbloom_meadow", "Mistbloom Meadow", "8ab897", r(0.28,0.78), r(0.62,0.97), h(-2,25), "", false,true,
                16, "fertile_loam","silt", 0.08,0.94,2,3,
                "alder:3,willow:1", "flower_daisy:5,flower_cornflower:5,flower_orchid:2,tall_grass:4,clover:5,small_grass:6");
        // Wetland: Dark acidic peat bog with low islands, still pools and sparse cypress growth.
        add("blackwater_bog", "Blackwater Bog", "355d51", r(0.16,0.8), r(0.73,1), h(-5,24), "", false,true,
                18, "peat","wet_mud", 0.28,0.57,2,3,
                "cypress:5,alder:5,willow:2", "marsh_reeds:5,forest_mushroom:3,cattails:2,lichen_patch:2");
        // Wetland: Floodplain hardwood forest with exposed roots, muddy channels, high willow crowns and damp hollows.
        add("floodroot_forest", "Floodroot Forest", "417d65", r(0.28,0.91), r(0.72,1), h(-3,27), "", false,true,
                18, "wet_mud","silt", 0.56,0.68,2,3,
                "alder:6,willow:4,mangrove:2,cypress:3,oak:1", "fern:5,cattails:4,marsh_reeds:5,forest_mushroom:1");
        // Wetland: Coastal and river-mouth delta of shifting sandbars, silt islands, reed beds and shallow channels.
        add("reedwater_delta", "Reedwater Delta", "698f86", r(0.4,1), r(0.55,1), h(-7,12), "", true,true,
                19, "silt","wet_mud", 0.39,0.92,2,2,
                "mangrove:8,alder:4,willow:2", "marsh_reeds:9,cattails:6,lily_pad:3,seaweed:2");
        // Coast: Storm-worn dark rocky coastline of slate bluffs, gravel coves and sea spray.
        add("stormbreak_coast", "Stormbreak Coast", "657d88", r(0.12,0.76), r(0.28,0.9), h(-8,29), "", true,false,
                21, "slate","gravel", 0.02,0.13,6,5,
                "", "dry_scrub:3,lichen_patch:3,small_grass:1");
        // Coast: Flat saline tidal margins with salt crusts, damp sands, brackish pools and little vegetation.
        add("saltwind_flats", "Saltwind Flats", "c9cab0", r(0.35,0.98), r(0.1,0.7), h(-6,15), "", true,false,
                18, "salt_crust","silt", 0.0,0.14,2,2,
                "", "dune_grass:4,dry_scrub:1,seaweed:2");
        // Coast: Warm sandy littoral beaches with dunes, shell patches and occasional coastal scrub.
        add("sunwash_strand", "Sunwash Strand", "dbc393", r(0.55,1), r(0.15,0.8), h(-7,24), "", true,false,
                17, "shell_sand","sand", 0.13,0.26,4,4,
                "palm:8", "dune_grass:6,seaweed:2,desert_succulent:2,dead_bush:1");
        // Highland: Rugged rolling foothills of weathered shale, scree fans and scattered conifers.
        add("shalecrest_foothills", "Shalecrest Foothills", "7d8180", r(0.16,0.69), r(0.24,0.77), h(30,115), "SHALE", false,false,
                21, "shale","stone", 0.25,0.34,5,4,
                "spruce:7,pine:5,birch:1", "lichen_patch:6,fern:2,small_grass:3");
        // Highland: Bald uplifted granite ridges, tors, sharp summits and rare sheltered pockets.
        add("granite_crown", "Granite Crown", "848b95", r(0.02,0.62), r(0.11,0.83), h(65,185), "GRANITE", false,false,
                26, "granite","stone", 0.0,0.1,6,5,
                "", "lichen_patch:5,snow_grass:2");
        // Highland: Perennial ice sheets and cracked blue glacial tongues between peaks.
        add("glimmerfrost_glacier", "Glimmerfrost Glacier", "b3d8e4", r(0.0,0.25), r(0.32,1), h(45,185), "", false,false,
                29, "glacial_ice","packed_snow", 0.0,0.02,6,5,
                "", "snow_grass:1");
        // Arid: Deep dry red sandstone canyons with layered cliffs, talus slopes and winding sandy washes.
        add("vermilion_canyon", "Vermilion Canyon", "b66e52", r(0.55,1), r(0.02,0.4), h(10,110), "SANDSTONE,SHALE", false,false,
                24, "red_sandstone","red_sandstone", 0.0,0.12,6,4,
                "", "dead_bush:4,dry_scrub:3,desert_succulent:1");
        // Arid: Blinding inland dry salt basin with polygonal crusts, gypsum fringes and shallow seasonal pools.
        add("sunscorch_saltpan", "Sunscorch Saltpan", "d6c9a5", r(0.7,1), r(0.0,0.28), h(-10,22), "", false,false,
                21, "salt_crust","gypsum", 0.0,0.04,2,2,
                "", "dead_bush:2,desert_succulent:1");
        // Volcanic: Glassy obsidian fields, cooled black lava and isolated hot fractures around old eruption cores.
        add("emberglass_wastes", "Emberglass Wastes", "3c414e", r(0.23,1), r(0.0,0.65), h(7,155), "VOLCANIC", false,false,
                32, "obsidian","basalt", 0.0,0.025,4,4,
                "", "dry_scrub:1");
        // Volcanic: Old volcanic soil colonized by pioneer grasses, sparse pine and patchy green regeneration.
        add("ashenwood_recovery", "Ashenwood Recovery", "667a58", r(0.29,0.79), r(0.39,0.83), h(8,92), "VOLCANIC", false,false,
                20, "volcanic_ash","basalt", 0.23,0.43,4,4,
                "pine:7,oak:1", "small_grass:6,lichen_patch:4,evergreen_shrub:4,fern:2");
    }

    private static double[] r(double a, double b) { return new double[]{a,b}; }
    private static int[] h(int a, int b) { return new int[]{a,b}; }

    private void generate(Path destination, Path textureDir) throws IOException {
        if (biomes.size() != 35) throw new IllegalStateException("Expected exactly 35 surface biomes");
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
        System.out.println("Generated 35 surface biome profiles with validated textures");
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
