package com.wildforge.worldgen;

import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;
import com.wildforge.render.BlockTextures;
import java.util.HashSet;

/** Content/saved-ID checks that don't require a running OpenGL context. */
public final class OceanTreeIntegrationSmokeTest {
    private static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(BlockType.values().length==155,"Block registry count changed");
        check(ItemType.values().length==169,"Item registry count changed");
        check(BlockTextures.layerCount()==226,"Missing texture atlas layers");
        var seen=new HashSet<Integer>();
        for(BlockType block:BlockType.values())
            check(seen.add(Byte.toUnsignedInt(block.getId())),"Duplicate block ID: "+block);
        var seenItems=new HashSet<Integer>();
        for(ItemType item:ItemType.values())
            check(seenItems.add(item.getId()),"Duplicate item ID: "+item);
        for(String wood:new String[]{"BIRCH","SPRUCE","ACACIA","ALDER","MANGROVE","PALM"}) {
            BlockType root=BlockType.valueOf(wood+"_LOG");
            BlockType x=BlockType.valueOf(wood+"_LOG_X");
            BlockType z=BlockType.valueOf(wood+"_LOG_Z");
            ItemType item=ItemType.valueOf(wood+"_LOG_BLOCK");
            check(root.orientedLog(0)==x && root.orientedLog(1)==root && root.orientedLog(2)==z,
                    "Invalid log-axis variant: "+wood);
            check(x.getDropItem()==item && z.getDropItem()==item && root.getDropItem()==item,
                    "Different drops across orientations: "+wood);
            check(x.textureForAxis(0)==root.getTopTextureLayer() &&
                  z.textureForAxis(2)==root.getTopTextureLayer(),"Misaligned log end grain: "+wood);
        }
        for(String ocean:new String[]{"OCEAN_SILTY_SAND","OCEAN_SHELL_GRIT","OCEAN_COLD_GRAVEL",
                "OCEAN_ALGAE_STONE","OCEAN_DARK_SILT","OCEAN_BASALT_PEBBLES","OCEAN_CORAL_SAND",
                "OCEAN_REEF_LIMESTONE","OCEAN_DEAD_CORAL_RUBBLE","OCEAN_ABYSSAL_SILT"}) {
            BlockType block=BlockType.valueOf(ocean);
            check(block.getDropItem()==ItemType.valueOf(ocean+"_BLOCK"),
                    "Seabed block missing inventory item: "+ocean);
        }
        check(WorldGenerator.TreeType.values().length==11,"Expected eleven tree species");
        check(WorldGenerator.OceanBiome.values().length==10,"Expected ten marine regions");
        System.out.println("Ocean/tree registry smoke test passed: 155 blocks, 169 items, 11 trees, 10 oceans.");
    }
}
