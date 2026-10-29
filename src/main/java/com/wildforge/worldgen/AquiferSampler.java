package com.wildforge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildforge.world.BlockType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic water tables shared by adjacent aquifer cells. Their local fluid
 * pressures blend in absolute world coordinates, with independent 3D basin masks.
 * Air caves remain dry above the water table. Bedrock and surface roofs are never
 * touched; the generation pipeline invokes this only for previously carved air.
 */
public final class AquiferSampler {
    public static final String RESOURCE="data/wildforge/worldgen/aquifers.json";
    public record Rules(int cellSize,int seaOffset,double tableVariation,double wetThreshold,
                        double lavaThreshold,int lavaMaxY) {
        public Rules {
            if(cellSize<16||cellSize>256||seaOffset<0||tableVariation<0||wetThreshold<-.9||wetThreshold>.9||
                lavaThreshold<-.9||lavaThreshold>.9||lavaMaxY<6||lavaMaxY>80)
                throw new IllegalArgumentException("Invalid aquifer tuning");
        }
    }
    private final SeededNoise noise;
    private final WorldGenSettings settings;
    private final Rules rules;
    public AquiferSampler(long seed,WorldGenSettings settings){this(seed,settings,loadRules());}
    public AquiferSampler(long seed,WorldGenSettings settings,Rules rules){
        this.noise=new SeededNoise(seed);this.settings=settings;this.rules=rules;
    }
    public static Rules defaults(){return new Rules(72,33,22,.13,.36,29);}
    public static Rules loadRules(){
        try(InputStream in=AquiferSampler.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(in==null)return defaults();
            JsonObject j=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-aquifers-v1".equals(j.get("schema").getAsString()))throw new IOException("Aquifer schema mismatch");
            return new Rules(j.get("cell_size").getAsInt(),j.get("water_table_below_sea").getAsInt(),
                j.get("water_table_variation").getAsDouble(),j.get("basin_threshold").getAsDouble(),
                j.get("lava_threshold").getAsDouble(),j.get("lava_max_y").getAsInt());
        }catch(Exception e){throw new IllegalStateException("Invalid aquifer generated data",e);}
    }
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double smooth(double x){return x*x*(3-2*x);}
    /** Height is continuous at cell boundaries, independently of generation order. */
    public double waterTableAt(int x,int z){
        int size=rules.cellSize();
        int cx=Math.floorDiv(x,size),cz=Math.floorDiv(z,size);
        double fx=smooth(Math.floorMod(x,size)/(double)size),fz=smooth(Math.floorMod(z,size)/(double)size);
        double a=lerp(cellTable(cx,cz),cellTable(cx+1,cz),fx);
        double b=lerp(cellTable(cx,cz+1),cellTable(cx+1,cz+1),fx);
        return lerp(a,b,fz);
    }
    private double cellTable(int x,int z){
        return settings.seaLevel()-rules.seaOffset()+
                (noise.unit(x,0,z,0x617175696665724cL)*2-1)*rules.tableVariation();
    }
    public BlockType fluidAt(int x,int y,int z,WorldGenerator.Surface surface){
        if(y<5||y>=surface.surfaceY()-16)return BlockType.AIR;
        if(y<rules.lavaMaxY()&&noise.noise3(x/90.0,y/50.0,z/90.0,0x1a7a001L)>rules.lavaThreshold())
            return BlockType.LAVA;
        if(surface.rainfall()<=.30&&surface.waterBody()==WorldGenerator.WaterBody.LAND)return BlockType.AIR;
        double table=waterTableAt(x,z);
        // 3D enclosure preserves dry caverns within the same underground biome.
        double basin=noise.noise3(x/100.0,y/63.0,z/100.0,0xa91beefL);
        return y<table && basin>rules.wetThreshold()?BlockType.WATER:BlockType.AIR;
    }
}
