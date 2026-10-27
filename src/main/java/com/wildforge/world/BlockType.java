
package com.wildforge.world;

import com.wildforge.item.ItemType;

/**
 * Defines blocks that can occupy cells in the world.
 *
 * Block IDs are part of the world-save format. Never change an existing ID.
 */
public enum BlockType {
    AIR(0, 0, 0, false, false, 2, 2, 2),
    GRASS(1, 1, 1, true, true, 0, 1, 2),
    DIRT(2, 2, 1, true, true, 2, 2, 2),
    STONE(3, 3, 1, true, true, 3, 3, 3),

    ANDESITE(4, 5, 1, true, true, 4, 4, 4),
    BASALT(5, 6, 1, true, true, 5, 5, 5),
    BEDROCK(6, 0, 0, true, false, 6, 6, 6),
    CLAY(7, 8, 1, true, true, 7, 7, 7),
    COAL_ORE(8, 9, 1, true, true, 8, 8, 8),
    IRON_ORE(9, 10, 1, true, true, 9, 9, 9),
    GOLD_ORE(10, 11, 1, true, true, 10, 10, 10),
    SILVER_ORE(11, 12, 1, true, true, 11, 11, 11),
    DIAMOND_ORE(12, 13, 1, true, true, 12, 12, 12),
    DIORITE(13, 14, 1, true, true, 13, 13, 13),
    GABBRO(14, 15, 1, true, true, 14, 14, 14),
    GRAVEL(15, 16, 1, true, true, 15, 15, 15),
    SAND(16, 17, 1, true, true, 16, 16, 16),
    SANDSTONE(17, 18, 1, true, true, 17, 17, 17);

    // Texture-array layer IDs. Keep these aligned with BlockTextures.
    public static final int GRASS_TOP_TEXTURE = 0;
    public static final int GRASS_SIDE_TEXTURE = 1;
    public static final int DIRT_TEXTURE = 2;
    public static final int STONE_TEXTURE = 3;

    public static final int ANDESITE_TEXTURE = 4;
    public static final int BASALT_TEXTURE = 5;
    public static final int BEDROCK_TEXTURE = 6;
    public static final int CLAY_TEXTURE = 7;
    public static final int COAL_ORE_TEXTURE = 8;
    public static final int IRON_ORE_TEXTURE = 9;
    public static final int GOLD_ORE_TEXTURE = 10;
    public static final int SILVER_ORE_TEXTURE = 11;
    public static final int DIAMOND_ORE_TEXTURE = 12;
    public static final int DIORITE_TEXTURE = 13;
    public static final int GABBRO_TEXTURE = 14;
    public static final int GRAVEL_TEXTURE = 15;
    public static final int SAND_TEXTURE = 16;
    public static final int SANDSTONE_TEXTURE = 17;

    final byte id;

    private final int dropItemId;
    private final int dropCount;
    private final boolean solid;
    private final boolean breakable;
    private final int topTextureLayer;
    private final int sideTextureLayer;
    private final int bottomTextureLayer;

    BlockType(
            int id,
            int dropItemId,
            int dropCount,
            boolean solid,
            boolean breakable,
            int topTextureLayer,
            int sideTextureLayer,
            int bottomTextureLayer
    ) {
        this.id = (byte) id;
        this.dropItemId = dropItemId;
        this.dropCount = dropCount;
        this.solid = solid;
        this.breakable = breakable;
        this.topTextureLayer = topTextureLayer;
        this.sideTextureLayer = sideTextureLayer;
        this.bottomTextureLayer = bottomTextureLayer;
    }

    public byte getId() {
        return id;
    }

    public ItemType getDropItem() {
        return ItemType.fromId(dropItemId);
    }

    public int getDropCount() {
        return dropCount;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean isBreakable() {
        return breakable;
    }

    public int getTopTextureLayer() {
        return topTextureLayer;
    }

    public int getSideTextureLayer() {
        return sideTextureLayer;
    }

    public int getBottomTextureLayer() {
        return bottomTextureLayer;
    }

    static BlockType fromId(byte id) {
        for (BlockType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return AIR;
    }

    public static boolean isValidId(byte id) {
        for (BlockType type : values()) {
            if (type.id == id) {
                return true;
            }
        }
        return false;
    }
}
