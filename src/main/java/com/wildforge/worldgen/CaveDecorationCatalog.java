package com.wildforge.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildforge.world.BlockType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Data-generated decoration choices for every Wildforge underground biome. */
public final class CaveDecorationCatalog {
    public static final String RESOURCE="data/wildforge/worldgen/cave_decorations.json";
    public record Rule(BlockType floor,BlockType ceiling){}
    private final Map<UndergroundBiome,Rule> rules;
    public CaveDecorationCatalog(Map<UndergroundBiome,Rule> rules){
        if(rules.size()!=UndergroundBiome.values().length)
            throw new IllegalArgumentException("Missing cave decoration rules");
        for(var type:UndergroundBiome.values())if(!rules.containsKey(type))
            throw new IllegalArgumentException("Missing cave decoration: "+type);
        this.rules=Map.copyOf(rules);
    }
    public Rule get(UndergroundBiome biome){return rules.get(biome);}
    public static CaveDecorationCatalog loadBuiltIn()throws IOException{
        try(InputStream stream=CaveDecorationCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("Missing "+RESOURCE);
            JsonObject root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-cave-decorations-v1".equals(root.get("schema").getAsString()))
                throw new IOException("Unsupported cave decoration schema");
            JsonObject all=root.getAsJsonObject("biomes");
            EnumMap<UndergroundBiome,Rule> values=new EnumMap<>(UndergroundBiome.class);
            for(Map.Entry<String,JsonElement> entry:all.entrySet()){
                UndergroundBiome biome=UndergroundBiome.valueOf(entry.getKey().toUpperCase(java.util.Locale.ROOT));
                JsonObject rule=entry.getValue().getAsJsonObject();
                values.put(biome,new Rule(
                        BlockType.valueOf(rule.get("floor").getAsString().toUpperCase(java.util.Locale.ROOT)),
                        BlockType.valueOf(rule.get("ceiling").getAsString().toUpperCase(java.util.Locale.ROOT))));
            }
            return new CaveDecorationCatalog(values);
        }catch(RuntimeException e){throw new IOException("Invalid cave decoration JSON",e);}
    }
}
