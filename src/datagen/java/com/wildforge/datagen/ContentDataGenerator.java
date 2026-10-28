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

        // Items without a placeable block use item(...).
        item("coal", 4, 64, "coal");

        // Tags are generated, indexed and resolved at runtime. # references nest tags.
        blockTag("stone", "stone", "andesite", "basalt", "diorite", "gabbro", "cobblestone");
        blockTag("natural_stone", "#stone", "sandstone");
        itemTag("stone_blocks", "stone_block", "andesite_block", "basalt_block",
                "diorite_block", "gabbro_block", "cobblestone_block");
        itemTag("building_blocks", "#stone_blocks", "dirt_block", "sandstone_block");
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
        json.addProperty("min_y", -64);
        json.addProperty("max_y", 191);
        json.addProperty("sea_level", 64);
        json.addProperty("continent_scale", 1400.0);
        json.addProperty("hills_scale", 260.0);
        json.addProperty("mountains_scale", 310.0);
        json.addProperty("detail_scale", 48.0);
        json.addProperty("climate_scale", 780.0);
        json.addProperty("river_scale", 500.0);
        json.addProperty("cave_scale", 43.0);
        json.addProperty("ocean_threshold", -0.13);
        json.addProperty("mountain_threshold", 0.12);
        json.addProperty("cave_threshold", 0.075);
        json.addProperty("tree_density", 0.85);
        json.addProperty("plant_density", 0.55);
        Files.writeString(directory.resolve("overworld.json"),
                GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
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
