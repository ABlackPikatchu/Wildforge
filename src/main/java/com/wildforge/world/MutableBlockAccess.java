package com.wildforge.world;

/** Mutation entrypoint that can schedule fluid, light, and mesh updates. */
public interface MutableBlockAccess extends BlockAccess {
    boolean setBlockState(BlockPos pos, BlockState state);
    default boolean setBlock(BlockPos pos, BlockType type) {
        return setBlockState(pos, BlockState.fromLegacy(type));
    }
}
