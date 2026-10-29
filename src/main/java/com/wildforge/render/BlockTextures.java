
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
        "/textures/lava.png"  // 134
    };

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

            if (image.getWidth() != TEXTURE_SIZE
                    || image.getHeight() != TEXTURE_SIZE) {
                throw new IllegalStateException(
                    "Texture must be 32x32: " + path
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
