
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
    SANDSTONE(17, 18, 1, true, true, 17, 17, 17),
    COBBLESTONE(18, 19, 1, true, true, 18, 18, 18),
    SNOW_GRASS(19, 20, 1, true, true, 19, 19, 19),
    SNOW(20, 21, 1, true, true, 20, 20, 20),
    TUNDRA_GRASS(21, 22, 1, true, true, 21, 21, 21),
    PERMAFROST(22, 23, 1, true, true, 22, 22, 22),
    PEAT(23, 24, 1, true, true, 23, 23, 23),
    RICH_HUMUS(24, 25, 1, true, true, 24, 24, 24),
    LEAF_LITTER(25, 26, 1, true, true, 25, 25, 25),
    PINE_NEEDLE_LITTER(26, 27, 1, true, true, 26, 26, 26),
    MOSS_BLOCK(27, 28, 1, true, true, 27, 27, 27),
    LIMESTONE(28, 29, 1, true, true, 28, 28, 28),
    SHALE(29, 30, 1, true, true, 29, 29, 29),
    SLATE(30, 31, 1, true, true, 30, 30, 30),
    GRANITE(31, 32, 1, true, true, 31, 31, 31),
    CHALK(32, 33, 1, true, true, 32, 32, 32),
    MUD(33, 34, 1, true, true, 33, 33, 33),
    WET_MUD(34, 35, 1, true, true, 34, 34, 34),
    SILT(35, 36, 1, true, true, 35, 35, 35),
    RED_SAND(36, 37, 1, true, true, 36, 36, 36),
    RED_SANDSTONE(37, 38, 1, true, true, 37, 37, 37),
    VOLCANIC_ASH(38, 39, 1, true, true, 38, 38, 38),
    SCORIA(39, 40, 1, true, true, 39, 39, 39),
    PUMICE(40, 41, 1, true, true, 40, 40, 40),
    OBSIDIAN(41, 42, 1, true, true, 41, 41, 41),
    SALT_CRUST(42, 43, 1, true, true, 42, 42, 42),
    SHELL_SAND(43, 44, 1, true, true, 43, 43, 43),
    RIVERBED_SEDIMENT(44, 45, 1, true, true, 44, 44, 44),
    WATERWORN_STONE(45, 46, 1, true, true, 45, 45, 45),
    ICE(46, 47, 1, true, true, 46, 46, 46),
    PACKED_SNOW(47, 48, 1, true, true, 47, 47, 47),
    SULFUR_ROCK(48, 49, 1, true, true, 48, 48, 48),
    QUARTZ_VEIN(49, 50, 1, true, true, 49, 49, 49),
    ALPINE_MOSS(50, 51, 1, true, true, 50, 50, 50),
    AUTUMN_LEAF_LITTER(51, 52, 1, true, true, 51, 51, 51),
    BASALT_COLUMN(52, 53, 1, true, true, 52, 52, 52),
    BEACH_PEBBLES(53, 54, 1, true, true, 53, 53, 53),
    CALCITE(54, 55, 1, true, true, 54, 54, 54),
    CAVE_CLAY(55, 56, 1, true, true, 55, 55, 55),
    CAVE_MOSS(56, 57, 1, true, true, 56, 56, 56),
    COAL_DUST_DEPOSIT(57, 58, 1, true, true, 57, 57, 57),
    COMPACTED_SEDIMENT(58, 59, 1, true, true, 58, 58, 58),
    CRACKED_BASALT(59, 60, 1, true, true, 59, 59, 59),
    CRACKED_EARTH(60, 61, 1, true, true, 60, 60, 60),
    EXPOSED_ROOT_STONE(61, 62, 1, true, true, 61, 61, 61),
    FAULT_BRECCIA(62, 63, 1, true, true, 62, 62, 62),
    FERTILE_LOAM(63, 64, 1, true, true, 63, 63, 63),
    FLOWSTONE(64, 65, 1, true, true, 64, 64, 64),
    FOSSIL_SHALE(65, 66, 1, true, true, 65, 65, 65),
    FRACTURED_SLATE(66, 67, 1, true, true, 66, 66, 66),
    FROSTED_ROCK(67, 68, 1, true, true, 67, 67, 67),
    FROZEN_PEAT(68, 69, 1, true, true, 68, 68, 68),
    GLACIAL_ICE(69, 70, 1, true, true, 69, 69, 69),
    GYPSUM(70, 71, 1, true, true, 70, 70, 70),
    IRON_STAINED_STONE(71, 72, 1, true, true, 71, 71, 71),
    LICHEN_STONE(72, 73, 1, true, true, 72, 72, 72),
    LIMESTONE_RUBBLE(73, 74, 1, true, true, 73, 73, 73),
    MAGMA_CRUST(74, 75, 1, true, true, 74, 74, 74),
    MINERAL_MATRIX(75, 76, 1, true, true, 75, 75, 75),
    MOSSY_ROCK(76, 77, 1, true, true, 76, 76, 76),
    MYCELIAL_MAT(77, 78, 1, true, true, 77, 77, 77),
    RED_CLAY(78, 79, 1, true, true, 78, 78, 78),
    ROCK_SALT(79, 80, 1, true, true, 79, 79, 79),
    ROOT_MAT(80, 81, 1, true, true, 80, 80, 80),
    ROPE_LAVA_STONE(81, 82, 1, true, true, 81, 81, 81),
    SANDSTONE_RUBBLE(82, 83, 1, true, true, 82, 82, 82),
    SCREE(83, 84, 1, true, true, 83, 83, 83),
    SILTSTONE(84, 85, 1, true, true, 84, 84, 84),
    SNOW_CRUST(85, 86, 1, true, true, 85, 85, 85),
    SUBMERGED_SCREE(86, 87, 1, true, true, 86, 86, 86),
    WEATHERED_LIMESTONE(87, 88, 1, true, true, 87, 87, 87),
    WET_ROCK(88, 89, 1, true, true, 88, 88, 88),
    WET_SAND(89, 90, 1, true, true, 89, 89, 89),
    WET_SLATE(90, 91, 1, true, true, 90, 90, 90),
    BRICK_BLOCK(91, 92, 1, true, true, 91, 91, 91),
    GLASS(92, 93, 1, true, true, 92, 92, 92),
    OAK_LOG(93, 94, 1, true, true, 94, 93, 94),
    OAK_PLANKS(94, 95, 1, true, true, 95, 95, 95),
    PINE_LOG(95, 96, 1, true, true, 97, 96, 97),
    PINE_PLANKS(96, 97, 1, true, true, 98, 98, 98),
    MAPLE_LOG(97, 98, 1, true, true, 100, 99, 100),
    MAPLE_PLANKS(98, 99, 1, true, true, 101, 101, 101),
    WILLOW_LOG(99, 100, 1, true, true, 103, 102, 103),
    WILLOW_PLANKS(100, 101, 1, true, true, 104, 104, 104),
    CYPRESS_LOG(101, 102, 1, true, true, 106, 105, 106),
    CYPRESS_PLANKS(102, 103, 1, true, true, 107, 107, 107),
    ROTTEN_WOOD(103, 104, 1, true, true, 109, 108, 109),
    CRAFTING_TABLE(104, 105, 1, true, true, 111, 110, 111),
    FURNACE(105, 106, 1, true, true, 113, 112, 113),
    CACTUS(106, 107, 1, true, true, 115, 114, 115);

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
    public static final int COBBLESTONE_TEXTURE = 18;

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
