package com.wildforge.content;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable resolved block/item tags, keyed by namespaced tag IDs. */
public final class TagRegistry {
    private final Map<ResourceId, Set<ResourceId>> blockTags;
    private final Map<ResourceId, Set<ResourceId>> itemTags;

    public TagRegistry(Map<ResourceId, ? extends Collection<ResourceId>> blocks,
                       Map<ResourceId, ? extends Collection<ResourceId>> items) {
        blockTags = freeze(blocks);
        itemTags = freeze(items);
    }

    private static Map<ResourceId, Set<ResourceId>> freeze(
            Map<ResourceId, ? extends Collection<ResourceId>> input) {
        Objects.requireNonNull(input, "tags");
        Map<ResourceId, Set<ResourceId>> copy = new LinkedHashMap<>();
        input.forEach((id, members) -> {
            Objects.requireNonNull(id, "tag id");
            Objects.requireNonNull(members, "tag members");
            copy.put(id, Collections.unmodifiableSet(new LinkedHashSet<>(members)));
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Unknown tags return false; invalid resource IDs throw IllegalArgumentException. */
    public boolean containsBlock(ResourceId tag, ResourceId block) {
        return blockTags.getOrDefault(tag, Set.of()).contains(block);
    }

    public boolean containsBlock(String tag, String block) {
        return containsBlock(ResourceId.parse(tag), ResourceId.parse(block));
    }

    public boolean containsItem(ResourceId tag, ResourceId item) {
        return itemTags.getOrDefault(tag, Set.of()).contains(item);
    }

    public boolean containsItem(String tag, String item) {
        return containsItem(ResourceId.parse(tag), ResourceId.parse(item));
    }

    public Set<ResourceId> getBlocks(ResourceId tag) {
        return blockTags.getOrDefault(tag, Set.of());
    }

    public Set<ResourceId> getItems(ResourceId tag) {
        return itemTags.getOrDefault(tag, Set.of());
    }

    public int blockTagCount() { return blockTags.size(); }
    public int itemTagCount() { return itemTags.size(); }
}
