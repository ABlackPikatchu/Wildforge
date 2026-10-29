package com.wildforge.item;

import com.wildforge.content.*;
import com.wildforge.player.MiningRules;
import com.wildforge.world.BlockType;

/** Runs with Gradle's generated JSON resources, not handcrafted mock definitions. */
public final class Phase8AContentMetadataSmokeTest {
    public static void main(String[] args) throws Exception {
        ContentLoader.Content content = ContentLoader.loadBuiltIn();
        ContentLoader.validateLegacyIds(content);
        ItemCatalog.install(content.items());
        int tools=0;
        for (ItemDefinition item : content.items().getAll()) {
            if (item.getId().getPath().matches("(wooden|stone|iron|gold|silver|diamond)_(axe|hoe|pickaxe|shovel|sword)")) {
                if (item.getTool() == null) throw new AssertionError("Missing generated tool data " + item.getId());
                tools++;
            }
        }
        if (tools != 30) throw new AssertionError("Expected 30 generated tools, got " + tools);
        for (BlockDefinition block : content.blocks().getAll()) {
            if (block.getMiningTool() == null) throw new AssertionError("Missing mining rule " + block.getId());
        }
        BlockDefinition iron = content.blocks().get("wildforge:iron_ore");
        if (iron.getHarvestTier() != 2) throw new AssertionError("Iron harvest tier must be 2");
        if (MiningRules.tool(ItemType.DIAMOND_PICKAXE).tier() != 4)
            throw new AssertionError("Runtime tool metadata not respected");
        if (!MiningRules.drops(iron, BlockType.IRON_ORE, ItemType.STONE_PICKAXE))
            throw new AssertionError("Stone pickaxe must harvest iron");
        if (MiningRules.drops(iron, BlockType.IRON_ORE, ItemType.WOODEN_PICKAXE))
            throw new AssertionError("Wooden pickaxe cannot harvest iron");
        System.out.println("Phase 8A content metadata passed: 30 tools, " + content.blocks().size() + " mining rules");
    }
}
