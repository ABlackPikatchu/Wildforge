package com.wildforge.content;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Registry indexed by both stable resource ID and legacy byte-sized numeric ID. */
public final class BlockRegistry {
    private final Map<ResourceId, BlockDefinition> byId = new LinkedHashMap<>();
    private final Map<Integer, BlockDefinition> byNumericId = new LinkedHashMap<>();

    public void register(BlockDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (byId.containsKey(definition.getId())) {
            throw new IllegalArgumentException("Duplicate block ID: " + definition.getId());
        }
        if (byNumericId.containsKey(definition.getNumericId())) {
            throw new IllegalArgumentException("Duplicate block numeric_id: "
                    + definition.getNumericId() + " (" + definition.getId() + ")");
        }
        byId.put(definition.getId(), definition);
        byNumericId.put(definition.getNumericId(), definition);
    }

    public BlockDefinition get(ResourceId id) { return byId.get(id); }
    public BlockDefinition get(String id) { return get(ResourceId.parse(id)); }
    public BlockDefinition getByNumericId(int numericId) { return byNumericId.get(numericId); }
    public Collection<BlockDefinition> getAll() {
        return Collections.unmodifiableCollection(byId.values());
    }
    public int size() { return byId.size(); }
}
