package com.wildforge.datagen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative catalog for built-in block/item JSON.
 *
 * To add another full-cube block, use one blockWithItem(...) call. The generator
 * creates its block, matching inventory item, self-drop, and content-index entries.
 * Keep historical numeric IDs unchanged; item ID 7 is reserved for old saves.
 *
 * Run: gradlew.bat generateContent
 */
public final class ContentDataGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String NAMESPACE = "wildforge";

    private record FileSpec(int numericId, String name, JsonObject json) { }
    private record TagSpec(String name, JsonObject json) { }

    private final List<FileSpec> blocks = new ArrayList<>();
    private final List<FileSpec> items = new ArrayList<>();
    private final List<TagSpec> blockTags = new ArrayList<>();
    private final List<TagSpec> itemTags = new ArrayList<>();
    private final Set<Integer> blockIds = new HashSet<>();
    private final Set<Integer> itemIds = new HashSet<>();
    private final Set<String> names = new HashSet<>();

    private ContentDataGenerator() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ContentDataGenerator <generated-resources-dir>");
        }
        ContentDataGenerator generator = new ContentDataGenerator();
        generator.registerBuiltInContent();
        generator.write(Path.of(args[0]));
        generator.writeWorldgenConfig(Path.of(args[0]));
        generator.writeGeologyConfig(Path.of(args[0]));
        generator.writeOreConfig(Path.of(args[0]));
        generator.writeCaveDecorations(Path.of(args[0]));
        generator.writeCaveWalls(Path.of(args[0]));
        generator.writeMarineSediments(Path.of(args[0]));
        generator.writeTreeSpecies(Path.of(args[0]));
        generator.writePlacedFeatures(Path.of(args[0]));
        generator.writeAquifers(Path.of(args[0]));
        generator.writeLightEmission(Path.of(args[0]));
        generator.writeTextureManifest(Path.of(args[0]), Path.of("src/main/resources/textures"));
        BiomeDataGenerator.write(Path.of(args[0]), Path.of("src/main/resources/textures"));
        UndergroundBiomeDataGenerator.write(
                Path.of(args[0]),
                Path.of("src/datagen/resources/wildforge/underground_biomes.source.json"),
                Path.of("src/datagen/resources/wildforge/underground_assets.source.json"),
                Path.of("src/main/resources/textures"));
    }

    private void registerBuiltInContent() {
        // World block numeric IDs are stable and saved directly as bytes.
        blockWithoutItem("air", 0, false, false, 0);
        blockWithItem("grass",     1,  1, 1).textures("grass_top", "grass_side", "dirt");
        blockWithItem("dirt",      2,  2, 1);
        blockWithItem("stone",     3,  3, 2);
        blockWithItem("andesite",  4,  5, 2);
        blockWithItem("basalt",    5,  6, 3);
        blockWithoutItem("bedrock",6, true, false, 999);
        blockWithItem("clay",      7,  8, 1);
        blockWithItem("coal_ore",  8,  9, 2);
        blockWithItem("iron_ore",  9, 10, 2);
        blockWithItem("gold_ore", 10, 11, 2);
        blockWithItem("silver_ore",11,12, 2);
        blockWithItem("diamond_ore",12,13,3);
        blockWithItem("diorite",  13, 14, 2);
        blockWithItem("gabbro",   14, 15, 2);
        blockWithItem("gravel",   15, 16, 1);
        blockWithItem("sand",     16, 17, 1);
        blockWithItem("sandstone",17, 18, 2);
        blockWithItem("cobblestone", 18, 19, 2);

        // Biome-foundation additions: match immutable runtime/save IDs.
        blockWithItem("snow_grass", 19, 20, 2);
        blockWithItem("snow", 20, 21, 2);
        blockWithItem("tundra_grass", 21, 22, 2);
        blockWithItem("permafrost", 22, 23, 2);
        blockWithItem("peat", 23, 24, 2);
        blockWithItem("rich_humus", 24, 25, 2);
        blockWithItem("leaf_litter", 25, 26, 2);
        blockWithItem("pine_needle_litter", 26, 27, 2);
        blockWithItem("moss_block", 27, 28, 2);
        blockWithItem("limestone", 28, 29, 2);
        blockWithItem("shale", 29, 30, 2);
        blockWithItem("slate", 30, 31, 2);
        blockWithItem("granite", 31, 32, 2);
        blockWithItem("chalk", 32, 33, 2);
        blockWithItem("mud", 33, 34, 2);
        blockWithItem("wet_mud", 34, 35, 2);
        blockWithItem("silt", 35, 36, 2);
        blockWithItem("red_sand", 36, 37, 2);
        blockWithItem("red_sandstone", 37, 38, 2);
        blockWithItem("volcanic_ash", 38, 39, 2);
        blockWithItem("scoria", 39, 40, 2);
        blockWithItem("pumice", 40, 41, 2);
        blockWithItem("obsidian", 41, 42, 2);
        blockWithItem("salt_crust", 42, 43, 2);
        blockWithItem("shell_sand", 43, 44, 2);
        blockWithItem("riverbed_sediment", 44, 45, 2);
        blockWithItem("waterworn_stone", 45, 46, 2);
        blockWithItem("ice", 46, 47, 2);
        blockWithItem("packed_snow", 47, 48, 2);
        blockWithItem("sulfur_rock", 48, 49, 2);
        blockWithItem("quartz_vein", 49, 50, 2);
        // Additional full-cube texture assets with enum-backed placement.
        blockWithItem("alpine_moss", 50, 51, 2);
        blockWithItem("autumn_leaf_litter", 51, 52, 2);
        blockWithItem("basalt_column", 52, 53, 2);
        blockWithItem("beach_pebbles", 53, 54, 2);
        blockWithItem("calcite", 54, 55, 2);
        blockWithItem("cave_clay", 55, 56, 2);
        blockWithItem("cave_moss", 56, 57, 2);
        blockWithItem("coal_dust_deposit", 57, 58, 2);
        blockWithItem("compacted_sediment", 58, 59, 2);
        blockWithItem("cracked_basalt", 59, 60, 2);
        blockWithItem("cracked_earth", 60, 61, 2);
        blockWithItem("exposed_root_stone", 61, 62, 2);
        blockWithItem("fault_breccia", 62, 63, 2);
        blockWithItem("fertile_loam", 63, 64, 2);
        blockWithItem("flowstone", 64, 65, 2);
        blockWithItem("fossil_shale", 65, 66, 2);
        blockWithItem("fractured_slate", 66, 67, 2);
        blockWithItem("frosted_rock", 67, 68, 2);
        blockWithItem("frozen_peat", 68, 69, 2);
        blockWithItem("glacial_ice", 69, 70, 2);
        blockWithItem("gypsum", 70, 71, 2);
        blockWithItem("iron_stained_stone", 71, 72, 2);
        blockWithItem("lichen_stone", 72, 73, 2);
        blockWithItem("limestone_rubble", 73, 74, 2);
        blockWithItem("magma_crust", 74, 75, 2);
        blockWithItem("mineral_matrix", 75, 76, 2);
        blockWithItem("mossy_rock", 76, 77, 2);
        blockWithItem("mycelial_mat", 77, 78, 2);
        blockWithItem("red_clay", 78, 79, 2);
        blockWithItem("rock_salt", 79, 80, 2);
        blockWithItem("root_mat", 80, 81, 2);
        blockWithItem("rope_lava_stone", 81, 82, 2);
        blockWithItem("sandstone_rubble", 82, 83, 2);
        blockWithItem("scree", 83, 84, 2);
        blockWithItem("siltstone", 84, 85, 2);
        blockWithItem("snow_crust", 85, 86, 2);
        blockWithItem("submerged_scree", 86, 87, 2);
        blockWithItem("weathered_limestone", 87, 88, 2);
        blockWithItem("wet_rock", 88, 89, 2);
        blockWithItem("wet_sand", 89, 90, 2);
        blockWithItem("wet_slate", 90, 91, 2);
        blockWithItem("brick_block", 91, 92, 2);
        blockWithItem("glass", 92, 93, 2);
        blockWithItem("oak_log", 93, 94, 2).textures("oak_log_top", "oak_log_side", "oak_log_top");
        blockWithItem("oak_planks", 94, 95, 2);
        blockWithItem("pine_log", 95, 96, 2).textures("pine_log_top", "pine_log_side", "pine_log_top");
        blockWithItem("pine_planks", 96, 97, 2);
        blockWithItem("maple_log", 97, 98, 2).textures("maple_log_top", "maple_log_side", "maple_log_top");
        blockWithItem("maple_planks", 98, 99, 2);
        blockWithItem("willow_log", 99, 100, 2).textures("willow_log_top", "willow_log_side", "willow_log_top");
        blockWithItem("willow_planks", 100, 101, 2);
        blockWithItem("cypress_log", 101, 102, 2).textures("cypress_log_top", "cypress_log_side", "cypress_log_top");
        blockWithItem("cypress_planks", 102, 103, 2);
        blockWithItem("rotten_wood", 103, 104, 2).textures("rotten_wood_top", "rotten_wood_side", "rotten_wood_top");
        blockWithItem("crafting_table", 104, 105, 2).textures("crafting_table_top", "crafting_table_side", "crafting_table_side");
        blockWithItem("furnace", 105, 106, 2).textures("furnace_top", "furnace_side", "furnace_side");
        blockWithItem("cactus", 106, 107, 2).textures("cactus_top", "cactus_side", "cactus_side");
        // Physics-managed fluids are not ordinary inventory/placeable blocks.
        blockWithoutItem("water", 107, false, false, 0).textures("water", "water", "water");
        blockWithoutItem("lava", 108, false, false, 0).textures("lava", "lava", "lava");
        blockWithoutItem("flowing_water", 109, false, false, 0).textures("water", "water", "water");
        blockWithoutItem("flowing_lava", 110, false, false, 0).textures("lava", "lava", "lava");
        // Log orientation variants keep the original block item and drops.
        orientedLog("oak_log_x", 111, "oak_log_block", "oak_log_top", "oak_log_side");
        orientedLog("oak_log_z", 112, "oak_log_block", "oak_log_top", "oak_log_side");
        orientedLog("pine_log_x", 113, "pine_log_block", "pine_log_top", "pine_log_side");
        orientedLog("pine_log_z", 114, "pine_log_block", "pine_log_top", "pine_log_side");
        orientedLog("maple_log_x", 115, "maple_log_block", "maple_log_top", "maple_log_side");
        orientedLog("maple_log_z", 116, "maple_log_block", "maple_log_top", "maple_log_side");
        orientedLog("willow_log_x", 117, "willow_log_block", "willow_log_top", "willow_log_side");
        orientedLog("willow_log_z", 118, "willow_log_block", "willow_log_top", "willow_log_side");
        orientedLog("cypress_log_x", 119, "cypress_log_block", "cypress_log_top", "cypress_log_side");
        orientedLog("cypress_log_z", 120, "cypress_log_block", "cypress_log_top", "cypress_log_side");
        // Ocean-floor materials and new tree woodsets (append-only legacy IDs).
        blockWithItem("ocean_silty_sand", 121, 149, 2);
        blockWithItem("ocean_shell_grit", 122, 150, 2);
        blockWithItem("ocean_cold_gravel", 123, 151, 2);
        blockWithItem("ocean_algae_stone", 124, 152, 2);
        blockWithItem("ocean_dark_silt", 125, 153, 2);
        blockWithItem("ocean_basalt_pebbles", 126, 154, 2);
        blockWithItem("ocean_coral_sand", 127, 155, 2);
        blockWithItem("ocean_reef_limestone", 128, 156, 2);
        blockWithItem("ocean_dead_coral_rubble", 129, 157, 2);
        blockWithItem("ocean_abyssal_silt", 130, 158, 2);
        blockWithItem("birch_log", 131, 159, 2).textures("birch_log_top", "birch_log_side", "birch_log_top");
        blockWithItem("birch_planks", 132, 160, 2);
        blockWithItem("spruce_log", 133, 161, 2).textures("spruce_log_top", "spruce_log_side", "spruce_log_top");
        blockWithItem("spruce_planks", 134, 162, 2);
        blockWithItem("acacia_log", 135, 163, 2).textures("acacia_log_top", "acacia_log_side", "acacia_log_top");
        blockWithItem("acacia_planks", 136, 164, 2);
        blockWithItem("alder_log", 137, 165, 2).textures("alder_log_top", "alder_log_side", "alder_log_top");
        blockWithItem("alder_planks", 138, 166, 2);
        blockWithItem("mangrove_log", 139, 167, 2).textures("mangrove_log_top", "mangrove_log_side", "mangrove_log_top");
        blockWithItem("mangrove_planks", 140, 168, 2);
        blockWithItem("palm_log", 141, 169, 2).textures("palm_log_top", "palm_log_side", "palm_log_top");
        blockWithItem("palm_planks", 142, 170, 2);
        orientedLog("birch_log_x", 143, "birch_log_block", "birch_log_top", "birch_log_side");
        orientedLog("birch_log_z", 144, "birch_log_block", "birch_log_top", "birch_log_side");
        orientedLog("spruce_log_x", 145, "spruce_log_block", "spruce_log_top", "spruce_log_side");
        orientedLog("spruce_log_z", 146, "spruce_log_block", "spruce_log_top", "spruce_log_side");
        orientedLog("acacia_log_x", 147, "acacia_log_block", "acacia_log_top", "acacia_log_side");
        orientedLog("acacia_log_z", 148, "acacia_log_block", "acacia_log_top", "acacia_log_side");
        orientedLog("alder_log_x", 149, "alder_log_block", "alder_log_top", "alder_log_side");
        orientedLog("alder_log_z", 150, "alder_log_block", "alder_log_top", "alder_log_side");
        orientedLog("mangrove_log_x", 151, "mangrove_log_block", "mangrove_log_top", "mangrove_log_side");
        orientedLog("mangrove_log_z", 152, "mangrove_log_block", "mangrove_log_top", "mangrove_log_side");
        orientedLog("palm_log_x", 153, "palm_log_block", "palm_log_top", "palm_log_side");
        orientedLog("palm_log_z", 154, "palm_log_block", "palm_log_top", "palm_log_side");
        // Patch 3B: cutout leaf and decoration blocks; IDs append-only.
        collidableLeavesWithItem("oak_leaves", 155, 171, 0.3f).textures("oak_leaves", "oak_leaves", "oak_leaves");
        collidableLeavesWithItem("pine_leaves", 156, 172, 0.3f).textures("pine_leaves", "pine_leaves", "pine_leaves");
        collidableLeavesWithItem("maple_leaves", 157, 173, 0.3f).textures("maple_leaves", "maple_leaves", "maple_leaves");
        collidableLeavesWithItem("willow_leaves", 158, 174, 0.3f).textures("willow_leaves", "willow_leaves", "willow_leaves");
        collidableLeavesWithItem("cypress_leaves", 159, 175, 0.3f).textures("cypress_leaves", "cypress_leaves", "cypress_leaves");
        collidableLeavesWithItem("birch_leaves", 160, 176, 0.3f).textures("birch_leaves", "birch_leaves", "birch_leaves");
        collidableLeavesWithItem("spruce_leaves", 161, 177, 0.3f).textures("spruce_leaves", "spruce_leaves", "spruce_leaves");
        collidableLeavesWithItem("acacia_leaves", 162, 178, 0.3f).textures("acacia_leaves", "acacia_leaves", "acacia_leaves");
        collidableLeavesWithItem("alder_leaves", 163, 179, 0.3f).textures("alder_leaves", "alder_leaves", "alder_leaves");
        collidableLeavesWithItem("mangrove_leaves", 164, 180, 0.3f).textures("mangrove_leaves", "mangrove_leaves", "mangrove_leaves");
        collidableLeavesWithItem("palm_leaves", 165, 181, 0.3f).textures("palm_leaves", "palm_leaves", "palm_leaves");
        nonSolidBlockWithItem("grass_tuft", 166, 182, 0.3f).textures("small_grass", "small_grass", "small_grass");
        nonSolidBlockWithItem("fern_plant", 167, 183, 0.3f).textures("fern", "fern", "fern");
        nonSolidBlockWithItem("reed_plant", 168, 184, 0.3f).textures("dune_grass", "dune_grass", "dune_grass");
        nonSolidBlockWithItem("dead_bush_plant", 169, 185, 0.3f).textures("dead_bush", "dead_bush", "dead_bush");
        nonSolidBlockWithItem("shrub_plant", 170, 186, 0.3f).textures("evergreen_shrub", "evergreen_shrub", "evergreen_shrub");
        nonSolidBlockWithItem("mushroom_plant", 171, 187, 0.3f).textures("forest_mushroom", "forest_mushroom", "forest_mushroom");
        nonSolidBlockWithItem("poppy_flower", 172, 188, 0.3f).textures("flower_poppy", "flower_poppy", "flower_poppy");
        nonSolidBlockWithItem("daisy_flower", 173, 189, 0.3f).textures("flower_daisy", "flower_daisy", "flower_daisy");
        nonSolidBlockWithItem("lavender_flower", 174, 190, 0.3f).textures("flower_lavender", "flower_lavender", "flower_lavender");
        nonSolidBlockWithItem("stalactite", 175, 191, 0.3f).textures("stalactite", "stalactite", "stalactite");
        nonSolidBlockWithItem("stalagmite", 176, 192, 0.3f).textures("stalagmite", "stalagmite", "stalagmite");
        nonSolidBlockWithItem("hanging_roots", 177, 193, 0.3f).textures("hanging_roots", "hanging_roots", "hanging_roots");
        nonSolidBlockWithItem("sporecap_fungus", 178, 194, 0.3f).textures("sporecap_fungus", "sporecap_fungus", "sporecap_fungus");
        nonSolidBlockWithItem("crystal_cluster", 179, 195, 0.8f).textures("quartz_vein", "quartz_vein", "quartz_vein");
        // Append-only snow-height states, no new inventory IDs: each drops a Snow item.
        for(int layers=1;layers<=7;layers++) {
            String name="snow_layer_"+layers;
            int id=179+layers;
            BlockBuilder block=blockWithoutItem(name,id,true,true,0.1f);
            block.textures("snow","snow","snow");
            JsonArray drops=new JsonArray();
            JsonObject drop=new JsonObject();
            drop.addProperty("item",id("snow_block"));
            drop.addProperty("count",1);
            drops.add(drop);
            block.json.add("drops",drops);
        }
        nonSolidBlockWithItem("clover_plant", 187, 196, 0.3f).textures("clover","clover","clover");
        nonSolidBlockWithItem("tall_grass_plant", 188, 197, 0.3f).textures("tall_grass","tall_grass","tall_grass");
        nonSolidBlockWithItem("cattail_plant", 189, 198, 0.3f).textures("cattails","cattails","cattails");
        nonSolidBlockWithItem("cornflower", 190, 199, 0.3f).textures("flower_cornflower","flower_cornflower","flower_cornflower");
        nonSolidBlockWithItem("orchid_flower", 191, 200, 0.3f).textures("flower_orchid","flower_orchid","flower_orchid");
        nonSolidBlockWithItem("frost_shrub", 192, 201, 0.3f).textures("frost_shrub","frost_shrub","frost_shrub");
        nonSolidBlockWithItem("dry_scrub", 193, 202, 0.3f).textures("dry_scrub","dry_scrub","dry_scrub");
        nonSolidBlockWithItem("lichen_patch", 194, 203, 0.3f).textures("lichen_patch","lichen_patch","lichen_patch");
        item("brick", 108, 64, "brick");
        item("diamond", 109, 64, "diamond");
        item("gold_ingot", 110, 64, "gold_ingot");
        item("iron_ingot", 111, 64, "iron_ingot");
        item("silver_ingot", 112, 64, "silver_ingot");
        item("raw_gold", 113, 64, "raw_gold");
        item("raw_iron", 114, 64, "raw_iron");
        item("raw_silver", 115, 64, "raw_silver");
        item("pine_cone", 116, 64, "pine_cone");
        item("sea_shell", 117, 64, "sea_shell");
        item("stick", 118, 64, "stick");
        item("wooden_axe", 119, 1, "wooden_axe");
        item("wooden_hoe", 120, 1, "wooden_hoe");
        item("wooden_pickaxe", 121, 1, "wooden_pickaxe");
        item("wooden_shovel", 122, 1, "wooden_shovel");
        item("wooden_sword", 123, 1, "wooden_sword");
        item("stone_axe", 124, 1, "stone_axe");
        item("stone_hoe", 125, 1, "stone_hoe");
        item("stone_pickaxe", 126, 1, "stone_pickaxe");
        item("stone_shovel", 127, 1, "stone_shovel");
        item("stone_sword", 128, 1, "stone_sword");
        item("iron_axe", 129, 1, "iron_axe");
        item("iron_hoe", 130, 1, "iron_hoe");
        item("iron_pickaxe", 131, 1, "iron_pickaxe");
        item("iron_shovel", 132, 1, "iron_shovel");
        item("iron_sword", 133, 1, "iron_sword");
        item("gold_axe", 134, 1, "gold_axe");
        item("gold_hoe", 135, 1, "gold_hoe");
        item("gold_pickaxe", 136, 1, "gold_pickaxe");
        item("gold_shovel", 137, 1, "gold_shovel");
        item("gold_sword", 138, 1, "gold_sword");
        item("silver_axe", 139, 1, "silver_axe");
        item("silver_hoe", 140, 1, "silver_hoe");
        item("silver_pickaxe", 141, 1, "silver_pickaxe");
        item("silver_shovel", 142, 1, "silver_shovel");
        item("silver_sword", 143, 1, "silver_sword");
        item("diamond_axe", 144, 1, "diamond_axe");
        item("diamond_hoe", 145, 1, "diamond_hoe");
        item("diamond_pickaxe", 146, 1, "diamond_pickaxe");
        item("diamond_shovel", 147, 1, "diamond_shovel");
        item("diamond_sword", 148, 1, "diamond_sword");

        // Items without a placeable block use item(...).
        item("coal", 4, 64, "coal");

        // Tags are generated, indexed and resolved at runtime. # references nest tags.
        blockTag("stone", "stone", "andesite", "basalt", "diorite", "gabbro", "cobblestone");
        blockTag("foliage", "oak_leaves", "pine_leaves", "maple_leaves", "willow_leaves", "cypress_leaves", "birch_leaves", "spruce_leaves", "acacia_leaves", "alder_leaves", "mangrove_leaves", "palm_leaves", "fern_plant", "grass_tuft", "shrub_plant");
        itemTag("stone_blocks", "stone_block", "andesite_block", "basalt_block",
                "diorite_block", "gabbro_block", "cobblestone_block");
        itemTag("foliage", "oak_leaves_block", "pine_leaves_block", "maple_leaves_block", "willow_leaves_block", "cypress_leaves_block", "birch_leaves_block", "spruce_leaves_block", "acacia_leaves_block", "alder_leaves_block", "mangrove_leaves_block", "palm_leaves_block", "grass_tuft_block", "fern_plant_block", "reed_plant_block", "shrub_plant_block");
        itemTag("building_blocks", "#stone_blocks", "dirt_block", "sandstone_block");

        // Functional grouped tags for crafting, harvest rules and future worldgen.
        blockTag("natural_stone", "stone", "andesite", "basalt", "diorite", "gabbro", "limestone", "shale", "slate", "granite", "chalk", "sandstone", "siltstone", "calcite", "wet_rock", "wet_slate", "frosted_rock");
        blockTag("ores", "coal_ore", "iron_ore", "gold_ore", "silver_ore", "diamond_ore");
        blockTag("soil", "grass", "dirt", "tundra_grass", "snow_grass", "rich_humus", "peat", "fertile_loam", "mud", "wet_mud", "silt", "red_clay", "cracked_earth", "root_mat");
        blockTag("sediment", "sand", "red_sand", "gravel", "riverbed_sediment", "shell_sand", "beach_pebbles", "wet_sand", "submerged_scree", "silt", "cave_clay", "clay", "ocean_silty_sand", "ocean_shell_grit", "ocean_cold_gravel", "ocean_algae_stone", "ocean_dark_silt", "ocean_basalt_pebbles", "ocean_coral_sand", "ocean_reef_limestone", "ocean_dead_coral_rubble", "ocean_abyssal_silt");
        blockTag("loose_rock", "gravel", "scree", "sandstone_rubble", "limestone_rubble", "beach_pebbles", "pumice");
        blockTag("snow_ice", "snow", "snow_grass", "packed_snow", "snow_crust", "ice", "glacial_ice", "permafrost", "frozen_peat", "snow_layer_1", "snow_layer_2", "snow_layer_3", "snow_layer_4", "snow_layer_5", "snow_layer_6", "snow_layer_7");
        blockTag("geological", "fossil_shale", "fault_breccia", "iron_stained_stone", "cracked_basalt", "basalt_column", "magma_crust", "obsidian", "scoria", "volcanic_ash", "sulfur_rock", "quartz_vein", "gypsum", "rock_salt", "salt_crust", "mineral_matrix", "flowstone", "coal_dust_deposit");
        blockTag("mossy", "moss_block", "mossy_rock", "cave_moss", "alpine_moss", "lichen_stone", "mycelial_mat", "exposed_root_stone");
        blockTag("logs", "oak_log", "pine_log", "maple_log", "willow_log", "cypress_log", "rotten_wood", "oak_log_x", "oak_log_z", "pine_log_x", "pine_log_z", "maple_log_x", "maple_log_z", "willow_log_x", "willow_log_z", "cypress_log_x", "cypress_log_z", "birch_log", "spruce_log", "acacia_log", "alder_log", "mangrove_log", "palm_log", "birch_log_x", "birch_log_z", "spruce_log_x", "spruce_log_z", "acacia_log_x", "acacia_log_z", "alder_log_x", "alder_log_z", "mangrove_log_x", "mangrove_log_z", "palm_log_x", "palm_log_z");
        blockTag("planks", "oak_planks", "pine_planks", "maple_planks", "willow_planks", "cypress_planks", "birch_planks", "spruce_planks", "acacia_planks", "alder_planks", "mangrove_planks", "palm_planks");
        blockTag("crafted_blocks", "brick_block", "glass", "crafting_table", "furnace");
        blockTag("fluids", "water", "flowing_water", "lava", "flowing_lava");
        blockTag("water", "water", "flowing_water");
        blockTag("lava", "lava", "flowing_lava");
        itemTag("placeable_blocks", "grass_block", "dirt_block", "stone_block", "andesite_block", "basalt_block", "clay_block", "coal_ore_block", "iron_ore_block", "gold_ore_block", "silver_ore_block", "diamond_ore_block", "diorite_block", "gabbro_block", "gravel_block", "sand_block", "sandstone_block", "cobblestone_block", "snow_grass_block", "snow_block", "tundra_grass_block", "permafrost_block", "peat_block", "rich_humus_block", "leaf_litter_block", "pine_needle_litter_block", "moss_block_block", "limestone_block", "shale_block", "slate_block", "granite_block", "chalk_block", "mud_block", "wet_mud_block", "silt_block", "red_sand_block", "red_sandstone_block", "volcanic_ash_block", "scoria_block", "pumice_block", "obsidian_block", "salt_crust_block", "shell_sand_block", "riverbed_sediment_block", "waterworn_stone_block", "ice_block", "packed_snow_block", "sulfur_rock_block", "quartz_vein_block", "alpine_moss_block", "autumn_leaf_litter_block", "basalt_column_block", "beach_pebbles_block", "calcite_block", "cave_clay_block", "cave_moss_block", "coal_dust_deposit_block", "compacted_sediment_block", "cracked_basalt_block", "cracked_earth_block", "exposed_root_stone_block", "fault_breccia_block", "fertile_loam_block", "flowstone_block", "fossil_shale_block", "fractured_slate_block", "frosted_rock_block", "frozen_peat_block", "glacial_ice_block", "gypsum_block", "iron_stained_stone_block", "lichen_stone_block", "limestone_rubble_block", "magma_crust_block", "mineral_matrix_block", "mossy_rock_block", "mycelial_mat_block", "red_clay_block", "rock_salt_block", "root_mat_block", "rope_lava_stone_block", "sandstone_rubble_block", "scree_block", "siltstone_block", "snow_crust_block", "submerged_scree_block", "weathered_limestone_block", "wet_rock_block", "wet_sand_block", "wet_slate_block", "brick_block_block", "glass_block", "oak_log_block", "oak_planks_block", "pine_log_block", "pine_planks_block", "maple_log_block", "maple_planks_block", "willow_log_block", "willow_planks_block", "cypress_log_block", "cypress_planks_block", "rotten_wood_block", "crafting_table_block", "furnace_block", "cactus_block", "ocean_silty_sand_block", "ocean_shell_grit_block", "ocean_cold_gravel_block", "ocean_algae_stone_block", "ocean_dark_silt_block", "ocean_basalt_pebbles_block", "ocean_coral_sand_block", "ocean_reef_limestone_block", "ocean_dead_coral_rubble_block", "ocean_abyssal_silt_block", "birch_log_block", "birch_planks_block", "spruce_log_block", "spruce_planks_block", "acacia_log_block", "acacia_planks_block", "alder_log_block", "alder_planks_block", "mangrove_log_block", "mangrove_planks_block", "palm_log_block", "palm_planks_block");
        itemTag("terrain_blocks", "andesite_block", "basalt_block", "beach_pebbles_block", "calcite_block", "cave_clay_block", "chalk_block", "clay_block", "cracked_earth_block", "diorite_block", "dirt_block", "fertile_loam_block", "frosted_rock_block", "frozen_peat_block", "gabbro_block", "glacial_ice_block", "granite_block", "grass_block", "gravel_block", "ice_block", "limestone_block", "limestone_rubble_block", "mud_block", "packed_snow_block", "peat_block", "permafrost_block", "pumice_block", "red_clay_block", "red_sand_block", "rich_humus_block", "riverbed_sediment_block", "root_mat_block", "sand_block", "sandstone_block", "sandstone_rubble_block", "scree_block", "shale_block", "shell_sand_block", "silt_block", "siltstone_block", "slate_block", "snow_block", "snow_crust_block", "snow_grass_block", "stone_block", "submerged_scree_block", "tundra_grass_block", "wet_mud_block", "wet_rock_block", "wet_sand_block", "wet_slate_block", "ocean_silty_sand_block", "ocean_shell_grit_block", "ocean_cold_gravel_block", "ocean_algae_stone_block", "ocean_dark_silt_block", "ocean_basalt_pebbles_block", "ocean_coral_sand_block", "ocean_reef_limestone_block", "ocean_dead_coral_rubble_block", "ocean_abyssal_silt_block");
        itemTag("ore_blocks", "coal_ore_block", "iron_ore_block", "gold_ore_block", "silver_ore_block", "diamond_ore_block");
        itemTag("wood_blocks", "oak_log_block", "pine_log_block", "maple_log_block", "willow_log_block", "cypress_log_block", "rotten_wood_block", "oak_planks_block", "pine_planks_block", "maple_planks_block", "willow_planks_block", "cypress_planks_block", "birch_log_block", "birch_planks_block", "spruce_log_block", "spruce_planks_block", "acacia_log_block", "acacia_planks_block", "alder_log_block", "alder_planks_block", "mangrove_log_block", "mangrove_planks_block", "palm_log_block", "palm_planks_block");
        itemTag("decoration_blocks", "moss_block_block", "mossy_rock_block", "cave_moss_block", "alpine_moss_block", "lichen_stone_block", "mycelial_mat_block", "exposed_root_stone_block", "leaf_litter_block", "autumn_leaf_litter_block", "pine_needle_litter_block", "cactus_block");
        itemTag("mineral_items", "coal", "diamond", "raw_gold", "raw_iron", "raw_silver", "gold_ingot", "iron_ingot", "silver_ingot");
        itemTag("crafting_materials", "stick", "brick", "pine_cone", "sea_shell");
        itemTag("tools", "diamond_axe", "diamond_hoe", "diamond_pickaxe", "diamond_shovel", "diamond_sword", "gold_axe", "gold_hoe", "gold_pickaxe", "gold_shovel", "gold_sword", "iron_axe", "iron_hoe", "iron_pickaxe", "iron_shovel", "iron_sword", "silver_axe", "silver_hoe", "silver_pickaxe", "silver_shovel", "silver_sword", "stone_axe", "stone_hoe", "stone_pickaxe", "stone_shovel", "stone_sword", "wooden_axe", "wooden_hoe", "wooden_pickaxe", "wooden_shovel", "wooden_sword");
    }

    /** One call creates a block, its placeable item, and the default block drop. */
    private BlockBuilder blockWithItem(String name, int blockId, int itemId, float hardness) {
        if (itemId == 7) {
            throw new IllegalArgumentException("Item ID 7 is reserved for save compatibility");
        }
        String blockItem = name + "_block";
        BlockBuilder block = new BlockBuilder(name, blockId, true, true, hardness);
        block.json.addProperty("block_item", id(blockItem));
        JsonArray drops = new JsonArray();
        JsonObject drop = new JsonObject();
        drop.addProperty("item", id(blockItem));
        drop.addProperty("count", 1);
        drops.add(drop);
        block.json.add("drops", drops);
        addItem(blockItem, itemId, 64, name, null);
        return block;
    }

    /** Harvestable, non-colliding flora.  The block ID is persisted like any other edit. */
    private BlockBuilder nonSolidBlockWithItem(String name, int blockId, int itemId, float hardness) {
        String blockItem = name + "_block";
        BlockBuilder block = new BlockBuilder(name, blockId, false, true, hardness);
        String model = name.equals("crystal_cluster") ? "block/crystal" :
                name.endsWith("_leaves") ? "block/leaves" :
                name.equals("stalactite") || name.equals("stalagmite") ? "block/spike" :
                name.equals("hanging_roots") ? "block/ceiling_plant" : "block/cross_plant";
        block.json.addProperty("model", id(model));
        block.json.addProperty("block_item", id(blockItem));
        JsonArray drops = new JsonArray();
        JsonObject drop = new JsonObject();
        drop.addProperty("item", id(blockItem));
        drop.addProperty("count", 1);
        drops.add(drop);
        block.json.add("drops", drops);
        addItem(blockItem, itemId, 64, name, null);
        return block;
    }

    /** Foliage occupies a full voxel for collision but uses cutout texture rendering. */
    private BlockBuilder collidableLeavesWithItem(String name,int blockId,int itemId,float hardness) {
        BlockBuilder block=nonSolidBlockWithItem(name,blockId,itemId,hardness);
        block.json.addProperty("solid",true);
        return block;
    }

    private void orientedLog(String name,int id,String drop,String top,String bark) {
        BlockBuilder block=blockWithoutItem(name,id,true,true,2.0f);
        block.textures(top,bark,top);
        JsonArray drops=new JsonArray();
        JsonObject item=new JsonObject();item.addProperty("item",id(drop));
        item.addProperty("count",1);drops.add(item);
        block.json.add("drops",drops);
    }

    private BlockBuilder blockWithoutItem(String name, int blockId,
                                         boolean solid, boolean breakable, float hardness) {
        return new BlockBuilder(name, blockId, solid, breakable, hardness);
    }

    /** Simple inventory-only resource, e.g. coal. */
    private void item(String name, int itemId, int maxStack, String texture) {
        addItem(name, itemId, maxStack, null, texture);
    }

    /** Define a tag using resource names or #other_tag to include another tag. */
    private void blockTag(String name, String... values) {
        tag(blockTags, name, values);
    }

    /** Item tags are separate from block tags, even when their names match. */
    private void itemTag(String name, String... values) {
        tag(itemTags, name, values);
    }

    private void tag(List<TagSpec> target, String name, String... values) {
        checkPath(name);
        if (target.stream().anyMatch(t -> t.name().equals(name))) {
            throw new IllegalArgumentException("Duplicate tag: " + name);
        }
        JsonArray entries = new JsonArray();
        for (String value : values) {
            boolean nested = value.startsWith("#");
            String path = nested ? value.substring(1) : value;
            checkPath(path);
            entries.add((nested ? "#" : "") + id(path));
        }
        JsonObject json = new JsonObject();
        json.addProperty("replace", false);
        json.add("values", entries);
        target.add(new TagSpec(name, json));
    }

    private void addItem(String name, int itemId, int maxStack,
                         String placedBlock, String texture) {
        checkPath(name);
        if (itemId < 1 || itemId > 65535 || itemId == 7 || !itemIds.add(itemId)) {
            throw new IllegalArgumentException("Duplicate or reserved item numeric ID: " + itemId);
        }
        if (!names.add("item/" + name)) {
            throw new IllegalArgumentException("Duplicate item name: " + name);
        }
        JsonObject json = new JsonObject();
        json.addProperty("id", id(name));
        json.addProperty("numeric_id", itemId);
        json.addProperty("max_stack_size", maxStack);
        if (placedBlock != null) {
            json.addProperty("places_block", id(placedBlock));
            json.addProperty("model", id("item/block"));
        } else {
            json.addProperty("texture", id(texture));
            json.addProperty("model", id("item/generated"));
        }
        items.add(new FileSpec(itemId, name, json));
    }

    private final class BlockBuilder {
        private final JsonObject json;

        private BlockBuilder(String name, int blockId,
                             boolean solid, boolean breakable, float hardness) {
            checkPath(name);
            if (blockId < 0 || blockId > 255 || !blockIds.add(blockId)) {
                throw new IllegalArgumentException("Duplicate or invalid block numeric ID: " + blockId);
            }
            if (!names.add("block/" + name)) {
                throw new IllegalArgumentException("Duplicate block name: " + name);
            }
            json = new JsonObject();
            json.addProperty("id", id(name));
            json.addProperty("numeric_id", blockId);
            json.addProperty("solid", solid);
            json.addProperty("breakable", breakable);
            json.addProperty("hardness", hardness);
            if (blockId != 0) {
                json.addProperty("model", id("block/cube"));
                textures(name, name, name);
            }
            json.add("drops", new JsonArray());
            blocks.add(new FileSpec(blockId, name, json));
        }

        /** Optional distinct top/side/bottom textures (grass uses this). */
        private BlockBuilder textures(String top, String side, String bottom) {
            JsonObject textures = new JsonObject();
            textures.addProperty("top", id(top));
            textures.addProperty("side", id(side));
            textures.addProperty("bottom", id(bottom));
            json.add("textures", textures);
            return this;
        }
    }

    private void write(Path destination) throws IOException {
        // This directory contains ONLY datagen output; stale files are removed.
        Path root = destination.toAbsolutePath().normalize().resolve("data/wildforge");
        Path blocksDir = root.resolve("blocks");
        Path itemsDir = root.resolve("items");
        Path tagsDir = root.resolve("tags/blocks");
        Path itemTagsDir = root.resolve("tags/items");
        cleanJson(blocksDir);
        cleanJson(itemsDir);
        cleanJson(tagsDir);
        cleanJson(itemTagsDir);
        Files.createDirectories(blocksDir);
        Files.createDirectories(itemsDir);
        Files.createDirectories(tagsDir);
        Files.createDirectories(itemTagsDir);

        JsonArray blockTagIndex = writeTags(blockTags, tagsDir, "blocks");
        JsonArray itemTagIndex = writeTags(itemTags, itemTagsDir, "items");
        JsonArray blockIndex = writeSpecs(blocks, blocksDir, "blocks");
        JsonArray itemIndex = writeSpecs(items, itemsDir, "items");
        JsonObject index = new JsonObject();
        index.add("blocks", blockIndex);
        index.add("items", itemIndex);
        index.add("block_tags", blockTagIndex);
        index.add("item_tags", itemTagIndex);
        Files.writeString(root.resolve("content_index.json"), GSON.toJson(index) + "\n",
                StandardCharsets.UTF_8);
        System.out.printf("Generated %d blocks, %d items, %d block tags and %d item tags into %s%n",
                blocks.size(), items.size(), blockTags.size(), itemTags.size(), root);
    }

    private static JsonArray writeTags(List<TagSpec> tags, Path directory, String kind)
            throws IOException {
        JsonArray index = new JsonArray();
        tags.sort(Comparator.comparing(TagSpec::name));
        for (TagSpec tag : tags) {
            String filename = tag.name() + ".json";
            Files.writeString(directory.resolve(filename), GSON.toJson(tag.json()) + "\n",
                    StandardCharsets.UTF_8);
            index.add("data/" + NAMESPACE + "/tags/" + kind + "/" + filename);
        }
        return index;
    }

    private static JsonArray writeSpecs(List<FileSpec> specs, Path directory, String kind)
            throws IOException {
        JsonArray index = new JsonArray();
        specs.sort(Comparator.comparingInt(FileSpec::numericId));
        for (FileSpec spec : specs) {
            String filename = spec.name() + ".json";
            Files.writeString(directory.resolve(filename), GSON.toJson(spec.json()) + "\n",
                    StandardCharsets.UTF_8);
            index.add("data/" + NAMESPACE + "/" + kind + "/" + filename);
        }
        return index;
    }


    /** Data-generated default worldgen tuning. These parameters belong in data, not TerrainMesh. */
    private void writeWorldgenConfig(Path destination) throws IOException {
        Path directory = destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(directory);
        JsonObject json = new JsonObject();
        json.addProperty("min_y", 0);
        json.addProperty("max_y", 511);
        json.addProperty("sea_level", 224);
        json.addProperty("continent_scale", 1400.0);
        json.addProperty("hills_scale", 260.0);
        json.addProperty("mountains_scale", 310.0);
        json.addProperty("detail_scale", 48.0);
        json.addProperty("climate_scale", 780.0);
        json.addProperty("river_scale", 500.0);
        json.addProperty("cave_scale", 43.0);
        json.addProperty("ocean_threshold", -0.20);
        json.addProperty("mountain_threshold", 0.12);
        json.addProperty("cave_threshold", 0.075);
        json.addProperty("tree_density", 0.85);
        json.addProperty("plant_density", 0.55);
        Files.writeString(directory.resolve("overworld.json"),
                GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    }

    /** Shared feature-placement modifiers; biome-specific species/weights are generated separately. */
    private void writePlacedFeatures(Path destination) throws IOException {
        Path dir=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(dir);
        JsonObject json=new JsonObject();
        json.addProperty("schema","wildforge-placed-features-v1");
        json.addProperty("tree_patch_scale",96.0);
        json.addProperty("tree_patch_amplitude",0.45);
        json.addProperty("forest_boost",1.24);
        json.addProperty("plant_patch_scale",75.0);
        json.addProperty("wet_plant_multiplier",1.65);
        json.addProperty("dry_plant_multiplier",1.15);
        json.addProperty("bank_plant_chance",0.62);
        json.addProperty("frostspire_treeline_above_sea",94);
        Files.writeString(dir.resolve("feature_placement.json"),GSON.toJson(json)+"\n",StandardCharsets.UTF_8);
    }

    private void writeAquifers(Path destination) throws IOException {
        Path dir=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(dir);
        JsonObject j=new JsonObject();
        j.addProperty("schema","wildforge-aquifers-v1");
        j.addProperty("cell_size",72);
        j.addProperty("water_table_below_sea",33);
        j.addProperty("water_table_variation",22.0);
        j.addProperty("basin_threshold",0.13);
        j.addProperty("lava_threshold",0.36);
        j.addProperty("lava_max_y",29);
        Files.writeString(dir.resolve("aquifers.json"),GSON.toJson(j)+"\n",StandardCharsets.UTF_8);
    }

    /** All nonzero emissive blocks, including natural cavern lights. */
    private void writeLightEmission(Path destination) throws IOException {
        Path dir=destination.resolve("data/wildforge/lighting");
        Files.createDirectories(dir);
        JsonObject root=new JsonObject();
        root.addProperty("schema","wildforge-light-emission-v1");
        JsonObject values=new JsonObject();
        values.addProperty("lava",15);
        values.addProperty("flowing_lava",15);
        values.addProperty("magma_crust",6);
        values.addProperty("sporecap_fungus",10);
        values.addProperty("crystal_cluster",8);
        root.add("blocks",values);
        Files.writeString(dir.resolve("emission.json"),GSON.toJson(root)+"\n",StandardCharsets.UTF_8);
    }

    /** Minecraft-inspired stone/deep-rock and randomized unbreakable floor rules. */
    private void writeGeologyConfig(Path destination) throws IOException {
        Path dir=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(dir);
        JsonObject json=new JsonObject();
        json.addProperty("bedrock_layers",5);
        json.addProperty("deep_rock_relative_sea",-64);
        json.addProperty("deep_rock_transition",8);
        json.addProperty("accent_percent",5);
        Files.writeString(dir.resolve("strata.json"),GSON.toJson(json)+"\n",StandardCharsets.UTF_8);
    }

    /** Ore placement is authored here; biome feature passes consume the JSON.
     * Heights are RELATIVE to the world sea level, so a taller world scales cleanly.
     * A profile is attempted once per 16x16x24 deposit band.
     */
    private void writeOreConfig(Path destination) throws IOException {
        Path dir=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(dir);
        JsonObject root=new JsonObject();
        root.addProperty("schema","wildforge-ore-features-v2");
        JsonArray ores=new JsonArray();
        ore(ores,"coal_ore",-62,210,38,"high",.63,4,14,true);
        ore(ores,"iron_ore",-208,-7,-48,"triangle",.46,3,10,false);
        ore(ores,"iron_ore",38,260,125,"high",.38,2,8,false);
        ore(ores,"silver_ore",-186,-12,-95,"triangle",.22,2,7,false);
        ore(ores,"gold_ore",-210,-31,-80,"triangle",.15,2,6,false);
        ore(ores,"diamond_ore",-218,-40,-204,"deep",.085,1,5,true);
        root.add("ores",ores);
        Files.writeString(dir.resolve("ores.json"),GSON.toJson(root)+"\n",StandardCharsets.UTF_8);
    }
    private static void ore(JsonArray list,String block,int min,int max,int peak,String shape,
                            double chance,int minimum,int maximum,boolean discardExposed) {
        JsonObject json=new JsonObject();
        json.addProperty("block",block);json.addProperty("min_relative_sea",min);
        json.addProperty("max_relative_sea",max);json.addProperty("peak_relative_sea",peak);
        json.addProperty("shape",shape);json.addProperty("chance",chance);
        json.addProperty("min_size",minimum);json.addProperty("max_size",maximum);
        json.addProperty("discard_exposed",discardExposed);
        list.add(json);
    }

    /** Every underground biome has a data-authored floor and ceiling feature.
     * Blocks are validated against the built-in registered IDs before writing.
     */
    /** Every ocean classification has a weighted local bedrock/sediment palette. */
    private void writeMarineSediments(Path destination) throws IOException {
        Path directory=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(directory);
        JsonObject root=new JsonObject();
        root.addProperty("schema","wildforge-marine-sediments-v1");
        JsonObject oceans=new JsonObject();
        marinePalette(oceans,"temperate","ocean_silty_sand:40","ocean_shell_grit:25","ocean_cold_gravel:20","ocean_algae_stone:15");
        marinePalette(oceans,"deep","ocean_dark_silt:55","ocean_basalt_pebbles:20","ocean_cold_gravel:15","ocean_algae_stone:10");
        marinePalette(oceans,"warm","ocean_coral_sand:45","ocean_shell_grit:25","ocean_reef_limestone:20","ocean_dead_coral_rubble:10");
        marinePalette(oceans,"cold","ocean_cold_gravel:45","ocean_algae_stone:25","ocean_silty_sand:20","ocean_basalt_pebbles:10");
        marinePalette(oceans,"frozen","ocean_cold_gravel:45","ocean_basalt_pebbles:30","ocean_dark_silt:15","ocean_algae_stone:10");
        marinePalette(oceans,"reef","ocean_reef_limestone:40","ocean_coral_sand:30","ocean_dead_coral_rubble:20","ocean_shell_grit:10");
        marinePalette(oceans,"kelp","ocean_algae_stone:40","ocean_cold_gravel:25","ocean_silty_sand:20","ocean_dark_silt:15");
        marinePalette(oceans,"abyssal","ocean_abyssal_silt:65","ocean_basalt_pebbles:25","ocean_dark_silt:10");
        marinePalette(oceans,"estuary","ocean_silty_sand:45","ocean_dark_silt:25","ocean_shell_grit:20","ocean_algae_stone:10");
        marinePalette(oceans,"volcanic","ocean_basalt_pebbles:55","ocean_abyssal_silt:20","ocean_dead_coral_rubble:15","ocean_algae_stone:10");
        root.add("oceans",oceans);
        Files.writeString(directory.resolve("marine_sediments.json"),GSON.toJson(root)+"\n",StandardCharsets.UTF_8);
    }
    private static void marinePalette(JsonObject oceans,String ocean,String... entries){
        JsonArray materials=new JsonArray();
        for(String entry:entries){
            String[] pair=entry.split(":",2);
            JsonObject j=new JsonObject();j.addProperty("block",pair[0]);j.addProperty("weight",Integer.parseInt(pair[1]));
            materials.add(j);
        }
        oceans.add(ocean,materials);
    }

    private void writeCaveWalls(Path destination) throws IOException {
        Path directory=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(directory);
        JsonObject root=new JsonObject();root.addProperty("schema","wildforge-cave-walls-v1");
        JsonObject biomes=new JsonObject();
        caveWalls(biomes,"rootlace_hollows","moss_block rich_humus","exposed_root_stone");
        caveWalls(biomes,"seepstone_grottos","moss_block wet_rock","wet_rock");
        caveWalls(biomes,"underflow_channels","silt riverbed_sediment","waterworn_stone");
        caveWalls(biomes,"hollowstone_vaults","limestone_rubble","calcite flowstone");
        caveWalls(biomes,"coalshade_galleries","compacted_sediment","fossil_shale");
        caveWalls(biomes,"saltveil_chambers","salt_crust","gypsum");
        caveWalls(biomes,"frostvault_crevasses","packed_snow","glacial_ice");
        caveWalls(biomes,"ironfault_chasms","fault_breccia","fractured_slate");
        caveWalls(biomes,"crystal_seam_chambers","mineral_matrix","mineral_matrix");
        caveWalls(biomes,"sporelight_hollows","mycelial_mat","mossy_rock wet_rock");
        caveWalls(biomes,"cindervein_tubes","rope_lava_stone","cracked_basalt");
        caveWalls(biomes,"stillwater_abyss","submerged_scree","wet_slate");
        root.add("biomes",biomes);
        Files.writeString(directory.resolve("cave_walls.json"),GSON.toJson(root)+"\n",StandardCharsets.UTF_8);
    }
    private static void caveWalls(JsonObject biomes,String biome,String floors,String walls){
        JsonObject json=new JsonObject();JsonArray floor=new JsonArray(),wall=new JsonArray();
        for(String item:floors.split(" "))floor.add(item);
        for(String item:walls.split(" "))wall.add(item);
        json.add("floor",floor);json.add("wall",wall);biomes.add(biome,json);
    }

    /** Tree heights are tuned as species traits; individual biomes select species in biome JSON. */
    private void writeTreeSpecies(Path destination) throws IOException {
        Path directory=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(directory);
        JsonObject root=new JsonObject();root.addProperty("schema","wildforge-tree-species-v1");
        JsonObject trees=new JsonObject();
        treeSpecies(trees,"oak",9,16);
        treeSpecies(trees,"maple",9,16);
        treeSpecies(trees,"pine",12,22);
        treeSpecies(trees,"willow",8,15);
        treeSpecies(trees,"cypress",13,23);
        treeSpecies(trees,"birch",9,17);
        treeSpecies(trees,"spruce",12,25);
        treeSpecies(trees,"acacia",7,13);
        treeSpecies(trees,"alder",8,16);
        treeSpecies(trees,"mangrove",6,12);
        treeSpecies(trees,"palm",9,19);
        root.add("trees",trees);
        Files.writeString(directory.resolve("trees.json"),GSON.toJson(root)+"\n",StandardCharsets.UTF_8);
    }
    private static void treeSpecies(JsonObject all,String name,int min,int max){
        JsonObject entry=new JsonObject();entry.addProperty("min_height",min);entry.addProperty("max_height",max);
        all.add(name,entry);
    }

    private void writeCaveDecorations(Path destination) throws IOException {
        Path directory=destination.resolve("data/wildforge/worldgen");
        Files.createDirectories(directory);
        JsonObject root=new JsonObject();
        root.addProperty("schema","wildforge-cave-decorations-v1");
        JsonObject entries=new JsonObject();
        caveDecor(entries,"rootlace_hollows","mushroom_plant","hanging_roots");
        caveDecor(entries,"seepstone_grottos","cave_moss","stalactite");
        caveDecor(entries,"underflow_channels","riverbed_sediment","lichen_patch");
        caveDecor(entries,"hollowstone_vaults","stalagmite","stalactite");
        caveDecor(entries,"coalshade_galleries","coal_dust_deposit","fossil_shale");
        caveDecor(entries,"saltveil_chambers","gypsum","stalactite");
        caveDecor(entries,"frostvault_crevasses","packed_snow","glacial_ice");
        caveDecor(entries,"ironfault_chasms","fault_breccia","stalactite");
        caveDecor(entries,"crystal_seam_chambers","crystal_cluster","quartz_vein");
        caveDecor(entries,"sporelight_hollows","sporecap_fungus","hanging_roots");
        caveDecor(entries,"cindervein_tubes","magma_crust","stalactite");
        caveDecor(entries,"stillwater_abyss","submerged_scree","wet_slate");
        root.add("biomes",entries);
        Files.writeString(directory.resolve("cave_decorations.json"),GSON.toJson(root)+"\n",StandardCharsets.UTF_8);
    }
    private static void caveDecor(JsonObject entries,String biome,String floor,String ceiling){
        JsonObject rule=new JsonObject();rule.addProperty("floor",floor);rule.addProperty("ceiling",ceiling);
        entries.add(biome,rule);
    }

    /**
     * Discover every source texture automatically and emit its metadata. The manifest
     * is a *catalog*, not a gameplay registration: cross-plants, liquids and tools
     * require render/model support before their blocks/items can be enabled.
     */
    private void writeTextureManifest(Path destination, Path textureDirectory) throws IOException {
        if (!Files.isDirectory(textureDirectory)) {
            throw new IOException("Missing source texture directory: " + textureDirectory);
        }
        JsonArray entries = new JsonArray();
        Map<String, String> undergroundHints = UndergroundBiomeDataGenerator.textureModelHints(
                Path.of("src/datagen/resources/wildforge/underground_assets.source.json"));
        List<Path> textures;
        try (Stream<Path> files = Files.list(textureDirectory)) {
            textures = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted().toList();
        }
        for (Path path : textures) {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null || image.getWidth() != 32
                    || (image.getHeight() != 32 && image.getHeight() != 64)) {
                throw new IOException("Texture has unsupported dimensions: " + path);
            }
            String name = path.getFileName().toString().replaceFirst("\\.png$", "");
            JsonObject texture = new JsonObject();
            texture.addProperty("id", id(name));
            texture.addProperty("source", "textures/" + path.getFileName());
            texture.addProperty("width", image.getWidth());
            texture.addProperty("height", image.getHeight());
            texture.addProperty("model_hint", undergroundHints.getOrDefault(name, textureModelHint(name)));
            entries.add(texture);
        }
        Path directory = destination.resolve("data/wildforge");
        Files.createDirectories(directory);
        JsonObject manifest = new JsonObject();
        manifest.add("textures", entries);
        Files.writeString(directory.resolve("texture_index.json"),
                GSON.toJson(manifest) + "\n", StandardCharsets.UTF_8);
        System.out.printf("Indexed %d source textures for future content models%n", entries.size());
    }

    private static String textureModelHint(String name) {
        // Auto-categorize only the graphics. A model hint does not register
        // a block/item until the gameplay renderer supports that model.
        if (name.startsWith("tall_")
                || java.util.Set.of("cattails", "marsh_reeds", "seaweed").contains(name)) {
            return "double_height_cross";
        }
        if (name.startsWith("flower_") || java.util.Set.of(
                "small_grass", "clover", "fern", "dead_bush", "desert_succulent",
                "dry_scrub", "dune_grass", "evergreen_shrub", "forest_mushroom",
                "snow_grass",
                "frost_shrub", "meadow_thistle", "tundra_lichen", "wild_wheat"
                ).contains(name)) return "cross";
        if (name.endsWith("_leaves")) return "cutout_cube";
        if (name.equals("water") || name.equals("lava")) return "fluid";
        if (name.equals("glass") || name.equals("ice")) return "translucent_cube";
        if (name.equals("lily_pad")) return "water_surface";
        if (name.equals("shelf_fungus")) return "wall_attachment";
        if (name.equals("lichen_patch")) return "ground_overlay";
        if (name.equals("stalactite") || name.equals("stalagmite")) return "directional_formation";
        if (name.endsWith("_ingot") || name.startsWith("raw_")
                || java.util.Set.of("coal", "diamond", "stick", "pine_cone",
                        "sea_shell", "brick").contains(name)
                || name.endsWith("_axe") || name.endsWith("_hoe")
                || name.endsWith("_shovel") || name.endsWith("_sword")
                || name.endsWith("_pickaxe")) return "flat_item";
        return "cube_face";
    }

    private static void cleanJson(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path file : paths.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                Files.delete(file);
            }
        }
    }

    private static void checkPath(String path) {
        if (!path.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid identifier: " + path);
        }
    }

    private static String id(String name) {
        return NAMESPACE + ":" + name;
    }
}
