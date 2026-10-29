package com.wildforge.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads definitions from classpath resources. The explicit content index works
 * both in development and in a packaged JAR (where directory scanning is unreliable).
 * This is deliberately additive: gameplay and save serialization still use enums.
 */
public final class ContentLoader {
    public static final String INDEX_RESOURCE = "data/wildforge/content_index.json";

    public record Content(BlockRegistry blocks, ItemRegistry items, TagRegistry tags) {
        public Content {
            Objects.requireNonNull(blocks, "blocks");
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(tags, "tags");
        }
    }

    private ContentLoader() { }

    public static Content loadBuiltIn() throws IOException {
        return load(ContentLoader.class.getClassLoader(), INDEX_RESOURCE);
    }

    /** Public for tests and later external content packs. */
    public static Content load(ClassLoader classLoader, String indexResource) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(indexResource, "indexResource");
        JsonObject index = readObject(classLoader, indexResource);
        BlockRegistry blocks = new BlockRegistry();
        ItemRegistry items = new ItemRegistry();

        for (String path : pathList(index, "blocks", indexResource)) {
            JsonObject json = readObject(classLoader, path);
            try {
                blocks.register(parseBlock(json));
            } catch (RuntimeException e) {
                throw new IOException("Invalid block definition in " + path + ": " + e.getMessage(), e);
            }
        }
        for (String path : pathList(index, "items", indexResource)) {
            JsonObject json = readObject(classLoader, path);
            try {
                items.register(parseItem(json));
            } catch (RuntimeException e) {
                throw new IOException("Invalid item definition in " + path + ": " + e.getMessage(), e);
            }
        }
        validateReferences(blocks, items);
        TagRegistry tags = loadTags(classLoader, index, indexResource, blocks, items);
        return new Content(blocks, items, tags);
    }

    /** Read the tag index and resolve nested #references, rejecting cycles or bad members. */
    private static TagRegistry loadTags(ClassLoader loader, JsonObject index, String indexPath,
                                        BlockRegistry blocks, ItemRegistry items) throws IOException {
        Map<ResourceId, List<String>> blockEntries = readTagEntries(
                loader, pathListOptional(index, "block_tags", indexPath), "tags/blocks/");
        Map<ResourceId, List<String>> itemEntries = readTagEntries(
                loader, pathListOptional(index, "item_tags", indexPath), "tags/items/");
        return new TagRegistry(resolveTags(blockEntries, blocks, null),
                resolveTags(itemEntries, null, items));
    }

    private static List<String> pathListOptional(JsonObject index, String field,
                                                  String indexPath) throws IOException {
        return index.has(field) ? pathList(index, field, indexPath) : List.of();
    }

    private static Map<ResourceId, List<String>> readTagEntries(ClassLoader loader,
            List<String> paths, String directory) throws IOException {
        Map<ResourceId, List<String>> entries = new LinkedHashMap<>();
        for (String path : paths) {
            if (!path.startsWith("data/") || !path.contains("/" + directory)
                    || !path.endsWith(".json")) {
                throw new IOException("Invalid tag path in index: " + path);
            }
            String namespace = path.substring("data/".length(), path.indexOf('/', "data/".length()));
            int tagStart = path.indexOf("/" + directory) + 1 + directory.length();
            String tagPath = path.substring(tagStart, path.length() - ".json".length());
            ResourceId tag;
            try {
                tag = new ResourceId(namespace, tagPath);
            } catch (RuntimeException ex) {
                throw new IOException("Invalid tag ID for " + path, ex);
            }
            JsonObject json = readObject(loader, path);
            try {
                // A single generated tag file per ID. No hidden duplicate overrides.
                if (entries.containsKey(tag)) throw new IllegalArgumentException("Duplicate tag: " + tag);
                if (json.has("replace") && !json.get("replace").isJsonPrimitive()) {
                    throw new IllegalArgumentException("Invalid replace flag");
                }
                JsonArray values = json.getAsJsonArray("values");
                if (values == null) throw new IllegalArgumentException("Missing values array");
                List<String> raw = new ArrayList<>();
                for (JsonElement value : values) raw.add(value.getAsString());
                entries.put(tag, raw);
            } catch (RuntimeException ex) {
                throw new IOException("Invalid tag definition in " + path + ": " + ex.getMessage(), ex);
            }
        }
        return entries;
    }

    private static Map<ResourceId, Set<ResourceId>> resolveTags(
            Map<ResourceId, List<String>> definitions,
            BlockRegistry blocks, ItemRegistry items) throws IOException {
        Map<ResourceId, Set<ResourceId>> resolved = new LinkedHashMap<>();
        for (ResourceId tag : definitions.keySet()) {
            resolveTag(tag, definitions, resolved, new LinkedHashSet<>(), blocks, items);
        }
        return resolved;
    }

    private static Set<ResourceId> resolveTag(ResourceId tag,
            Map<ResourceId, List<String>> definitions,
            Map<ResourceId, Set<ResourceId>> resolved, Set<ResourceId> visiting,
            BlockRegistry blocks, ItemRegistry items) throws IOException {
        if (resolved.containsKey(tag)) return resolved.get(tag);
        if (!definitions.containsKey(tag)) throw new IOException("Unknown nested tag: #" + tag);
        if (!visiting.add(tag)) throw new IOException("Cyclic tag reference: " + visiting + " -> " + tag);
        LinkedHashSet<ResourceId> members = new LinkedHashSet<>();
        for (String raw : definitions.get(tag)) {
            boolean nested = raw.startsWith("#");
            ResourceId id;
            try { id = ResourceId.parse(nested ? raw.substring(1) : raw); }
            catch (RuntimeException ex) { throw new IOException("Invalid entry '" + raw + "' in tag #" + tag, ex); }
            if (nested) {
                members.addAll(resolveTag(id, definitions, resolved, visiting, blocks, items));
            } else if (blocks != null ? blocks.get(id) != null : items.get(id) != null) {
                members.add(id);
            } else {
                throw new IOException("Unknown member " + id + " in tag #" + tag);
            }
        }
        visiting.remove(tag);
        resolved.put(tag, members);
        return members;
    }

    /** Check that JSON agrees with all enum-backed content in existing saves. */
    public static void validateLegacyIds(Content content) throws IOException {
        BlockRegistry blocks = content.blocks();
        ItemRegistry items = content.items();
        for (BlockType type : BlockType.values()) {
            int legacyId = Byte.toUnsignedInt(type.getId());
            BlockDefinition definition = blocks.getByNumericId(legacyId);
            String expected = "wildforge:" + type.name().toLowerCase(java.util.Locale.ROOT);
            if (definition == null || !definition.getId().toString().equals(expected)) {
                throw new IOException("Legacy block ID mismatch: " + legacyId + " must be " + expected);
            }
            if (definition.isSolid() != type.isSolid()
                    || definition.isBreakable() != type.isBreakable()) {
                throw new IOException("Legacy block properties mismatch: " + expected);
            }
            ItemType drop = type.getDropItem();
            if (drop == null) {
                if (!definition.getDrops().isEmpty()) {
                    throw new IOException("Unexpected legacy block drops: " + expected);
                }
            } else if (definition.getDrops().size() != 1
                    || definition.getDrops().get(0).count() != type.getDropCount()
                    || items.get(definition.getDrops().get(0).item()) == null
                    || items.get(definition.getDrops().get(0).item()).getNumericId() != drop.getId()) {
                throw new IOException("Legacy block drops mismatch: " + expected);
            }
        }
        for (ItemType type : ItemType.values()) {
            ItemDefinition definition = items.getByNumericId(type.getId());
            String expected = "wildforge:" + type.name().toLowerCase(java.util.Locale.ROOT);
            if (definition == null || !definition.getId().toString().equals(expected)) {
                throw new IOException("Legacy item ID mismatch: " + type.getId() + " must be " + expected);
            }
            // Display names are localized and must not be compared to legacy enum text.
            if (definition.getMaxStackSize() != type.getMaxStackSize()) {
                throw new IOException("Legacy item stack size mismatch: " + expected);
            }
            BlockType placed = type.getPlacedBlock();
            ResourceId expectedBlock = placed == null ? null
                    : ResourceId.parse("wildforge:" + placed.name().toLowerCase(java.util.Locale.ROOT));
            if (!Objects.equals(definition.getPlacesBlock(), expectedBlock)) {
                throw new IOException("Legacy placed block mismatch: " + expected);
            }
        }
        if (items.getByNumericId(7) != null) {
            throw new IOException("Legacy item numeric ID 7 must remain unused");
        }
        // Block rendering and worldgen still rely on the old numeric block catalog.
        // Additional item definitions are now supported by registry-keyed inventory
        // and saves, although creative UI and dropped-entity rendering still need
        // adapters before custom item entries are fully playable.
        if (blocks.size() != BlockType.values().length) {
            throw new IOException("Generated blocks must match the current renderer: "
                    + blocks.size() + " vs " + BlockType.values().length);
        }
        if (items.size() < ItemType.values().length) {
            throw new IOException("Generated item catalog omits built-in items");
        }
    }

    private static JsonObject readObject(ClassLoader loader, String resource) throws IOException {
        if (resource.isBlank() || resource.startsWith("/") || resource.contains("..")
                || resource.indexOf('\\') >= 0) {
            throw new IOException("Invalid content resource path: " + resource);
        }
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Missing content resource: " + resource);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IOException("Expected JSON object in " + resource);
                }
                return root.getAsJsonObject();
            }
        } catch (RuntimeException ex) {
            throw new IOException("Invalid JSON in " + resource + ": " + ex.getMessage(), ex);
        }
    }

    private static List<String> pathList(JsonObject index, String field, String resource)
            throws IOException {
        try {
            JsonArray array = index.getAsJsonArray(field);
            if (array == null) {
                throw new IllegalArgumentException("Missing " + field + " array");
            }
            List<String> paths = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement element : array) {
                String path = element.getAsString();
                if (!seen.add(path)) {
                    throw new IllegalArgumentException("Duplicate indexed path: " + path);
                }
                paths.add(path);
            }
            return paths;
        } catch (RuntimeException ex) {
            throw new IOException("Invalid " + field + " index in " + resource + ": "
                    + ex.getMessage(), ex);
        }
    }

    private static BlockDefinition parseBlock(JsonObject json) {
        ResourceId id = requiredId(json, "id");
        List<BlockDefinition.Drop> drops = new ArrayList<>();
        if (json.has("drops")) {
            for (JsonElement element : json.getAsJsonArray("drops")) {
                JsonObject drop = element.getAsJsonObject();
                drops.add(new BlockDefinition.Drop(requiredId(drop, "item"),
                        requiredInt(drop, "count")));
            }
        }
        JsonObject textures = json.has("textures") ? json.getAsJsonObject("textures") : null;
        return new BlockDefinition(id, requiredInt(json, "numeric_id"),
                requiredBoolean(json, "solid"), requiredBoolean(json, "breakable"),
                requiredFloat(json, "hardness"), optionalId(json, "block_item"),
                optionalId(json, "model"), textures == null ? null : optionalId(textures, "top"),
                textures == null ? null : optionalId(textures, "side"),
                textures == null ? null : optionalId(textures, "bottom"), drops,
                json.has("mining_tool") ? requiredString(json, "mining_tool") : null,
                json.has("harvest_tier") ? requiredInt(json, "harvest_tier") : 0);
    }

    private static ItemDefinition parseItem(JsonObject json) {
        JsonObject tool = json.has("tool") ? json.getAsJsonObject("tool") : null;
        ItemDefinition.ToolComponent component = tool == null ? null :
                new ItemDefinition.ToolComponent(requiredString(tool, "kind"),
                        requiredInt(tool, "tier"), requiredFloat(tool, "speed"));
        return new ItemDefinition(requiredId(json, "id"), requiredInt(json, "numeric_id"),
                requiredInt(json, "max_stack_size"),
                optionalId(json, "places_block"), optionalId(json, "texture"),
                optionalId(json, "model"), component);
    }

    private static void validateReferences(BlockRegistry blocks, ItemRegistry items)
            throws IOException {
        for (BlockDefinition block : blocks.getAll()) {
            if (block.getBlockItem() != null) {
                ItemDefinition item = items.get(block.getBlockItem());
                if (item == null || !block.getId().equals(item.getPlacesBlock())) {
                    throw new IOException("Block item linkage invalid for " + block.getId());
                }
            }
            for (BlockDefinition.Drop drop : block.getDrops()) {
                if (items.get(drop.item()) == null) {
                    throw new IOException("Unknown item drop " + drop.item() + " in " + block.getId());
                }
            }
        }
        for (ItemDefinition item : items.getAll()) {
            if (item.getPlacesBlock() != null) {
                BlockDefinition block = blocks.get(item.getPlacesBlock());
                if (block == null || !item.getId().equals(block.getBlockItem())) {
                    throw new IOException("Item block linkage invalid for " + item.getId());
                }
            }
        }
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return object.get(field).getAsString();
    }
    private static int requiredInt(JsonObject object, String field) {
        return object.get(field) == null ? missing(field) : object.get(field).getAsInt();
    }
    private static int missing(String field) {
        throw new IllegalArgumentException("Missing field: " + field);
    }
    private static float requiredFloat(JsonObject object, String field) {
        if (!object.has(field)) { missing(field); }
        return object.get(field).getAsFloat();
    }
    private static boolean requiredBoolean(JsonObject object, String field) {
        if (!object.has(field)) { missing(field); }
        return object.get(field).getAsBoolean();
    }
    private static ResourceId requiredId(JsonObject object, String field) {
        return ResourceId.parse(requiredString(object, field));
    }
    private static ResourceId optionalId(JsonObject object, String field) {
        return !object.has(field) || object.get(field).isJsonNull()
                ? null : ResourceId.parse(object.get(field).getAsString());
    }
}
