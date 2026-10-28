package com.wildforge.content;

import java.util.Objects;
import com.wildforge.lang.LanguageManager;

/** Immutable item data. Legacy ItemType IDs and saves are unaffected. */
public final class ItemDefinition {
    private final ResourceId id;
    private final int numericId;
    private final int maxStackSize;
    private final ResourceId placesBlock;
    private final ResourceId texture;
    private final ResourceId model;

    public ItemDefinition(ResourceId id, int numericId, int maxStackSize, ResourceId placesBlock,
                          ResourceId texture, ResourceId model) {
        this.id = Objects.requireNonNull(id, "id");
        if (numericId < 1 || numericId > 65535) {
            throw new IllegalArgumentException("Item numeric_id must be 1..65535: " + id);
        }
        if (maxStackSize < 1 || maxStackSize > 999) {
            throw new IllegalArgumentException("Invalid max_stack_size for " + id);
        }
        this.numericId = numericId;
        this.maxStackSize = maxStackSize;
        this.placesBlock = placesBlock;
        this.texture = texture;
        this.model = model;
    }

    public ResourceId getId() { return id; }
    public int getNumericId() { return numericId; }
    public String getTranslationKey() { return LanguageManager.itemKey(id); }
    public String getDisplayName() {
        String fallback = id.getPath().replace('_', ' ');
        return LanguageManager.itemName(id, fallback);
    }
    public int getMaxStackSize() { return maxStackSize; }
    /** Null for materials such as coal. */
    public ResourceId getPlacesBlock() { return placesBlock; }
    public ResourceId getTexture() { return texture; }
    public ResourceId getModel() { return model; }
}
