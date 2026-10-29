
package com.wildforge.render;

import com.wildforge.world.BlockType;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL12.glTexSubImage3D;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;

public final class BlockTextures implements AutoCloseable {
    public static final int TEXTURE_SIZE = 32;

    public static final int GRASS_TOP = BlockType.GRASS_TOP_TEXTURE;
    public static final int GRASS_SIDE = BlockType.GRASS_SIDE_TEXTURE;
    public static final int DIRT = BlockType.DIRT_TEXTURE;
    public static final int STONE = BlockType.STONE_TEXTURE;

    private static final String[] TEXTURE_PATHS = {
        "/textures/grass_top.png",   // 0
        "/textures/grass_side.png",  // 1
        "/textures/dirt.png",        // 2
        "/textures/stone.png",       // 3
        "/textures/andesite.png",    // 4
        "/textures/basalt.png",      // 5
        "/textures/bedrock.png",     // 6
        "/textures/clay.png",        // 7
        "/textures/coal_ore.png",    // 8
        "/textures/iron_ore.png",    // 9
        "/textures/gold_ore.png",    // 10
        "/textures/silver_ore.png",  // 11
        "/textures/diamond_ore.png", // 12
        "/textures/diorite.png",     // 13
        "/textures/gabbro.png",      // 14
        "/textures/gravel.png",      // 15
        "/textures/sand.png",        // 16
        "/textures/sandstone.png",   // 17
        "/textures/cobblestone.png", // 18
        "/textures/snow_grass.png",
        "/textures/snow.png",
        "/textures/tundra_grass.png",
        "/textures/permafrost.png",
        "/textures/peat.png",
        "/textures/rich_humus.png",
        "/textures/leaf_litter.png",
        "/textures/pine_needle_litter.png",
        "/textures/moss_block.png",
        "/textures/limestone.png",
        "/textures/shale.png",
        "/textures/slate.png",
        "/textures/granite.png",
        "/textures/chalk.png",
        "/textures/mud.png",
        "/textures/wet_mud.png",
        "/textures/silt.png",
        "/textures/red_sand.png",
        "/textures/red_sandstone.png",
        "/textures/volcanic_ash.png",
        "/textures/scoria.png",
        "/textures/pumice.png",
        "/textures/obsidian.png",
        "/textures/salt_crust.png",
        "/textures/shell_sand.png",
        "/textures/riverbed_sediment.png",
        "/textures/waterworn_stone.png",
        "/textures/ice.png",
        "/textures/packed_snow.png",
        "/textures/sulfur_rock.png",
        "/textures/quartz_vein.png",
        "/textures/alpine_moss.png",
        "/textures/autumn_leaf_litter.png",
        "/textures/basalt_column.png",
        "/textures/beach_pebbles.png",
        "/textures/calcite.png",
        "/textures/cave_clay.png",
        "/textures/cave_moss.png",
        "/textures/coal_dust_deposit.png",
        "/textures/compacted_sediment.png",
        "/textures/cracked_basalt.png",
        "/textures/cracked_earth.png",
        "/textures/exposed_root_stone.png",
        "/textures/fault_breccia.png",
        "/textures/fertile_loam.png",
        "/textures/flowstone.png",
        "/textures/fossil_shale.png",
        "/textures/fractured_slate.png",
        "/textures/frosted_rock.png",
        "/textures/frozen_peat.png",
        "/textures/glacial_ice.png",
        "/textures/gypsum.png",
        "/textures/iron_stained_stone.png",
        "/textures/lichen_stone.png",
        "/textures/limestone_rubble.png",
        "/textures/magma_crust.png",
        "/textures/mineral_matrix.png",
        "/textures/mossy_rock.png",
        "/textures/mycelial_mat.png",
        "/textures/red_clay.png",
        "/textures/rock_salt.png",
        "/textures/root_mat.png",
        "/textures/rope_lava_stone.png",
        "/textures/sandstone_rubble.png",
        "/textures/scree.png",
        "/textures/siltstone.png",
        "/textures/snow_crust.png",
        "/textures/submerged_scree.png",
        "/textures/weathered_limestone.png",
        "/textures/wet_rock.png",
        "/textures/wet_sand.png",
        "/textures/wet_slate.png",
        "/textures/brick_block.png",
        "/textures/glass.png",
        "/textures/oak_log_side.png",
        "/textures/oak_log_top.png",
        "/textures/oak_planks.png",
        "/textures/pine_log_side.png",
        "/textures/pine_log_top.png",
        "/textures/pine_planks.png",
        "/textures/maple_log_side.png",
        "/textures/maple_log_top.png",
        "/textures/maple_planks.png",
        "/textures/willow_log_side.png",
        "/textures/willow_log_top.png",
        "/textures/willow_planks.png",
        "/textures/cypress_log_side.png",
        "/textures/cypress_log_top.png",
        "/textures/cypress_planks.png",
        "/textures/rotten_wood_side.png",
        "/textures/rotten_wood_top.png",
        "/textures/crafting_table_side.png",
        "/textures/crafting_table_top.png",
        "/textures/furnace_side.png",
        "/textures/furnace_top.png",
        "/textures/cactus_side.png",
        "/textures/cactus_top.png",
        "/textures/oak_leaves.png",
        "/textures/pine_leaves.png",
        "/textures/maple_leaves.png",
        "/textures/willow_leaves.png",
        "/textures/cypress_leaves.png",
        "/textures/small_grass.png",
        "/textures/fern.png",
        "/textures/dune_grass.png",
        "/textures/dead_bush.png",
        "/textures/evergreen_shrub.png",
        "/textures/forest_mushroom.png",
        "/textures/flower_snowdrop.png",
        "/textures/flower_poppy.png",
        "/textures/flower_daisy.png",
        "/textures/flower_goldenrod.png",
        "/textures/flower_lavender.png",
        "/textures/flower_bluebell.png",
        "/textures/water.png", // 133
        "/textures/lava.png", // 134
        "/textures/coal.png",
        "/textures/brick.png",
        "/textures/diamond.png",
        "/textures/gold_ingot.png",
        "/textures/iron_ingot.png",
        "/textures/silver_ingot.png",
        "/textures/raw_gold.png",
        "/textures/raw_iron.png",
        "/textures/raw_silver.png",
        "/textures/pine_cone.png",
        "/textures/sea_shell.png",
        "/textures/stick.png",
        "/textures/wooden_axe.png",
        "/textures/wooden_hoe.png",
        "/textures/wooden_pickaxe.png",
        "/textures/wooden_shovel.png",
        "/textures/wooden_sword.png",
        "/textures/stone_axe.png",
        "/textures/stone_hoe.png",
        "/textures/stone_pickaxe.png",
        "/textures/stone_shovel.png",
        "/textures/stone_sword.png",
        "/textures/iron_axe.png",
        "/textures/iron_hoe.png",
        "/textures/iron_pickaxe.png",
        "/textures/iron_shovel.png",
        "/textures/iron_sword.png",
        "/textures/gold_axe.png",
        "/textures/gold_hoe.png",
        "/textures/gold_pickaxe.png",
        "/textures/gold_shovel.png",
        "/textures/gold_sword.png",
        "/textures/silver_axe.png",
        "/textures/silver_hoe.png",
        "/textures/silver_pickaxe.png",
        "/textures/silver_shovel.png",
        "/textures/silver_sword.png",
        "/textures/diamond_axe.png",
        "/textures/diamond_hoe.png",
        "/textures/diamond_pickaxe.png",
        "/textures/diamond_shovel.png",
        "/textures/diamond_sword.png",
        "/textures/birch_log_side.png",
        "/textures/birch_log_top.png",
        "/textures/birch_planks.png",
        "/textures/birch_leaves.png",
        "/textures/spruce_log_side.png",
        "/textures/spruce_log_top.png",
        "/textures/spruce_planks.png",
        "/textures/spruce_leaves.png",
        "/textures/acacia_log_side.png",
        "/textures/acacia_log_top.png",
        "/textures/acacia_planks.png",
        "/textures/acacia_leaves.png",
        "/textures/alder_log_side.png",
        "/textures/alder_log_top.png",
        "/textures/alder_planks.png",
        "/textures/alder_leaves.png",
        "/textures/mangrove_log_side.png",
        "/textures/mangrove_log_top.png",
        "/textures/mangrove_planks.png",
        "/textures/mangrove_leaves.png",
        "/textures/palm_log_side.png",
        "/textures/palm_log_top.png",
        "/textures/palm_planks.png",
        "/textures/palm_leaves.png",
        "/textures/ocean_silty_sand.png",
        "/textures/ocean_shell_grit.png",
        "/textures/ocean_cold_gravel.png",
        "/textures/ocean_algae_stone.png",
        "/textures/ocean_dark_silt.png",
        "/textures/ocean_basalt_pebbles.png",
        "/textures/ocean_coral_sand.png",
        "/textures/ocean_reef_limestone.png",
        "/textures/ocean_dead_coral_rubble.png",
        "/textures/ocean_abyssal_silt.png",
        "/textures/seagrass_short.png",
        "/textures/seagrass_broad.png",
        "/textures/seagrass_tall_segment.png",
        "/textures/kelp_stalk.png",
        "/textures/kelp_crown.png",
        "/textures/sea_fan_green.png",
        "/textures/coral_orange_branch.png",
        "/textures/coral_purple_brain.png",
        "/textures/coral_blue_tube.png",
        "/textures/coral_dead_branch.png",
        "/textures/coral_yellow_plate.png",
        "/textures/coral_red_branch.png",
        "/textures/coral_pink_fan.png",
        "/textures/anemone_green.png",
        "/textures/sponge_yellow.png",
        "/textures/hanging_roots.png",
        "/textures/stalactite.png",
        "/textures/stalagmite.png",
        "/textures/sporecap_fungus.png",
        "/textures/clover.png",
        "/textures/tall_grass.png",
        "/textures/cattails.png",
        "/textures/flower_cornflower.png",
        "/textures/flower_orchid.png",
        "/textures/frost_shrub.png",
        "/textures/dry_scrub.png",
        "/textures/lichen_patch.png"
    };

