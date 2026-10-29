package com.wildforge.world.fluid;

import com.wildforge.world.BlockType;

/** Stateless liquid mechanics. Scheduling/edits are handled by the world simulation. */
public final class FluidRules {
    public static final float TICK_SECONDS = .05f;
    public static final int WATER_DELAY_TICKS = 5;
    public static final int LAVA_DELAY_TICKS = 30;
    private FluidRules() { }

    public static int delay(BlockType liquid) {
        if (liquid == null || !liquid.isFluid())
            throw new IllegalArgumentException("Expected a liquid block");
        return liquid.isLava() ? LAVA_DELAY_TICKS : WATER_DELAY_TICKS;
    }

    public static float surfaceHeight(int strength) {
        if (strength < 1 || strength > 7)
            throw new IllegalArgumentException("Fluid strength must be 1..7");
        return strength >= 7 ? .94f : .20f + .105f * strength;
    }

    public static boolean formsWaterSource(BlockType below, BlockType east,
                                           BlockType west, BlockType south, BlockType north) {
        if (below == null || (!below.isSolid() && below != BlockType.WATER)) return false;
        int sources = 0;
        if (east == BlockType.WATER) sources++;
        if (west == BlockType.WATER) sources++;
        if (south == BlockType.WATER) sources++;
        if (north == BlockType.WATER) sources++;
        return sources >= 2;
    }

    public static boolean activatesBorder(BlockType source, int sourceStrength,
                                          BlockType adjacent, int adjacentStrength) {
        return source != null && adjacent != null && source.isFluid() &&
            (adjacent == BlockType.AIR ||
                (adjacent.isFluid() &&
                    (source.isWater() != adjacent.isWater() || sourceStrength > adjacentStrength)));
    }
}
