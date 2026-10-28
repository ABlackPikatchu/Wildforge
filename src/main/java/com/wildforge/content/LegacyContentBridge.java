package com.wildforge.content;

import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;

import java.util.Objects;

/** Connects JSON definitions to enum-backed gameplay without changing save IDs. */
public final class LegacyContentBridge {
    private final BlockRegistry blocks;
    private final ItemRegistry items;

    public LegacyContentBridge(ContentLoader.Content content) {
        Objects.requireNonNull(content, "content");
        this.blocks = content.blocks();
        this.items = content.items();
    }

    public BlockDefinition block(BlockType type) {
        Objects.requireNonNull(type, "type");
        BlockDefinition definition = blocks.getByNumericId(Byte.toUnsignedInt(type.getId()));
        if (definition == null) throw new IllegalStateException("Missing JSON block: " + type);
        return definition;
    }

    public ItemDefinition item(ItemType type) {
        Objects.requireNonNull(type, "type");
        ItemDefinition definition = items.getByNumericId(type.getId());
        if (definition == null) throw new IllegalStateException("Missing JSON item: " + type);
        return definition;
    }

    /** Returns null when the given JSON item does not belong to a legacy item enum. */
    public ItemType legacyItem(ResourceId id) {
        ItemDefinition definition = items.get(id);
        return definition == null ? null : ItemType.fromId(definition.getNumericId());
    }

    /** Returns null when a JSON block cannot be represented by a legacy world enum. */
    public BlockType legacyBlock(ResourceId id) {
        BlockDefinition definition = blocks.get(id);
        if (definition == null) return null;
        for (BlockType type : BlockType.values()) {
            if (Byte.toUnsignedInt(type.getId()) == definition.getNumericId()) return type;
        }
        return null;
    }
}