    /** Resolve registry textures using actual texture-array entries, not enum IDs. */
    public static int textureLayer(com.wildforge.content.ResourceId texture) {
        if (texture==null || !texture.getNamespace().equals("wildforge")) return -1;
        String path=texture.getPath();
        String filename="/textures/"+path+(path.endsWith(".png")?"":".png");
        for(int i=0;i<TEXTURE_PATHS.length;i++)
            if(TEXTURE_PATHS[i].equals(filename)) return i;
        return -1;
    }

    public static int itemTextureLayer(com.wildforge.content.ItemDefinition item) {
        return item==null ? -1 : textureLayer(item.getTexture());
    }

    /** Icon layers appended after terrain textures. IDs remain unchanged. */
    public static int itemTextureLayer(com.wildforge.item.ItemType item) {
        if(item==null)return -1;
        return switch(item.name().toLowerCase(java.util.Locale.ROOT)) {
            case "coal" -> 135;
            case "brick" -> 136;
            case "diamond" -> 137;
            case "gold_ingot" -> 138;
            case "iron_ingot" -> 139;
            case "silver_ingot" -> 140;
            case "raw_gold" -> 141;
            case "raw_iron" -> 142;
            case "raw_silver" -> 143;
            case "pine_cone" -> 144;
            case "sea_shell" -> 145;
            case "stick" -> 146;
            case "wooden_axe" -> 147;
            case "wooden_hoe" -> 148;
            case "wooden_pickaxe" -> 149;
            case "wooden_shovel" -> 150;
            case "wooden_sword" -> 151;
            case "stone_axe" -> 152;
            case "stone_hoe" -> 153;
            case "stone_pickaxe" -> 154;
            case "stone_shovel" -> 155;
            case "stone_sword" -> 156;
            case "iron_axe" -> 157;
            case "iron_hoe" -> 158;
            case "iron_pickaxe" -> 159;
            case "iron_shovel" -> 160;
            case "iron_sword" -> 161;
            case "gold_axe" -> 162;
            case "gold_hoe" -> 163;
            case "gold_pickaxe" -> 164;
            case "gold_shovel" -> 165;
            case "gold_sword" -> 166;
            case "silver_axe" -> 167;
            case "silver_hoe" -> 168;
            case "silver_pickaxe" -> 169;
            case "silver_shovel" -> 170;
            case "silver_sword" -> 171;
            case "diamond_axe" -> 172;
            case "diamond_hoe" -> 173;
            case "diamond_pickaxe" -> 174;
            case "diamond_shovel" -> 175;
            case "diamond_sword" -> 176;
            default -> -1;
        };
    }

