package com.wildforge.player;

import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;

/** Minecraft-style tool speed, effective-tool classification and harvest tiers.
 * Uses stable enum names until the item registry carries tool components.
 * All timing is seconds, advanced only by the fixed physics tick.
 */
public final class MiningRules {
    private MiningRules() {}
    public enum Tool { HAND, PICKAXE, AXE, SHOVEL, HOE }
    public record ToolStats(Tool type,int tier,double speed) {}
    public static ToolStats tool(ItemType held) {
        if(held==null)return new ToolStats(Tool.HAND,0,1);
        String name=held.name();
        Tool kind=name.endsWith("_PICKAXE")?Tool.PICKAXE:
            name.endsWith("_SHOVEL")?Tool.SHOVEL:
            name.endsWith("_AXE")?Tool.AXE:
            name.endsWith("_HOE")?Tool.HOE:Tool.HAND;
        if(kind==Tool.HAND)return new ToolStats(Tool.HAND,0,1);
        int tier=name.startsWith("WOODEN_")?1:name.startsWith("STONE_")?2:
            name.startsWith("IRON_")||name.startsWith("SILVER_")?3:
            name.startsWith("DIAMOND_")?4:name.startsWith("GOLD_")?1:0;
        double speed=name.startsWith("WOODEN_")?2:name.startsWith("STONE_")?4:
            name.startsWith("IRON_")||name.startsWith("SILVER_")?6:
            name.startsWith("DIAMOND_")?8:name.startsWith("GOLD_")?12:1;
        return new ToolStats(kind,tier,speed);
    }
    public static Tool preferred(BlockType block) {
        String name=block.name();
        if(name.endsWith("_LOG")||name.endsWith("_LOG_X")||name.endsWith("_LOG_Z")||
           name.contains("WOOD")||name.contains("PLANK")||name.contains("FURNACE") || name.contains("CRAFTING_TABLE"))return Tool.AXE;
        if(name.contains("DIRT")||name.contains("GRASS")||name.contains("SAND")||name.contains("GRAVEL")||
            name.contains("SNOW")||name.contains("CLAY")||name.contains("MUD")||name.contains("SILT")||
            name.contains("LOAM")||name.contains("PEAT"))return Tool.SHOVEL;
        if(name.contains("ORE")||name.contains("STONE")||name.contains("SLATE")||name.contains("ROCK")||
            name.contains("BASALT")||name.contains("GRANITE")||name.contains("ANDESITE")||
            name.contains("DIORITE")||name.contains("SCORIA")||name.contains("OBSIDIAN")||name.contains("COBBLE")||
            name.contains("GYPSUM")||name.contains("CALCITE")||name.contains("SHALE"))return Tool.PICKAXE;
        return Tool.HAND;
    }
    public static int minimumTier(BlockType block) {
        String n=block.name();
        if(n.contains("DIAMOND_ORE")||n.contains("GOLD_ORE")||n.contains("OBSIDIAN"))return 3;
        if(n.contains("IRON_ORE")||n.contains("SILVER_ORE"))return 2;
        if(n.contains("COAL_ORE"))return 1;
        return 0;
    }
    public static boolean drops(BlockType block,ItemType held) {
        Tool required=preferred(block);
        if(required!=Tool.PICKAXE)return true;
        return tool(held).type()==Tool.PICKAXE && tool(held).tier()>=minimumTier(block);
    }
    public static double seconds(BlockType block,float hardness,ItemType held) {
        if(hardness<=0)return .05;
        ToolStats t=tool(held);
        boolean effective=t.type()==preferred(block) && t.type()!=Tool.HAND;
        double speed=effective?t.speed():1;
        boolean harvest=preferred(block)!=Tool.PICKAXE || drops(block,held);
        return Math.max(.05,hardness*(harvest?1.5:5.0)/speed);
    }
}
