package com.wildforge.worldgen;

import com.wildforge.world.BlockType;
import java.util.EnumMap;
import java.util.List;

/** Headless generation invariants: deterministic bedrock, geology, ores, cave shapes,
 * local aquifers, and complete ocean palettes. No OpenGL context is necessary. */
public final class WorldCompositionSmokeTest {
    public static void main(String[] args) {
        int sea=224;
        WorldGenSettings settings=new WorldGenSettings(0,511,sea,1400,260,310,48,780,500,43,
                -.20,.12,.075,.85,.55);
        testStrata(sea);
        testOres();
        testCaves(settings);
        testAquifers(settings);
        testOceanCatalog();
        testVegetationCatalog();
        testUndergroundPalettes();
        System.out.println("World composition passed: bedrock, dominant rock, ore bands, caves, aquifers, 10 oceans, 11 trees, 12 cave palettes");
    }
    private static void testStrata(int sea){
        StrataGenerator s=new StrataGenerator(7851L,sea,-64,8,5,5);
        int total=0,bedrockByY[]=new int[5],stone=0,slate=0,accent=0;
        for(int x=-48;x<48;x+=2)for(int z=-48;z<48;z+=2){
            for(int y=0;y<5;y++)if(s.bedrockAt(x,y,z))bedrockByY[y]++;
            for(int y:new int[]{8,38,108,145,170,214,285,330}){
                var b=s.rockAt(x,y,z,BlockType.GABBRO);total++;
                if(b==BlockType.STONE)stone++;
                else if(b==BlockType.SLATE)slate++;
                else if(b==BlockType.GABBRO)accent++;
                else throw new AssertionError("Invalid crust material: "+b);
            }
        }
        check(bedrockByY[0]==48*48,"Floor must be entirely bedrock");
        check(bedrockByY[1]>bedrockByY[2]&&bedrockByY[2]>bedrockByY[3]&&bedrockByY[3]>bedrockByY[4],
              "Bedrock layers should become sparser upward");
        check(stone+slate>total*.88,"Stone and slate must dominate deep and surface rock");
        check(accent>total*.01&&accent<total*.10,"Geologic accents must be sparse");
        check(s.rockAt(100,8,-100,null)==BlockType.SLATE,"Deep layer should be slate");
        check(s.rockAt(100,270,-100,null)==BlockType.STONE,"Upper crust should be stone");
        check(!s.bedrockAt(0,5,0)&&!s.bedrockAt(0,-1,0),"No bedrock outside bottom five layers");
        check(s.bedrockAt(11,2,-9)==new StrataGenerator(7851L,sea,-64,8,5,5).bedrockAt(11,2,-9),
            "Bedrock must depend on seed and world position, not generation order");
    }
    private static void testOres(){
        var diamond=new OreFeatureGenerator.Rule(BlockType.DIAMOND_ORE,-218,-40,-204,"deep",.085,1,5,true);
        var mountainIron=new OreFeatureGenerator.Rule(BlockType.IRON_ORE,38,260,125,"high",.38,2,8,false);
        var gold=new OreFeatureGenerator.Rule(BlockType.GOLD_ORE,-210,-31,-80,"triangle",.15,2,6,false);
        check(diamond.weight(-200)>diamond.weight(-50),"Diamonds must prefer deep rock");
        check(mountainIron.weight(200)>mountainIron.weight(60),"High-altitude iron must prefer mountains");
        check(gold.weight(-80)>gold.weight(-204)&&gold.weight(-80)>gold.weight(-34),
            "Gold must favor its declared height band");
        check(diamond.weight(40)==0&&mountainIron.weight(-100)==0,
            "Ore rules must reject unsupported heights");
    }
    private static void testCaves(WorldGenSettings settings){
        var c=new CaveShapeSampler(76543L,settings);
        int carved=0,examined=0;
        for(int x=-260;x<=260;x+=7)for(int z=-260;z<=260;z+=9)for(int y=20;y<=180;y+=11){
            boolean actual=c.carved(x,y,z,260);
            check(actual==c.carved(x,y,z,260),"Cave shape nondeterministic");
            if(actual)carved++;
            examined++;
        }
        check(carved>examined*.001 && carved<examined*.25,"Cave volume implausible: "+carved+"/"+examined);
        for(int y=0;y<=260;y++){
            check(!c.carved(12,y,12,y+12),"Caves must preserve 14-block roof");
            if(y<5)check(!c.carved(-17,y,19,280),"Caves must preserve bedrock floor");
        }
    }
    private static void testAquifers(WorldGenSettings settings){
        var a=new AquiferSampler(789L,settings);
        var wet=new WorldGenerator.Surface(260,Biome.MOSSVEIL_RAINWOOD,.65,.8,.30,false,
                Geology.LIMESTONE,WorldGenerator.WaterBody.LAND,0,false,"grass_top","dirt","stone",224);
        int wetCells=0,dryCells=0;
        for(int x=-300;x<=300;x+=29)for(int z=-300;z<=300;z+=31)for(int y=40;y<=170;y+=13){
            BlockType fluid=a.fluidAt(x,y,z,wet);
            check(fluid==a.fluidAt(x,y,z,wet),"Aquifer depends on evaluation order");
            if(fluid==BlockType.WATER)wetCells++;
            if(fluid==BlockType.AIR)dryCells++;
            check(fluid==BlockType.WATER||fluid==BlockType.LAVA||fluid==BlockType.AIR,
                    "Invalid aquifer output");
        }
        check(wetCells>0&&dryCells>0,"Wet and dry caves should coexist");
        check(a.fluidAt(0,0,0,wet)==BlockType.AIR,"Bedrock cannot fill with water");
    }
    private static void testOceanCatalog(){
        var profiles=new EnumMap<WorldGenerator.OceanBiome,List<MarineSedimentCatalog.Entry>>(
                WorldGenerator.OceanBiome.class);
        for(var ocean:WorldGenerator.OceanBiome.values())
            profiles.put(ocean,List.of(new MarineSedimentCatalog.Entry(BlockType.OCEAN_SILTY_SAND,60),
                    new MarineSedimentCatalog.Entry(BlockType.OCEAN_SHELL_GRIT,40)));
        var marine=new MarineSedimentCatalog(profiles);
        for(var ocean:WorldGenerator.OceanBiome.values())
            check(marine.forOcean(ocean).size()==2,"Missing ocean sediments: "+ocean);
        profiles.remove(WorldGenerator.OceanBiome.KELP);
        try{new MarineSedimentCatalog(profiles);throw new AssertionError("Missing palette accepted");}
        catch(IllegalArgumentException expected) { /* required */ }
    }
    private static void testVegetationCatalog(){
        var trees=new EnumMap<WorldGenerator.TreeType,TreeSpeciesCatalog.Size>(WorldGenerator.TreeType.class);
        for(var type:WorldGenerator.TreeType.values())trees.put(type,new TreeSpeciesCatalog.Size(6,16));
        var catalog=new TreeSpeciesCatalog(trees);
        for(var type:WorldGenerator.TreeType.values()){
            check(catalog.get(type).choose(0)==6,"Minimum tree height");
            check(catalog.get(type).choose(.999999)==16,"Maximum tree height");
        }
        trees.remove(WorldGenerator.TreeType.CYPRESS);
        try{new TreeSpeciesCatalog(trees);throw new AssertionError("Incomplete tree species accepted");}
        catch(IllegalArgumentException expected) { /* required */ }
    }
    private static void testUndergroundPalettes(){
        var rules=new EnumMap<UndergroundBiome,CaveWallCatalog.Rule>(UndergroundBiome.class);
        for(var biome:UndergroundBiome.values()){
            rules.put(biome,new CaveWallCatalog.Rule(List.of(BlockType.STONE),List.of(BlockType.SLATE)));
        }
        var catalog=new CaveWallCatalog(rules);
        for(var biome:UndergroundBiome.values()){
            check(catalog.get(biome).choose(true,0)==BlockType.STONE,"Missing cave floor");
            check(catalog.get(biome).choose(false,2)==BlockType.SLATE,"Missing cave wall");
        }
        rules.remove(UndergroundBiome.CINDERVEIN_TUBES);
        try{new CaveWallCatalog(rules);throw new AssertionError("Missing cave palette accepted");}
        catch(IllegalArgumentException expected){ /* required */ }
    }
    private static void check(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
}
