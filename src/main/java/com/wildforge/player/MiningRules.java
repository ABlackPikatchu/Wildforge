package com.wildforge.player;

import com.wildforge.item.ItemType;
import com.wildforge.content.ItemCatalog;
import com.wildforge.content.ItemDefinition;
import com.wildforge.world.BlockType;

/** Minecraft-style tool speed, effective-tool classification and harvest tiers.
 * Uses stable enum names until the item registry carries tool components.
 * All timing is seconds, advanced only by the fixed physics tick.
 */
public final class MiningRules {
    private MiningRules() {}
    public enum Tool { HAND, PICKAXE, AXE, SHOVEL, HOE }
    public record ToolStats(Tool type,int tier,double speed) {}
    /** Authoritative registry-first tool lookup; supports custom tools beyond ItemType. */
    public static ToolStats tool(ItemDefinition item) {
        if(item==null || item.getTool()==null)return new ToolStats(Tool.HAND,0,1);
        var component=item.getTool();
        Tool kind=switch(component.kind()) {
            case "pickaxe" -> Tool.PICKAXE;
            case "axe" -> Tool.AXE;
            case "shovel" -> Tool.SHOVEL;
            case "hoe" -> Tool.HOE;
            default -> Tool.HAND;
        };
        return new ToolStats(kind,component.tier(),component.speed());
    }

    public static ToolStats tool(ItemType held) {
        if(held==null)return new ToolStats(Tool.HAND,0,1);
        ItemDefinition.ToolComponent component = ItemCatalog.legacy(held).getTool();
        if (component != null) {
            Tool kind = switch (component.kind()) {
                case "pickaxe" -> Tool.PICKAXE;
                case "axe" -> Tool.AXE;
                case "shovel" -> Tool.SHOVEL;
                case "hoe" -> Tool.HOE;
                default -> Tool.HAND;
            };
            return new ToolStats(kind, component.tier(), component.speed());
        }
        // Compatibility for isolated headless tests that do not load generated JSON.
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
    public static boolean drops(com.wildforge.content.BlockDefinition definition,
                                BlockType block, ItemDefinition held) {
        Tool required=preferred(definition,block);
        int tier=definition==null || definition.getMiningTool()==null ? minimumTier(block)
                : definition.getHarvestTier();
        ToolStats stats=tool(held);
        return required!=Tool.PICKAXE || (stats.type()==Tool.PICKAXE && stats.tier()>=tier);
    }

    public static double seconds(com.wildforge.content.BlockDefinition definition,
                                 BlockType block,float hardness,ItemDefinition held) {
        if(hardness<=0)return .05;
        ToolStats stats=tool(held);
        Tool preferred=preferred(definition,block);
        double speed=stats.type()==preferred && preferred!=Tool.HAND ? stats.speed() : 1;
        boolean harvestable=preferred!=Tool.PICKAXE || drops(definition,block,held);
        return Math.max(.05,hardness*(harvestable?1.5:5.0)/speed);
    }

    public static Tool preferred(com.wildforge.content.BlockDefinition definition, BlockType fallback) {
        if (definition == null || definition.getMiningTool() == null) return preferred(fallback);
        return switch (definition.getMiningTool()) {
            case "pickaxe" -> Tool.PICKAXE;
            case "axe" -> Tool.AXE;
            case "shovel" -> Tool.SHOVEL;
            case "hoe" -> Tool.HOE;
            default -> Tool.HAND;
        };
    }
    public static boolean drops(com.wildforge.content.BlockDefinition definition,
                                BlockType block, ItemType held) {
        Tool required = preferred(definition, block);
        int tier = definition == null || definition.getMiningTool() == null
                ? minimumTier(block) : definition.getHarvestTier();
        return required != Tool.PICKAXE || (tool(held).type() == Tool.PICKAXE && tool(held).tier() >= tier);
    }
    public static double seconds(com.wildforge.content.BlockDefinition definition,
                                 BlockType block, float hardness, ItemType held) {
        if (hardness <= 0) return .05;
        ToolStats stats = tool(held);
        Tool preferred = preferred(definition, block);
        double speed = stats.type() == preferred && preferred != Tool.HAND ? stats.speed() : 1;
        boolean harvestable = preferred != Tool.PICKAXE || drops(definition, block, held);
        return Math.max(.05, hardness * (harvestable ? 1.5 : 5.0) / speed);
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
