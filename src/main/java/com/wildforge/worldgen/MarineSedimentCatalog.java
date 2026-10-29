package com.wildforge.worldgen;

import com.google.gson.*;
import com.wildforge.world.BlockType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Data-generated ocean-floor material palettes, shared by all chunks.
 * Weighted coherent patches have no chunk-local random state.
 */
public final class MarineSedimentCatalog {
    public static final String RESOURCE="data/wildforge/worldgen/marine_sediments.json";
    public record Entry(BlockType block,int weight){
        public Entry { if(block==null||block==BlockType.AIR||weight<1)throw new IllegalArgumentException("Bad marine sediment"); }
    }
    private final Map<WorldGenerator.OceanBiome,List<Entry>> palettes;
    public MarineSedimentCatalog(Map<WorldGenerator.OceanBiome,List<Entry>> palettes){
        EnumMap<WorldGenerator.OceanBiome,List<Entry>> safe=new EnumMap<>(WorldGenerator.OceanBiome.class);
        for(var ocean:WorldGenerator.OceanBiome.values()) {
            var values=palettes.get(ocean);
            if(values==null||values.isEmpty())throw new IllegalArgumentException("Missing marine palette: "+ocean);
            safe.put(ocean,List.copyOf(values));
        }
        this.palettes=Map.copyOf(safe);
    }
    public List<Entry> forOcean(WorldGenerator.OceanBiome ocean){return palettes.get(ocean);}
    public static MarineSedimentCatalog loadBuiltIn() throws IOException {
        try(InputStream stream=MarineSedimentCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("Missing "+RESOURCE);
            JsonObject root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-marine-sediments-v1".equals(root.get("schema").getAsString()))throw new IOException("Invalid marine schema");
            EnumMap<WorldGenerator.OceanBiome,List<Entry>> map=new EnumMap<>(WorldGenerator.OceanBiome.class);
            for(var entry:root.getAsJsonObject("oceans").entrySet()){
                WorldGenerator.OceanBiome biome=WorldGenerator.OceanBiome.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                List<Entry> materials=new ArrayList<>();
                for(JsonElement value:entry.getValue().getAsJsonArray()){
                    JsonObject j=value.getAsJsonObject();
                    materials.add(new Entry(BlockType.valueOf(j.get("block").getAsString().toUpperCase(Locale.ROOT)),j.get("weight").getAsInt()));
                }
                map.put(biome,materials);
            }
            return new MarineSedimentCatalog(map);
        }catch(RuntimeException error){throw new IOException("Invalid marine-sediment configuration",error);}
    }
}
