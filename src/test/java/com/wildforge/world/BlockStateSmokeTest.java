package com.wildforge.world;

import java.util.HashSet;
import java.util.Set;

/** Headless, save-ID-stable block-state/model regression suite. */
public final class BlockStateSmokeTest {
    private BlockStateSmokeTest() {}
    public static void main(String[] args) {
        require(BlockModels.class.getResource("/assets/wildforge/models/block_models.properties") != null,
                "model definition resource missing from runtime classpath");
        Set<Integer> ids = new HashSet<>();
        for (BlockType legacy : BlockType.values()) {
            int id = Byte.toUnsignedInt(legacy.getId());
            require(ids.add(id), "duplicate ID: " + id);
            require(BlockType.isValidId(legacy.getId()), "invalid block id " + legacy);
            require(BlockType.fromId(legacy.getId()) == legacy, "ID round trip: " + legacy);
            BlockState state = BlockState.fromLegacy(legacy);
            require(state.toLegacyBlock() == legacy, "state round trip: " + legacy);
            require(BlockState.fromLegacyId(legacy.getId()).equals(state), "byte state: " + legacy);
            require(BlockModels.modelFor(state) != null, "missing model: " + legacy);
            for (BlockModels.Face face : BlockModels.Face.values())
                require(BlockModels.textureForFace(state, face) >= 0, "missing texture: " + legacy);
        }
        require(BlockType.fromId((byte) 255) == BlockType.AIR, "unknown ID fallback");
        require(BlockType.values().length == 195, "block ID count changed: review migration");

        BlockType[] logs = {
            BlockType.OAK_LOG, BlockType.PINE_LOG, BlockType.MAPLE_LOG,
            BlockType.WILLOW_LOG, BlockType.CYPRESS_LOG, BlockType.BIRCH_LOG,
            BlockType.SPRUCE_LOG, BlockType.ACACIA_LOG, BlockType.ALDER_LOG,
            BlockType.MANGROVE_LOG, BlockType.PALM_LOG
        };
        for (BlockType log : logs) {
            BlockState base = BlockState.fromLegacy(log);
            require(base.isLog() && base.axis() == BlockState.Axis.Y, "log default axis: " + log);
            for (BlockState.Axis axis : new BlockState.Axis[]{BlockState.Axis.X,BlockState.Axis.Y,BlockState.Axis.Z}) {
                BlockState state = base.withAxis(axis);
                BlockType encoded = state.toLegacyBlock();
                require(state.block() == log, "canonical wood: " + log);
                require(state.axis() == axis && encoded.isLog(), "axis: " + log + axis);
                require(encoded.getDropItem() == log.getDropItem(), "drop changed: " + encoded);
                require(encoded.getDropCount() == log.getDropCount(), "drop count changed: " + encoded);
                require(BlockState.fromLegacy(encoded).equals(state), "orientation codec: " + encoded);
                require(log.orientedLog(axis == BlockState.Axis.X ? 0 : axis == BlockState.Axis.Y ? 1 : 2) == encoded,
                        "legacy orientation adapter: " + log + axis);
                int end = log.getTopTextureLayer(), bark = log.getSideTextureLayer();
                require(BlockModels.textureForFace(state, BlockModels.Face.UP) == (axis == BlockState.Axis.Y ? end : bark), "up bark/cap " + encoded);
                require(BlockModels.textureForFace(state, BlockModels.Face.DOWN) == (axis == BlockState.Axis.Y ? end : bark), "down bark/cap " + encoded);
                require(BlockModels.textureForFace(state, BlockModels.Face.EAST) == (axis == BlockState.Axis.X ? end : bark), "east bark/cap " + encoded);
                require(BlockModels.textureForFace(state, BlockModels.Face.NORTH) == (axis == BlockState.Axis.Z ? end : bark), "north bark/cap " + encoded);
            }
        }
        require(!BlockType.STONE.isLog(), "stone is not a log");
        require(BlockModels.modelFor(BlockState.fromLegacy(BlockType.STONE)).shape() == BlockModels.Shape.CUBE, "stone cube");
        require(BlockModels.modelFor(BlockState.fromLegacy(BlockType.WATER)).shape() == BlockModels.Shape.FLUID, "water fluid model");
        require(!BlockModels.modelFor(BlockState.fromLegacy(BlockType.AIR)).fullCubeOcclusion(), "air occlusion");
        System.out.println("Block state/model check passed: " + ids.size() + " legacy block IDs and " + logs.length + " log species");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
