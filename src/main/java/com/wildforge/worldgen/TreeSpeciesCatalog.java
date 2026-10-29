package com.wildforge.worldgen;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Data-generated mature tree dimensions; biome ecology picks the species. */
public final class TreeSpeciesCatalog {
    public static final String RESOURCE="data/wildforge/worldgen/trees.json";
    public record Size(int minHeight,int maxHeight){
        public Size { if(minHeight<2||maxHeight<minHeight||maxHeight>64)
            throw new IllegalArgumentException("Invalid tree heights"); }
        public int choose(double unit){return minHeight+(int)(Math.max(0,Math.min(.9999999,unit))*(maxHeight-minHeight+1));}
    }
    private final Map<WorldGenerator.TreeType,Size> entries;
    public TreeSpeciesCatalog(Map<WorldGenerator.TreeType,Size> entries){
        for(var tree:WorldGenerator.TreeType.values())if(!entries.containsKey(tree))
            throw new IllegalArgumentException("Missing tree dimensions: "+tree);
        this.entries=Map.copyOf(entries);
    }
    public Size get(WorldGenerator.TreeType tree){return entries.get(tree);}
    public static TreeSpeciesCatalog loadBuiltIn() throws IOException {
        try(InputStream stream=TreeSpeciesCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("Missing "+RESOURCE);
            JsonObject root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-tree-species-v1".equals(root.get("schema").getAsString()))throw new IOException("Bad trees schema");
            EnumMap<WorldGenerator.TreeType,Size> values=new EnumMap<>(WorldGenerator.TreeType.class);
            for(var entry:root.getAsJsonObject("trees").entrySet()){
                JsonObject j=entry.getValue().getAsJsonObject();
                values.put(WorldGenerator.TreeType.valueOf(entry.getKey().toUpperCase(Locale.ROOT)),
                        new Size(j.get("min_height").getAsInt(),j.get("max_height").getAsInt()));
            }
            return new TreeSpeciesCatalog(values);
        }catch(RuntimeException e){throw new IOException("Invalid tree species catalog",e);}
    }
    public static TreeSpeciesCatalog loadBuiltInUnchecked(){
        try{return loadBuiltIn();}catch(IOException e){throw new IllegalStateException(e);}
    }
}
