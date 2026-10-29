package com.wildforge.content;

import com.wildforge.core.save.DroppedItemStorage;
import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import com.wildforge.ui.CreativeCatalog;
import com.wildforge.world.BlockAccess;
import com.wildforge.world.BlockBehaviors;
import com.wildforge.world.BlockModels;
import com.wildforge.world.BlockPos;
import com.wildforge.world.BlockState;
import com.wildforge.world.BlockType;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Headless check for registry-first creative, models, block placement and item-save migration. */
public final class Phase8ARegistryCompletionSmokeTest {
    static void check(boolean value, String reason) { if(!value)throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        ResourceId plant=ResourceId.parse("wildforge:custom_luminous_plant");
        ResourceId itemKey=ResourceId.parse("wildforge:custom_luminous_plant_item");
        BlockRegistry blocks=new BlockRegistry();
        blocks.register(new BlockDefinition(plant,300,false,true,.2f,itemKey,
                ResourceId.parse("wildforge:block/cross_plant"),
                ResourceId.parse("wildforge:clover"),ResourceId.parse("wildforge:clover"),
                ResourceId.parse("wildforge:clover"),List.of(new BlockDefinition.Drop(itemKey,1))));
        ItemRegistry items=new ItemRegistry();
        items.register(new ItemDefinition(itemKey,400,16,plant,
                ResourceId.parse("wildforge:clover"),ResourceId.parse("wildforge:item/block")));
        // Old numeric saves must still recognize their built-in item identity.
        ItemDefinition legacyDiamond=ItemCatalog.legacy(ItemType.DIAMOND);
        items.register(legacyDiamond);
        ItemCatalog.install(items);
        BlockCatalog.install(blocks);
        BlockModels.install(blocks);
        check(ItemCatalog.require(itemKey).getNumericId()==400,"registry ID above 255");
        check(new ItemStack(itemKey,12).maxStackSize()==16,"registry stack limit");
        ItemDefinition highTierTool=new ItemDefinition(ResourceId.parse("wildforge:custom_pickaxe"),
                401,1,null,ResourceId.parse("wildforge:diamond_pickaxe"),null,
                new ItemDefinition.ToolComponent("pickaxe",6,15));
        BlockDefinition toughOre=new BlockDefinition(ResourceId.parse("wildforge:tough_ore"),
                402,true,true,5f,null,ResourceId.parse("wildforge:block/cube"),null,null,null,
                List.of(),"pickaxe",5);
        check(com.wildforge.player.MiningRules.drops(toughOre,BlockType.DIAMOND_ORE,highTierTool),
                "registry-only high-tier tools must harvest high-tier ore");
        check(com.wildforge.player.MiningRules.seconds(toughOre,BlockType.DIAMOND_ORE,5f,highTierTool)
                <com.wildforge.player.MiningRules.seconds(toughOre,BlockType.DIAMOND_ORE,5f,(ItemDefinition)null),
                "registry tool speed must affect mining");
        check(CreativeCatalog.definitions("luminous",0).stream().anyMatch(i->i.getId().equals(itemKey)),
                "creative browser must include registry-only items");
        check(BlockModels.modelFor(plant).shape()==BlockModels.Shape.CROSS_PLANT,
                "block model must come from registered data");
        var behavior=BlockBehaviors.forBlock(plant);
        check(behavior!=null && behavior.support()==BlockBehaviors.Support.BELOW,"plant support rule");
        BlockAccess supported=new BlockAccess() {
            public boolean isLoaded(BlockPos pos){return true;}
            public BlockState getBlockState(BlockPos pos) {return pos.y()==0
                    ? BlockState.fromLegacy(BlockType.STONE) : BlockState.fromLegacy(BlockType.AIR);}
        };
        BlockAccess unsupported=new BlockAccess() {
            public boolean isLoaded(BlockPos pos){return true;}
            public BlockState getBlockState(BlockPos pos){return BlockState.fromLegacy(BlockType.AIR);}
        };
        check(behavior.canPlace(supported,new BlockPos(0,1,0)),"supported plant placement");
        check(!behavior.canPlace(unsupported,new BlockPos(0,1,0)),"unsupported plant placement");

        Path file=Files.createTempFile("wildforge-registry-drops", ".bin");
        try {
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(0x57464954);out.writeInt(2);out.writeInt(1);
                out.writeUTF(itemKey.toString());out.writeInt(12);
                out.writeFloat(1);out.writeFloat(100);out.writeFloat(2);
                out.writeFloat(0);out.writeFloat(0);out.writeFloat(0);out.writeFloat(1);
            }
            var decoded=DroppedItemStorage.readStacks(file);
            check(decoded.size()==1 && decoded.get(0).id().equals(itemKey) && decoded.get(0).count()==12,
                    "version-2 named drop identity");
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(0x57464954);out.writeInt(1);out.writeInt(1);
                out.writeInt(ItemType.DIAMOND.getId());out.writeInt(1);
                out.writeFloat(1);out.writeFloat(100);out.writeFloat(2);
                out.writeFloat(0);out.writeFloat(0);out.writeFloat(0);out.writeFloat(1);
            }
            check(DroppedItemStorage.readStacks(file).get(0).id().equals(ItemCatalog.legacyId(ItemType.DIAMOND)),
                    "version-1 numeric drop migration");
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(0x57464954);out.writeInt(2);out.writeInt(1);
                out.writeUTF("wildforge:missing_item");out.writeInt(2);
                for(int i=0;i<7;i++)out.writeFloat(0);
            }
            boolean rejected=false;
            try{DroppedItemStorage.readStacks(file);}catch(java.io.IOException expected){rejected=true;}
            check(rejected,"unknown named item must not be silently replaced");
        }finally {Files.deleteIfExists(file);}
        System.out.println("Phase 8A registry completion: creative, block models, support, v1/v2 drops PASS");
    }
}
