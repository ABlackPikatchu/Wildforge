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
    SANDSTONE_BLOCK(18, "Sandstone", 64, BlockType.SANDSTONE),
    COBBLESTONE_BLOCK(19, "Cobblestone", 64, BlockType.COBBLESTONE),
    SNOW_GRASS_BLOCK(20, "Snow Grass", 64, BlockType.SNOW_GRASS),
    SNOW_BLOCK(21, "Snow", 64, BlockType.SNOW),
    TUNDRA_GRASS_BLOCK(22, "Tundra Grass", 64, BlockType.TUNDRA_GRASS),
    PERMAFROST_BLOCK(23, "Permafrost", 64, BlockType.PERMAFROST),
    PEAT_BLOCK(24, "Peat", 64, BlockType.PEAT),
    RICH_HUMUS_BLOCK(25, "Rich Humus", 64, BlockType.RICH_HUMUS),
    LEAF_LITTER_BLOCK(26, "Leaf Litter", 64, BlockType.LEAF_LITTER),
    PINE_NEEDLE_LITTER_BLOCK(27, "Pine Needle Litter", 64, BlockType.PINE_NEEDLE_LITTER),
    MOSS_BLOCK_BLOCK(28, "Moss Block", 64, BlockType.MOSS_BLOCK),
    LIMESTONE_BLOCK(29, "Limestone", 64, BlockType.LIMESTONE),
    SHALE_BLOCK(30, "Shale", 64, BlockType.SHALE),
    SLATE_BLOCK(31, "Slate", 64, BlockType.SLATE),
    GRANITE_BLOCK(32, "Granite", 64, BlockType.GRANITE),
    CHALK_BLOCK(33, "Chalk", 64, BlockType.CHALK),
    MUD_BLOCK(34, "Mud", 64, BlockType.MUD),
    WET_MUD_BLOCK(35, "Wet Mud", 64, BlockType.WET_MUD),
    SILT_BLOCK(36, "Silt", 64, BlockType.SILT),
    RED_SAND_BLOCK(37, "Red Sand", 64, BlockType.RED_SAND),
    RED_SANDSTONE_BLOCK(38, "Red Sandstone", 64, BlockType.RED_SANDSTONE),
    VOLCANIC_ASH_BLOCK(39, "Volcanic Ash", 64, BlockType.VOLCANIC_ASH),
    SCORIA_BLOCK(40, "Scoria", 64, BlockType.SCORIA),
    PUMICE_BLOCK(41, "Pumice", 64, BlockType.PUMICE),
    OBSIDIAN_BLOCK(42, "Obsidian", 64, BlockType.OBSIDIAN),
    SALT_CRUST_BLOCK(43, "Salt Crust", 64, BlockType.SALT_CRUST),
    SHELL_SAND_BLOCK(44, "Shell Sand", 64, BlockType.SHELL_SAND),
    RIVERBED_SEDIMENT_BLOCK(45, "Riverbed Sediment", 64, BlockType.RIVERBED_SEDIMENT),
    WATERWORN_STONE_BLOCK(46, "Waterworn Stone", 64, BlockType.WATERWORN_STONE),
    ICE_BLOCK(47, "Ice", 64, BlockType.ICE),
    PACKED_SNOW_BLOCK(48, "Packed Snow", 64, BlockType.PACKED_SNOW),
    SULFUR_ROCK_BLOCK(49, "Sulfur Rock", 64, BlockType.SULFUR_ROCK),
    QUARTZ_VEIN_BLOCK(50, "Quartz Vein", 64, BlockType.QUARTZ_VEIN),
    ALPINE_MOSS_BLOCK(51, "Alpine Moss", 64, BlockType.ALPINE_MOSS),
    AUTUMN_LEAF_LITTER_BLOCK(52, "Autumn Leaf Litter", 64, BlockType.AUTUMN_LEAF_LITTER),
    BASALT_COLUMN_BLOCK(53, "Basalt Column", 64, BlockType.BASALT_COLUMN),
    BEACH_PEBBLES_BLOCK(54, "Beach Pebbles", 64, BlockType.BEACH_PEBBLES),
    CALCITE_BLOCK(55, "Calcite", 64, BlockType.CALCITE),
    CAVE_CLAY_BLOCK(56, "Cave Clay", 64, BlockType.CAVE_CLAY),
    CAVE_MOSS_BLOCK(57, "Cave Moss", 64, BlockType.CAVE_MOSS),
    COAL_DUST_DEPOSIT_BLOCK(58, "Coal Dust Deposit", 64, BlockType.COAL_DUST_DEPOSIT),
    COMPACTED_SEDIMENT_BLOCK(59, "Compacted Sediment", 64, BlockType.COMPACTED_SEDIMENT),
    CRACKED_BASALT_BLOCK(60, "Cracked Basalt", 64, BlockType.CRACKED_BASALT),
    CRACKED_EARTH_BLOCK(61, "Cracked Earth", 64, BlockType.CRACKED_EARTH),
    EXPOSED_ROOT_STONE_BLOCK(62, "Exposed Root Stone", 64, BlockType.EXPOSED_ROOT_STONE),
    FAULT_BRECCIA_BLOCK(63, "Fault Breccia", 64, BlockType.FAULT_BRECCIA),
    FERTILE_LOAM_BLOCK(64, "Fertile Loam", 64, BlockType.FERTILE_LOAM),
    FLOWSTONE_BLOCK(65, "Flowstone", 64, BlockType.FLOWSTONE),
    FOSSIL_SHALE_BLOCK(66, "Fossil Shale", 64, BlockType.FOSSIL_SHALE),
    FRACTURED_SLATE_BLOCK(67, "Fractured Slate", 64, BlockType.FRACTURED_SLATE),
    FROSTED_ROCK_BLOCK(68, "Frosted Rock", 64, BlockType.FROSTED_ROCK),
    FROZEN_PEAT_BLOCK(69, "Frozen Peat", 64, BlockType.FROZEN_PEAT),
    GLACIAL_ICE_BLOCK(70, "Glacial Ice", 64, BlockType.GLACIAL_ICE),
    GYPSUM_BLOCK(71, "Gypsum", 64, BlockType.GYPSUM),
    IRON_STAINED_STONE_BLOCK(72, "Iron Stained Stone", 64, BlockType.IRON_STAINED_STONE),
    LICHEN_STONE_BLOCK(73, "Lichen Stone", 64, BlockType.LICHEN_STONE),
    LIMESTONE_RUBBLE_BLOCK(74, "Limestone Rubble", 64, BlockType.LIMESTONE_RUBBLE),
    MAGMA_CRUST_BLOCK(75, "Magma Crust", 64, BlockType.MAGMA_CRUST),
    MINERAL_MATRIX_BLOCK(76, "Mineral Matrix", 64, BlockType.MINERAL_MATRIX),
    MOSSY_ROCK_BLOCK(77, "Mossy Rock", 64, BlockType.MOSSY_ROCK),
    MYCELIAL_MAT_BLOCK(78, "Mycelial Mat", 64, BlockType.MYCELIAL_MAT),
    RED_CLAY_BLOCK(79, "Red Clay", 64, BlockType.RED_CLAY),
    ROCK_SALT_BLOCK(80, "Rock Salt", 64, BlockType.ROCK_SALT),
    ROOT_MAT_BLOCK(81, "Root Mat", 64, BlockType.ROOT_MAT),
    ROPE_LAVA_STONE_BLOCK(82, "Rope Lava Stone", 64, BlockType.ROPE_LAVA_STONE),
    SANDSTONE_RUBBLE_BLOCK(83, "Sandstone Rubble", 64, BlockType.SANDSTONE_RUBBLE),
    SCREE_BLOCK(84, "Scree", 64, BlockType.SCREE),
    SILTSTONE_BLOCK(85, "Siltstone", 64, BlockType.SILTSTONE),
    SNOW_CRUST_BLOCK(86, "Snow Crust", 64, BlockType.SNOW_CRUST),
    SUBMERGED_SCREE_BLOCK(87, "Submerged Scree", 64, BlockType.SUBMERGED_SCREE),
    WEATHERED_LIMESTONE_BLOCK(88, "Weathered Limestone", 64, BlockType.WEATHERED_LIMESTONE),
    WET_ROCK_BLOCK(89, "Wet Rock", 64, BlockType.WET_ROCK),
    WET_SAND_BLOCK(90, "Wet Sand", 64, BlockType.WET_SAND),
    WET_SLATE_BLOCK(91, "Wet Slate", 64, BlockType.WET_SLATE),
    BRICK_BLOCK_BLOCK(92, "Brick Block", 64, BlockType.BRICK_BLOCK),
    GLASS_BLOCK(93, "Glass", 64, BlockType.GLASS),
    OAK_LOG_BLOCK(94, "Oak Log", 64, BlockType.OAK_LOG),
    OAK_PLANKS_BLOCK(95, "Oak Planks", 64, BlockType.OAK_PLANKS),
    PINE_LOG_BLOCK(96, "Pine Log", 64, BlockType.PINE_LOG),
    PINE_PLANKS_BLOCK(97, "Pine Planks", 64, BlockType.PINE_PLANKS),
    MAPLE_LOG_BLOCK(98, "Maple Log", 64, BlockType.MAPLE_LOG),
    MAPLE_PLANKS_BLOCK(99, "Maple Planks", 64, BlockType.MAPLE_PLANKS),
    WILLOW_LOG_BLOCK(100, "Willow Log", 64, BlockType.WILLOW_LOG),
    WILLOW_PLANKS_BLOCK(101, "Willow Planks", 64, BlockType.WILLOW_PLANKS),
    CYPRESS_LOG_BLOCK(102, "Cypress Log", 64, BlockType.CYPRESS_LOG),
    CYPRESS_PLANKS_BLOCK(103, "Cypress Planks", 64, BlockType.CYPRESS_PLANKS),
    ROTTEN_WOOD_BLOCK(104, "Rotten Wood", 64, BlockType.ROTTEN_WOOD),
    CRAFTING_TABLE_BLOCK(105, "Crafting Table", 64, BlockType.CRAFTING_TABLE),
    FURNACE_BLOCK(106, "Furnace", 64, BlockType.FURNACE),
    CACTUS_BLOCK(107, "Cactus", 64, BlockType.CACTUS),
    BRICK(108, "Brick", 64, null),
    DIAMOND(109, "Diamond", 64, null),
    GOLD_INGOT(110, "Gold Ingot", 64, null),
    IRON_INGOT(111, "Iron Ingot", 64, null),
    SILVER_INGOT(112, "Silver Ingot", 64, null),
    RAW_GOLD(113, "Raw Gold", 64, null),
    RAW_IRON(114, "Raw Iron", 64, null),
    RAW_SILVER(115, "Raw Silver", 64, null),
    PINE_CONE(116, "Pine Cone", 64, null),
    SEA_SHELL(117, "Sea Shell", 64, null),
    STICK(118, "Stick", 64, null),
    WOODEN_AXE(119, "Wooden Axe", 1, null),
    WOODEN_HOE(120, "Wooden Hoe", 1, null),
    WOODEN_PICKAXE(121, "Wooden Pickaxe", 1, null),
    WOODEN_SHOVEL(122, "Wooden Shovel", 1, null),
    WOODEN_SWORD(123, "Wooden Sword", 1, null),
    STONE_AXE(124, "Stone Axe", 1, null),
    STONE_HOE(125, "Stone Hoe", 1, null),
    STONE_PICKAXE(126, "Stone Pickaxe", 1, null),
    STONE_SHOVEL(127, "Stone Shovel", 1, null),
    STONE_SWORD(128, "Stone Sword", 1, null),
    IRON_AXE(129, "Iron Axe", 1, null),
    IRON_HOE(130, "Iron Hoe", 1, null),
    IRON_PICKAXE(131, "Iron Pickaxe", 1, null),
    IRON_SHOVEL(132, "Iron Shovel", 1, null),
    IRON_SWORD(133, "Iron Sword", 1, null),
    GOLD_AXE(134, "Gold Axe", 1, null),
    GOLD_HOE(135, "Gold Hoe", 1, null),
    GOLD_PICKAXE(136, "Gold Pickaxe", 1, null),
    GOLD_SHOVEL(137, "Gold Shovel", 1, null),
    GOLD_SWORD(138, "Gold Sword", 1, null),
    SILVER_AXE(139, "Silver Axe", 1, null),
    SILVER_HOE(140, "Silver Hoe", 1, null),
    SILVER_PICKAXE(141, "Silver Pickaxe", 1, null),
    SILVER_SHOVEL(142, "Silver Shovel", 1, null),
    SILVER_SWORD(143, "Silver Sword", 1, null),
    DIAMOND_AXE(144, "Diamond Axe", 1, null),
    DIAMOND_HOE(145, "Diamond Hoe", 1, null),
    DIAMOND_PICKAXE(146, "Diamond Pickaxe", 1, null),
    DIAMOND_SHOVEL(147, "Diamond Shovel", 1, null),
    DIAMOND_SWORD(148, "Diamond Sword", 1, null),
    OCEAN_SILTY_SAND_BLOCK(149, "Ocean Silty Sand", 64, BlockType.OCEAN_SILTY_SAND),
    OCEAN_SHELL_GRIT_BLOCK(150, "Ocean Shell Grit", 64, BlockType.OCEAN_SHELL_GRIT),
    OCEAN_COLD_GRAVEL_BLOCK(151, "Ocean Cold Gravel", 64, BlockType.OCEAN_COLD_GRAVEL),
    OCEAN_ALGAE_STONE_BLOCK(152, "Ocean Algae Stone", 64, BlockType.OCEAN_ALGAE_STONE),
    OCEAN_DARK_SILT_BLOCK(153, "Ocean Dark Silt", 64, BlockType.OCEAN_DARK_SILT),
    OCEAN_BASALT_PEBBLES_BLOCK(154, "Ocean Basalt Pebbles", 64, BlockType.OCEAN_BASALT_PEBBLES),
    OCEAN_CORAL_SAND_BLOCK(155, "Ocean Coral Sand", 64, BlockType.OCEAN_CORAL_SAND),
    OCEAN_REEF_LIMESTONE_BLOCK(156, "Ocean Reef Limestone", 64, BlockType.OCEAN_REEF_LIMESTONE),
    OCEAN_DEAD_CORAL_RUBBLE_BLOCK(157, "Ocean Dead Coral Rubble", 64, BlockType.OCEAN_DEAD_CORAL_RUBBLE),
    OCEAN_ABYSSAL_SILT_BLOCK(158, "Ocean Abyssal Silt", 64, BlockType.OCEAN_ABYSSAL_SILT),
    BIRCH_LOG_BLOCK(159, "Birch Log", 64, BlockType.BIRCH_LOG),
    BIRCH_PLANKS_BLOCK(160, "Birch Planks", 64, BlockType.BIRCH_PLANKS),
    SPRUCE_LOG_BLOCK(161, "Spruce Log", 64, BlockType.SPRUCE_LOG),
    SPRUCE_PLANKS_BLOCK(162, "Spruce Planks", 64, BlockType.SPRUCE_PLANKS),
    ACACIA_LOG_BLOCK(163, "Acacia Log", 64, BlockType.ACACIA_LOG),
    ACACIA_PLANKS_BLOCK(164, "Acacia Planks", 64, BlockType.ACACIA_PLANKS),
    ALDER_LOG_BLOCK(165, "Alder Log", 64, BlockType.ALDER_LOG),
    ALDER_PLANKS_BLOCK(166, "Alder Planks", 64, BlockType.ALDER_PLANKS),
    MANGROVE_LOG_BLOCK(167, "Mangrove Log", 64, BlockType.MANGROVE_LOG),
    MANGROVE_PLANKS_BLOCK(168, "Mangrove Planks", 64, BlockType.MANGROVE_PLANKS),
    PALM_LOG_BLOCK(169, "Palm Log", 64, BlockType.PALM_LOG),
    PALM_PLANKS_BLOCK(170, "Palm Planks", 64, BlockType.PALM_PLANKS);

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