    /** Metadata accessors let verification run without creating an OpenGL context. */
    public static int layerCount() {
        return TEXTURE_PATHS.length;
    }

    public static String resourcePathForLayer(int layer) {
        if (layer < 0 || layer >= TEXTURE_PATHS.length) {
            throw new IllegalArgumentException("Unknown texture layer: " + layer);
        }
        return TEXTURE_PATHS[layer];
    }

    private final int textureId;

    public BlockTextures() {
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);

        glTexImage3D(
            GL_TEXTURE_2D_ARRAY,
            0,
            GL_RGBA8,
            TEXTURE_SIZE,
            TEXTURE_SIZE,
            TEXTURE_PATHS.length,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            (ByteBuffer) null
        );

        for (int layer = 0; layer < TEXTURE_PATHS.length; layer++) {
            ByteBuffer pixels = loadTexture(TEXTURE_PATHS[layer]);

            glTexSubImage3D(
                GL_TEXTURE_2D_ARRAY,
                0,
                0,
                0,
                layer,
                TEXTURE_SIZE,
                TEXTURE_SIZE,
                1,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                pixels
            );
        }

        org.lwjgl.opengl.GL30.glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    private ByteBuffer loadTexture(String path) {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException(
                    "Missing texture resource: " + path
                );
            }

