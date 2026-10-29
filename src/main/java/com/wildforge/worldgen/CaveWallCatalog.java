package com.wildforge.worldgen;

import com.google.gson.*;
import com.wildforge.world.BlockType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Data-authored underground cave surface palettes; all twelve biome IDs required. */
public final class CaveWallCatalog {
    public static final String RESOURCE="data/wildforge/worldgen/cave_walls.json";
    public record Rule(List<BlockType> floor,List<BlockType> wall) {
        public Rule {if(floor==null||wall==null||floor.isEmpty()||wall.isEmpty())
            throw new IllegalArgumentException("Empty cave surface palette");
            floor=List.copyOf(floor);wall=List.copyOf(wall);
        }
        public BlockType choose(boolean floorFace,int n){
            var palette=floorFace?floor:wall;
            return palette.get(Math.floorMod(n,palette.size()));
        }
    }
    private final Map<UndergroundBiome,Rule> rules;
    public CaveWallCatalog(Map<UndergroundBiome,Rule> rules){
        for(var b:UndergroundBiome.values())if(!rules.containsKey(b))
            throw new IllegalArgumentException("Missing cave wall palette: "+b);
        this.rules=Map.copyOf(rules);
    }
    public Rule get(UndergroundBiome biome){return rules.get(biome);}
    public static CaveWallCatalog loadBuiltIn() throws IOException {
        try(InputStream stream=CaveWallCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("Missing "+RESOURCE);
            var root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-cave-walls-v1".equals(root.get("schema").getAsString()))throw new IOException("Bad cave walls schema");
            var result=new EnumMap<UndergroundBiome,Rule>(UndergroundBiome.class);
            for(var entry:root.getAsJsonObject("biomes").entrySet()){
                var biome=UndergroundBiome.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                JsonObject j=entry.getValue().getAsJsonObject();
                result.put(biome,new Rule(read(j.getAsJsonArray("floor")),read(j.getAsJsonArray("wall"))));
            }
            return new CaveWallCatalog(result);
        }catch(RuntimeException error){throw new IOException("Invalid cave walls",error);}
    }
    private static List<BlockType> read(JsonArray list){
        var out=new java.util.ArrayList<BlockType>();
        for(JsonElement entry:list)out.add(BlockType.valueOf(entry.getAsString().toUpperCase(Locale.ROOT)));
        return List.copyOf(out);
    }
}
