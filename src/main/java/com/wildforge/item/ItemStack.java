package com.wildforge.item;

import com.wildforge.content.ItemCatalog;
import com.wildforge.content.ItemDefinition;
import com.wildforge.content.ResourceId;
import java.util.Objects;

/** Registry-keyed stack. Content identity survives runtime ID changes. */
public final class ItemStack {
    private final ItemDefinition definition;
    private int count;

    public ItemStack(ItemType legacy, int count) {
        this(legacy == null ? null : ItemCatalog.legacy(legacy), count);
    }
    public ItemStack(ResourceId key, int count) {
        this(key == null ? null : ItemCatalog.require(key), count);
    }
    public ItemStack(ItemDefinition item, int count) {
        if (item == null || count <= 0) {
            definition = null;
            this.count = 0;
        } else {
            definition = Objects.requireNonNull(item);
            this.count = Math.min(count, item.getMaxStackSize());
        }
    }

    public ItemDefinition definition() { return definition; }
    public ResourceId itemId() { return definition == null ? null : definition.getId(); }
    /** Compatibility adapter; null for a data-only item without a legacy enum entry. */
    public ItemType getItemType() {
        return definition == null ? null : ItemCatalog.legacyAdapter(definition.getId());
    }
    public int maxStackSize() { return definition == null ? 0 : definition.getMaxStackSize(); }
    public int getCount() { return count; }
    public void setCount(int next) {
        count = definition == null ? 0 : Math.max(0, Math.min(next, definition.getMaxStackSize()));
    }
    public boolean isEmpty() { return definition == null || count <= 0; }
    public boolean sameItem(ItemStack other) {
        return other != null && !isEmpty() && !other.isEmpty() && itemId().equals(other.itemId());
    }
}
