package com.wildforge.content;

import com.wildforge.item.ItemType;
import java.util.Locale;
import java.util.Objects;

/** Session item identity. Numeric enum values are compatibility adapters, not save IDs.
 * The installed JSON registry is authoritative after startup. */
public final class ItemCatalog {
    private static volatile ItemRegistry registry;
    private ItemCatalog() { }

    public static void install(ItemRegistry items) {
        Objects.requireNonNull(items, "items");
        // Install only a fully validated registry. Startup owns this transition.
        registry = items;
    }

    public static java.util.List<ItemDefinition> all() {
        ItemRegistry loaded = registry;
        if (loaded != null) return java.util.List.copyOf(loaded.getAll());
        java.util.List<ItemDefinition> fallback = new java.util.ArrayList<>();
        for (ItemType item : ItemType.values()) if (item.getId()>0) fallback.add(fallback(item));
        return java.util.List.copyOf(fallback);
    }

    public static ItemDefinition byId(ResourceId id) {
        Objects.requireNonNull(id, "id");
        ItemRegistry current = registry;
        if (current != null) return current.get(id);
        for (ItemType item : ItemType.values()) {
            if (legacyId(item).equals(id)) return fallback(item);
        }
        return null;
    }

    public static ItemDefinition require(ResourceId id) {
        ItemDefinition item = byId(id);
        if (item == null) throw new IllegalArgumentException("Unknown item: " + id);
        return item;
    }

    public static ItemDefinition legacy(ItemType type) {
        Objects.requireNonNull(type, "type");
        ItemRegistry current = registry;
        if (current != null) {
            ItemDefinition definition = current.get(legacyId(type));
            if (definition == null || definition.getNumericId() != type.getId())
                throw new IllegalStateException("Item registry mismatch: " + type);
            return definition;
        }
        return fallback(type);
    }

    public static ResourceId legacyId(ItemType type) {
        return ResourceId.parse("wildforge:" + type.name().toLowerCase(Locale.ROOT));
    }

    private static ItemDefinition fallback(ItemType type) {
        ResourceId placed = type.getPlacedBlock() == null ? null :
                ResourceId.parse("wildforge:" + type.getPlacedBlock().name().toLowerCase(Locale.ROOT));
        return new ItemDefinition(legacyId(type), type.getId(), type.getMaxStackSize(), placed, null, null);
    }

    /** Old renderer/controls still use enum-backed assets during staged migration. */
    public static ItemType legacyAdapter(ResourceId id) {
        ItemDefinition definition = byId(id);
        if (definition == null) return null;
        ItemType type = ItemType.fromId(definition.getNumericId());
        return type != null && legacyId(type).equals(id) ? type : null;
    }
}
