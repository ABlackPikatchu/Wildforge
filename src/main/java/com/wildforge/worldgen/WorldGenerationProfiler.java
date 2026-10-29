package com.wildforge.worldgen;

import java.util.EnumMap;
import java.util.Map;

/** Deterministic headless audit of a new world's ecology and surface composition.
 * This is a sample profiler, not an exact count of fully generated chunks.
 * No OpenGL or game session is required.
 */
public final class WorldGenerationProfiler {
    private WorldGenerationProfiler() {}
    private static WorldGenSettings load() {
        try{return WorldGenConfigLoader.loadBuiltIn();}
        catch(java.io.IOException e){throw new IllegalStateException("Missing generated world settings",e);}
    }
    public static void main(String[] args) {
        long seed=args.length>0?Long.parseLong(args[0]):20261029L;
        int radius=args.length>1?Integer.parseInt(args[1]):512;
        int step=args.length>2?Integer.parseInt(args[2]):16;
        if(radius<64||radius>4096||step<4||step>64)
            throw new IllegalArgumentException("Radius must be 64..4096 and step 4..64");
        var generator=new WorldGenerator(seed,load());
        var biomes=new EnumMap<Biome,Integer>(Biome.class);
        int samples=0,water=0,mountains=0,highest=Integer.MIN_VALUE,lowest=Integer.MAX_VALUE;
        int trees=0,plants=0,caves=0,caveSamples=0,overhangs=0;
        long started=System.nanoTime();
        for(int x=-radius;x<=radius;x+=step)for(int z=-radius;z<=radius;z+=step){
            WorldGenerator.Surface s=generator.surface(x,z);
            samples++; biomes.merge(s.biome(),1,Integer::sum);
            if(s.waterBody()!=WorldGenerator.WaterBody.LAND)water++;
            highest=Math.max(highest,s.surfaceY());lowest=Math.min(lowest,s.surfaceY());
            if(s.surfaceY()>=320)mountains++;
            int slope=generator.slopeAt(x,z);
            if(generator.terrainDensity(x,s.surfaceY()+2,z,s.surfaceY(),slope)>0)overhangs++;
            if(generator.treeForCell(Math.floorDiv(x,WorldGenerator.TREE_CELL_SIZE),
                    Math.floorDiv(z,WorldGenerator.TREE_CELL_SIZE))!=null)trees++;
            if(generator.plantForCell(Math.floorDiv(x,WorldGenerator.PLANT_CELL_SIZE),
                    Math.floorDiv(z,WorldGenerator.PLANT_CELL_SIZE))!=null)plants++;
            if(samples%8==0){
                int y=Math.min(s.surfaceY()-18,64);
                if(y>8){caveSamples++;if(generator.isCave(x,y,z,s.surfaceY()))caves++;}
            }
        }
        System.out.println("Wildforge worldgen profile — revision "+WorldGenerator.CURRENT_REVISION+" | seed="+seed+" | sample="+samples+
                " | spacing="+step+" | radius="+radius);
        System.out.printf("Terrain: minY=%d maxY=%d wet=%.1f%% high_mountain=%.1f%% overhang_columns=%.2f%%%n",
                lowest,highest,100.0*water/samples,100.0*mountains/samples,100.0*overhangs/samples);
        System.out.printf("Candidate samples: tree=%.2f%% plant=%.2f%% deep_cave=%.2f%%%n",
                100.0*trees/samples,100.0*plants/samples,100.0*caves/Math.max(1,caveSamples));
        final int finalSamples=samples;
        biomes.entrySet().stream().sorted(Map.Entry.<Biome,Integer>comparingByValue().reversed())
                .forEach(e->System.out.printf("  %-28s %6.2f%%%n",e.getKey(),100.0*e.getValue()/finalSamples));
        System.out.printf("Profile wall time: %.1f ms%n",(System.nanoTime()-started)/1_000_000.0);
    }
}