            BufferedImage image = ImageIO.read(input);

            if (image == null) {
                throw new IllegalStateException(
                    "Could not read texture image: " + path
                );
            }

            // Source pack includes some 32x64 two-frame animation strips.
            // Until the animated atlas exists, upload the first 32x32 frame.
            if (image.getWidth() != TEXTURE_SIZE
                    || (image.getHeight() != TEXTURE_SIZE && image.getHeight() != TEXTURE_SIZE*2)) {
                throw new IllegalStateException(
                    "Texture must be 32x32 or a 32x64 two-frame strip: " + path
                    + " (got " + image.getWidth()
                    + "x" + image.getHeight() + ")"
                );
            }

            ByteBuffer buffer = BufferUtils.createByteBuffer(
                TEXTURE_SIZE * TEXTURE_SIZE * 4
            );

            for (int y = 0; y < TEXTURE_SIZE; y++) {
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    int pixel = image.getRGB(x, y);

                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) (pixel & 0xFF));
                    buffer.put((byte) ((pixel >> 24) & 0xFF));
                }
            }

            buffer.flip();
            return buffer;

        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to load texture: " + path, e
            );
        }
    }

    public void bind(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureId);
    }

    @Override
    public void close() {
        if (textureId != 0) {
            glDeleteTextures(textureId);
        }
    }
}
