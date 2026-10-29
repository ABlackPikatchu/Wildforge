package com.wildforge.phase6;

import com.wildforge.world.BlockType;
import com.wildforge.item.ItemType;
import com.wildforge.world.light.LightEngine;
import com.wildforge.worldgen.*;
import com.wildforge.player.MiningRules;
import java.util.*;

/** Headless tests for Phase 6, with no graphics context and no generator resources. */
public final class Phase6EngineSmokeTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        testPipeline();testDensity();testMining();testLighting();testFeatureRules();testAquiferContinuity();
        System.out.println("Phase 6 passed: ordered passes, 3D density, mining speeds/tiers, sky and block light, placement modifiers");
    }
    private static void testPipeline(){
        var order=new ArrayList<String>();
        var stages=new ChunkGenerationPipeline.Builder<ArrayList<String>>()
                .add("base", a -> a.add("base"))
                .add("carvers",a -> a.add("carvers"))
                .add("features",a -> a.add("features")).build();
        check(stages.stageNames().equals(List.of("base","carvers","features")),"Wrong stage order");
        stages.run(order);
        check(order.equals(stages.stageNames()),"Stage execution did not preserve registration order");
    }
    private static void testDensity(){
        TerrainDensityGenerator d=new TerrainDensityGenerator(15561L,224);
        boolean full3D=false;
        for(int x=-96;x<96;x+=7)for(int z=-96;z<96;z+=7){
            double a=d.sample(x,308,z,314,12);
            check(a==d.sample(x,308,z,314,12),"Nondeterministic density");
            if(Math.abs(a-6.5)>1.2)full3D=true;
            check(d.sample(x,100,z,314,12)>0,"Deep crust should remain solid before carvers");
            check(d.sample(x,340,z,314,12)<0,"Space above mountain should be empty");
            check(d.sample(x,314,z,314,0)==.5,"Flat land should follow heightfield");
        }
        check(full3D,"Density has no 3D relief");
    }
    private static void testMining(){
        double bare=MiningRules.seconds(BlockType.STONE,2f,null);
        double wood=MiningRules.seconds(BlockType.STONE,2f,ItemType.WOODEN_PICKAXE);
        double diamond=MiningRules.seconds(BlockType.STONE,2f,ItemType.DIAMOND_PICKAXE);
        check(diamond<wood&&wood<bare,"Tool tiers do not change mining time");
        check(!MiningRules.drops(BlockType.DIAMOND_ORE,ItemType.STONE_PICKAXE),"Low-tier diamond harvest accepted");
        check(MiningRules.drops(BlockType.DIAMOND_ORE,ItemType.IRON_PICKAXE),"Iron should harvest diamond");
        check(MiningRules.seconds(BlockType.DIRT,1f,ItemType.WOODEN_SHOVEL)<MiningRules.seconds(BlockType.DIRT,1f,null),"Shovel ineffective");
    }
    private static void testLighting(){
        byte[] blocks=new byte[LightEngine.LENGTH];
        // Make a fully opaque roof separating a cave from outdoor skylight.
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)blocks[(x*16+z)*512+260]=BlockType.STONE.getId();
        int middle=(8*16+8)*512;
        blocks[middle+100]=BlockType.LAVA.getId();
        long before=System.nanoTime();
        LightEngine light=LightEngine.bake(blocks);
        check(light.sky(8,290,8)==15,"Outdoor skylight should be 15");
        check(light.sky(8,200,8)==0,"Solid roof failed to block skylight");
        check(light.block(8,100,8)==15,"Lava must emit level 15");
        check(light.block(9,100,8)==14,"Block light should fall off one level per cell");
        check(light.block(8,100,8)>light.block(14,100,8),"Block light falloff missing");
        byte[] north=new byte[16*512];
        LightEngine connected=LightEngine.bake(blocks,null,null,north,null);
        check(connected.sky(8,200,0)>0,"Skylight should enter from a lit neighbor edge");
        check(connected.sky(8,200,15)==0,"Indirect light should attenuate across the cave");
        blocks[middle+100]=BlockType.SPORECAP_FUNGUS.getId();
        check(LightEngine.bake(blocks).block(8,100,8)>0,"Natural fungus should illuminate caves");
        System.out.println("Lighting bake: "+((System.nanoTime()-before)/1_000_000)+" ms including seam and emissive checks");
    }
    private static void testAquiferContinuity(){
        WorldGenSettings s=new WorldGenSettings(0,511,224,1400,260,310,48,780,500,43,-.2,.12,.075,.85,.55);
        AquiferSampler aquifer=new AquiferSampler(123L,s,AquiferSampler.defaults());
        for(int x=-144;x<=144;x+=72){
            double left=aquifer.waterTableAt(x-1,0),right=aquifer.waterTableAt(x,0);
            check(Math.abs(left-right)<2,"Aquifer water table jumps at a cell seam");
        }
    }
    private static void testFeatureRules(){
        var f=new FeaturePlacementEngine(FeaturePlacementEngine.defaults());
        check(f.treeChance(nullSafeProfile(),.85,.9,.1)>0,"Configured tree chance absent");
        check(f.treeChance(nullSafeProfile(),1,1,1)<=.91,"Tree chance cap missing");
        check(f.bankChance()>0,"Riverbank vegetation settings missing");
    }
    private static BiomeProfile nullSafeProfile(){
        return new BiomeProfile(Biome.GREENHEART_FOREST,"Forest",0,
            new BiomeProfile.Range(0,1),new BiomeProfile.Range(0,1),
            new BiomeProfile.Range(-100,300),List.of(),false,false,1,
            "grass","dirt",.65,.5,5,4,List.of(),List.of());
    }
}
