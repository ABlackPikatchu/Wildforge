package com.wildforge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildforge.world.BlockType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Global stone/slate strata and seeded bottom-bedrock gradient.
 * Bedrock is always solid at Y=0; the next four layers are progressively rarer.
 * The lower, negative-Y void is outside the loaded dimension.
 */
public final class StrataGenerator {
    public static final String RESOURCE="data/wildforge/worldgen/strata.json";
    private final long seed;
    private final int seaLevel,deepRelative,transition,layers,accentChance;
    public StrataGenerator(long seed,int seaLevel,int deepRelative,int transition,int layers,int accentChance) {
        if(transition<1||layers<1||layers>16||accentChance<0||accentChance>100)
            throw new IllegalArgumentException("Invalid strata settings");
        this.seed=seed;this.seaLevel=seaLevel;this.deepRelative=deepRelative;
        this.transition=transition;this.layers=layers;this.accentChance=accentChance;
    }
    public static StrataGenerator loadBuiltIn(long seed,int seaLevel) throws IOException {
        try(InputStream stream=StrataGenerator.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("Missing "+RESOURCE);
            JsonObject j=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            return new StrataGenerator(seed,seaLevel,j.get("deep_rock_relative_sea").getAsInt(),
                    j.get("deep_rock_transition").getAsInt(),j.get("bedrock_layers").getAsInt(),
                    j.get("accent_percent").getAsInt());
        }catch(RuntimeException e){throw new IOException("Invalid strata JSON",e);}
    }
    /** Layer 0 guaranteed; higher layers independently choose bedrock with a falling probability. */
    public boolean bedrockAt(int x,int y,int z){
        if(y<0||y>=layers)return false;
        if(y==0)return true;
        return unit(x,y,z,0xbed0c4L)<(layers-y)/(double)layers;
    }
    /** Host rock must be mostly two blocks; local geology only accents rare lenses. */
    public BlockType rockAt(int x,int y,int z,BlockType accent) {
        int boundary=seaLevel+deepRelative;
        double deepFraction=Math.max(0,Math.min(1,(boundary+transition-y)/(double)transition));
        boolean deep=unit(x,y,z,0xdecaf10L)<deepFraction;
        // Patch-aligned low-density alternate rocks; never replace the dominant host everywhere.
        if(accent!=null && accent!=BlockType.AIR &&
           unit(Math.floorDiv(x,7),Math.floorDiv(y,7),Math.floorDiv(z,7),0xaacc331L)<accentChance/100.0)
            return accent;
        return deep?BlockType.SLATE:BlockType.STONE;
    }
    private double unit(int x,int y,int z,long salt){
        long v=seed^salt^((long)x*0x9e3779b97f4a7c15L)^((long)y*0xbf58476d1ce4e5b9L)^((long)z*0x94d049bb133111ebL);
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return ((v^(v>>>31))>>>11)*0x1.0p-53;
    }
}
