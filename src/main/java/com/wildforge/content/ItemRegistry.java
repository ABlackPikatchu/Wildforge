package com.wildforge.content;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Registry indexed by both stable resource ID and legacy item numeric ID. */
public final class ItemRegistry {
    private final Map<ResourceId, ItemDefinition> byId = new LinkedHashMap<>();
    private final Map<Integer, ItemDefinition> byNumericId = new LinkedHashMap<>();

    public void register(ItemDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (byId.containsKey(definition.getId())) {
            throw new IllegalArgumentException("Duplicate item ID: " + definition.getId());
        }
        if (byNumericId.containsKey(definition.getNumericId())) {
            throw new IllegalArgumentException("Duplicate item numeric_id: "
                    + definition.getNumericId() + " (" + definition.getId() + ")");
        }
        byId.put(definition.getId(), definition);
        byNumericId.put(definition.getNumericId(), definition);
    }

    public ItemDefinition get(ResourceId id) { return byId.get(id); }
    public ItemDefinition get(String id) { return get(ResourceId.parse(id)); }
    public ItemDefinition getByNumericId(int numericId) { return byNumericId.get(numericId); }
    public Collection<ItemDefinition> getAll() {
        return Collections.unmodifiableCollection(byId.values());
    }
    public int size() { return byId.size(); }
}
