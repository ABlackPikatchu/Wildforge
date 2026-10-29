package com.wildforge.world;

import com.wildforge.world.fluid.FluidRules;

/** Headless pure-fluid-rule checks without loading a generated world. */
public final class FluidRulesSmokeTest {
    private FluidRulesSmokeTest() { }
    public static void main(String[] args) {
        check(FluidRules.delay(BlockType.WATER)==5,"water tick delay");
        check(FluidRules.delay(BlockType.LAVA)==30,"lava tick delay");
        check(FluidRules.surfaceHeight(7)>FluidRules.surfaceHeight(1),"strength/height order");
        check(FluidRules.formsWaterSource(BlockType.STONE,BlockType.WATER,
                BlockType.WATER,BlockType.AIR,BlockType.AIR),"supported water replenishes");
        check(!FluidRules.formsWaterSource(BlockType.AIR,BlockType.WATER,
                BlockType.WATER,BlockType.AIR,BlockType.AIR),"unsupported water remains flow");
        check(!FluidRules.formsWaterSource(BlockType.STONE,BlockType.LAVA,
                BlockType.LAVA,BlockType.AIR,BlockType.AIR),"lava does not replenish");
        check(FluidRules.activatesBorder(BlockType.WATER,7,BlockType.AIR,0),"empty seam opens");
        check(!FluidRules.activatesBorder(BlockType.WATER,7,BlockType.WATER,7),"still ocean stays inert");
        System.out.println("Fluid rule checks passed: tick timing, levels, sources, seams");
    }
    private static void check(boolean condition,String description) {
        if (!condition)throw new AssertionError(description);
    }
}
