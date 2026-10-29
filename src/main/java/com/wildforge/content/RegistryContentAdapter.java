package com.wildforge.content;

import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;

import java.util.Objects;

/** Maps generated registries to the compact gameplay IDs used by inventory and chunks. */
public final class RegistryContentAdapter {
    private final BlockRegistry blocks;
    private final ItemRegistry items;

    public RegistryContentAdapter(ContentLoader.Content content) {
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

    /** Full registry-state lookup, including data-only blocks above 255. */
    public BlockDefinition block(com.wildforge.world.BlockState state) {
        Objects.requireNonNull(state, "state");
        return blocks.get(state.resourceId());
    }

    public ItemDefinition item(ItemType type) {
        Objects.requireNonNull(type, "type");
        ItemDefinition definition = items.getByNumericId(type.getId());
        if (definition == null) throw new IllegalStateException("Missing JSON item: " + type);
        return definition;
    }

    /** Returns null when the given JSON item does not belong to a gameplay item enum. */
    public ItemType itemType(ResourceId id) {
        ItemDefinition definition = items.get(id);
        return definition == null ? null : ItemType.fromId(definition.getNumericId());
    }

    /** Returns null when a JSON block cannot be represented by a gameplay block enum. */
    public BlockType blockType(ResourceId id) {
        BlockDefinition definition = blocks.get(id);
        if (definition == null) return null;
        for (BlockType type : BlockType.values()) {
            if (Byte.toUnsignedInt(type.getId()) == definition.getNumericId()) return type;
        }
        return null;
    }
}
