package com.wildforge.item;

import com.wildforge.world.BlockType;
import com.wildforge.lang.LanguageManager;
import com.wildforge.content.ResourceId;
import java.util.Locale;

/**
 * Defines an inventory item. A null placed block means the item is not
 * placeable as a block (for example, coal).
 */
public enum ItemType {
    // IDs 1-3 match the old player-save block ordinals so existing
    // inventories continue loading correctly.
    GRASS_BLOCK(1, "Grass Block", 64, BlockType.GRASS),
    DIRT_BLOCK(2, "Dirt Block", 64, BlockType.DIRT),
    STONE_BLOCK(3, "Stone Block", 64, BlockType.STONE),
    COAL(4, "Coal", 64, null),

    ANDESITE_BLOCK(5, "Andesite", 64, BlockType.ANDESITE),
    BASALT_BLOCK(6, "Basalt", 64, BlockType.BASALT),
    CLAY_BLOCK(8, "Clay", 64, BlockType.CLAY),
    COAL_ORE_BLOCK(9, "Coal Ore", 64, BlockType.COAL_ORE),
    IRON_ORE_BLOCK(10, "Iron Ore", 64, BlockType.IRON_ORE),
    GOLD_ORE_BLOCK(11, "Gold Ore", 64, BlockType.GOLD_ORE),
    SILVER_ORE_BLOCK(12, "Silver Ore", 64, BlockType.SILVER_ORE),
    DIAMOND_ORE_BLOCK(13, "Diamond Ore", 64, BlockType.DIAMOND_ORE),
    DIORITE_BLOCK(14, "Diorite", 64, BlockType.DIORITE),
    GABBRO_BLOCK(15, "Gabbro", 64, BlockType.GABBRO),
    GRAVEL_BLOCK(16, "Gravel", 64, BlockType.GRAVEL),
    SAND_BLOCK(17, "Sand", 64, BlockType.SAND),
    SANDSTONE_BLOCK(18, "Sandstone", 64, BlockType.SANDSTONE);

    private final int id;
    private final String displayName;
    private final int maxStackSize;
    private final BlockType placedBlock;

    ItemType(
            int id,
            String displayName,
            int maxStackSize,
            BlockType placedBlock) {
        this.id = id;
        this.displayName = displayName;
        this.maxStackSize = maxStackSize;
        this.placedBlock = placedBlock;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return LanguageManager.itemName(ResourceId.parse("wildforge:"
                + name().toLowerCase(Locale.ROOT)), displayName);
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    public BlockType getPlacedBlock() {
        return placedBlock;
    }

    public static ItemType fromId(int id) {
        for (ItemType itemType : values()) {
            if (itemType.id == id) {
                return itemType;
            }
        }
        return null;
    }
}