package com.wildforge.world;

/**
 * Rendering-independent read contract for world simulations. A missing chunk
 * is NOT equivalent to air: callers must check isLoaded before simulating.
 */
public interface BlockAccess {
    boolean isLoaded(BlockPos pos);
    BlockState getBlockState(BlockPos pos);
    default BlockType getBlock(BlockPos pos) { return getBlockState(pos).toLegacyBlock(); }
}
