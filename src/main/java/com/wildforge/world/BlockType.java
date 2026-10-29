
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
    CAVE_MOSS(56, 57, 1, true, true, 27, 27, 27),
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
    CACTUS(106, 107, 1, true, true, 115, 114, 115),
    WATER(107, 0, 0, false, false, 133, 133, 133),
    LAVA(108, 0, 0, false, false, 134, 134, 134),
    FLOWING_WATER(109, 0, 0, false, false, 133, 133, 133),
    FLOWING_LAVA(110, 0, 0, false, false, 134, 134, 134),
    // Axis variants are new save-stable block IDs.  They drop the original logs.
    OAK_LOG_X(111,94,1,true,true,94,93,94),
    OAK_LOG_Z(112,94,1,true,true,94,93,94),
    PINE_LOG_X(113,96,1,true,true,97,96,97),
    PINE_LOG_Z(114,96,1,true,true,97,96,97),
    MAPLE_LOG_X(115,98,1,true,true,100,99,100),
    MAPLE_LOG_Z(116,98,1,true,true,100,99,100),
    WILLOW_LOG_X(117,100,1,true,true,103,102,103),
    WILLOW_LOG_Z(118,100,1,true,true,103,102,103),
    CYPRESS_LOG_X(119,102,1,true,true,106,105,106),
    CYPRESS_LOG_Z(120,102,1,true,true,106,105,106),
    OCEAN_SILTY_SAND(121, 149, 1, true, true, 201, 201, 201),
    OCEAN_SHELL_GRIT(122, 150, 1, true, true, 202, 202, 202),
    OCEAN_COLD_GRAVEL(123, 151, 1, true, true, 203, 203, 203),
    OCEAN_ALGAE_STONE(124, 152, 1, true, true, 204, 204, 204),
    OCEAN_DARK_SILT(125, 153, 1, true, true, 205, 205, 205),
    OCEAN_BASALT_PEBBLES(126, 154, 1, true, true, 206, 206, 206),
    OCEAN_CORAL_SAND(127, 155, 1, true, true, 207, 207, 207),
    OCEAN_REEF_LIMESTONE(128, 156, 1, true, true, 208, 208, 208),
    OCEAN_DEAD_CORAL_RUBBLE(129, 157, 1, true, true, 209, 209, 209),
    OCEAN_ABYSSAL_SILT(130, 158, 1, true, true, 210, 210, 210),
    BIRCH_LOG(131, 159, 1, true, true, 178, 177, 178),
    BIRCH_PLANKS(132, 160, 1, true, true, 179, 179, 179),
    SPRUCE_LOG(133, 161, 1, true, true, 182, 181, 182),
    SPRUCE_PLANKS(134, 162, 1, true, true, 183, 183, 183),
    ACACIA_LOG(135, 163, 1, true, true, 186, 185, 186),
    ACACIA_PLANKS(136, 164, 1, true, true, 187, 187, 187),
    ALDER_LOG(137, 165, 1, true, true, 190, 189, 190),
    ALDER_PLANKS(138, 166, 1, true, true, 191, 191, 191),
    MANGROVE_LOG(139, 167, 1, true, true, 194, 193, 194),
    MANGROVE_PLANKS(140, 168, 1, true, true, 195, 195, 195),
    PALM_LOG(141, 169, 1, true, true, 198, 197, 198),
    PALM_PLANKS(142, 170, 1, true, true, 199, 199, 199),
    BIRCH_LOG_X(143, 159, 1, true, true, 178, 177, 178),
    BIRCH_LOG_Z(144, 159, 1, true, true, 178, 177, 178),
    SPRUCE_LOG_X(145, 161, 1, true, true, 182, 181, 182),
    SPRUCE_LOG_Z(146, 161, 1, true, true, 182, 181, 182),
    ACACIA_LOG_X(147, 163, 1, true, true, 186, 185, 186),
    ACACIA_LOG_Z(148, 163, 1, true, true, 186, 185, 186),
    ALDER_LOG_X(149, 165, 1, true, true, 190, 189, 190),
    ALDER_LOG_Z(150, 165, 1, true, true, 190, 189, 190),
    MANGROVE_LOG_X(151, 167, 1, true, true, 194, 193, 194),
    MANGROVE_LOG_Z(152, 167, 1, true, true, 194, 193, 194),
    PALM_LOG_X(153, 169, 1, true, true, 198, 197, 198),
    PALM_LOG_Z(154, 169, 1, true, true, 198, 197, 198),
    OAK_LEAVES(155, 171, 1, true, true, 116, 116, 116),
    PINE_LEAVES(156, 172, 1, true, true, 117, 117, 117),
    MAPLE_LEAVES(157, 173, 1, true, true, 118, 118, 118),
    WILLOW_LEAVES(158, 174, 1, true, true, 119, 119, 119),
    CYPRESS_LEAVES(159, 175, 1, true, true, 120, 120, 120),
    BIRCH_LEAVES(160, 176, 1, true, true, 180, 180, 180),
    SPRUCE_LEAVES(161, 177, 1, true, true, 184, 184, 184),
    ACACIA_LEAVES(162, 178, 1, true, true, 188, 188, 188),
    ALDER_LEAVES(163, 179, 1, true, true, 192, 192, 192),
    MANGROVE_LEAVES(164, 180, 1, true, true, 196, 196, 196),
    PALM_LEAVES(165, 181, 1, true, true, 200, 200, 200),
    GRASS_TUFT(166, 182, 1, false, true, 121, 121, 121),
    FERN_PLANT(167, 183, 1, false, true, 122, 122, 122),
    REED_PLANT(168, 184, 1, false, true, 123, 123, 123),
    DEAD_BUSH_PLANT(169, 185, 1, false, true, 124, 124, 124),
    SHRUB_PLANT(170, 186, 1, false, true, 125, 125, 125),
    MUSHROOM_PLANT(171, 187, 1, false, true, 126, 126, 126),
    POPPY_FLOWER(172, 188, 1, false, true, 128, 128, 128),
    DAISY_FLOWER(173, 189, 1, false, true, 129, 129, 129),
    LAVENDER_FLOWER(174, 190, 1, false, true, 131, 131, 131),
    STALACTITE(175, 191, 1, false, true, 227, 227, 227),
    STALAGMITE(176, 192, 1, false, true, 228, 228, 228),
    HANGING_ROOTS(177, 193, 1, false, true, 226, 226, 226),
    SPORECAP_FUNGUS(178, 194, 1, false, true, 229, 229, 229),
    CRYSTAL_CLUSTER(179, 195, 1, false, true, 49, 49, 49),
    SNOW_LAYER_1(180, 21, 1, true, true, 20, 20, 20),
    SNOW_LAYER_2(181, 21, 1, true, true, 20, 20, 20),
    SNOW_LAYER_3(182, 21, 1, true, true, 20, 20, 20),
    SNOW_LAYER_4(183, 21, 1, true, true, 20, 20, 20),
    SNOW_LAYER_5(184, 21, 1, true, true, 20, 20, 20),
    SNOW_LAYER_6(185, 21, 1, true, true, 20, 20, 20),
    SNOW_LAYER_7(186, 21, 1, true, true, 20, 20, 20),
    CLOVER_PLANT(187, 196, 1, false, true, 230, 230, 230),
    TALL_GRASS_PLANT(188, 197, 1, false, true, 231, 231, 231),
    CATTAIL_PLANT(189, 198, 1, false, true, 232, 232, 232),
    CORNFLOWER(190, 199, 1, false, true, 233, 233, 233),
    ORCHID_FLOWER(191, 200, 1, false, true, 234, 234, 234),
    FROST_SHRUB(192, 201, 1, false, true, 235, 235, 235),
    DRY_SCRUB(193, 202, 1, false, true, 236, 236, 236),
    LICHEN_PATCH(194, 203, 1, false, true, 237, 237, 237);

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

    /**
     * Compatibility adapter for the original axis-oriented log block IDs.
     * The logical state (wood species + axis) lives in BlockState; legacy ID
     * values are preserved on disk until an explicitly versioned migration.
     */
    public int textureForAxis(int faceAxis) {
        return BlockModels.textureForAxis(BlockState.fromLegacy(this), faceAxis);
    }

    /** Returns the actual texture for an individual face, including DOWN. */
    public int textureForFace(BlockModels.Face face) {
        return BlockModels.textureForFace(BlockState.fromLegacy(this), face);
    }

    public boolean isLog() {
        return BlockState.fromLegacy(this).isLog();
    }

    public BlockType orientedLog(int faceAxis) {
        if (!isLog()) return this;
        return BlockState.fromLegacy(this).withAxis(BlockState.Axis.fromFaceAxis(faceAxis)).toLegacyBlock();
    }

    public boolean isWater() { return this == WATER || this == FLOWING_WATER; }
    public boolean isLava() { return this == LAVA || this == FLOWING_LAVA; }
    public boolean isFluid() { return isWater() || isLava(); }

    public boolean isSolid() {
        return solid;
    }

    /** Fractional top surface for accumulating snow, full-height for ordinary blocks. */
    public float collisionHeight() {
        return isSnowLayer() ? snowLayers() / 8f : (solid ? 1f : 0f);
    }

    public boolean isSnowLayer() {
        int id = Byte.toUnsignedInt(this.id);
        return id >= 180 && id <= 186;
    }

    public int snowLayers() {
        return isSnowLayer() ? Byte.toUnsignedInt(id) - 179 : this == SNOW ? 8 : 0;
    }

    public static BlockType snowLayer(int count) {
        if (count < 1 || count > 8) throw new IllegalArgumentException("Snow layers: " + count);
        return count == 8 ? SNOW : fromId((byte)(179 + count));
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

    // Constant-time unsigned byte lookup; also verifies save-stable IDs are unique.
    private static final BlockType[] BY_ID = createIdLookup();

    private static BlockType[] createIdLookup() {
        BlockType[] result = new BlockType[256];
        for (BlockType type : values()) {
            int id = Byte.toUnsignedInt(type.id);
            if (result[id] != null)
                throw new IllegalStateException("Duplicate legacy block ID " + id);
            result[id] = type;
        }
        return result;
    }

    public static BlockType fromId(byte id) {
        BlockType type = BY_ID[Byte.toUnsignedInt(id)];
        return type == null ? AIR : type;
    }

    public static boolean isValidId(byte id) {
        return BY_ID[Byte.toUnsignedInt(id)] != null;
    }
}
