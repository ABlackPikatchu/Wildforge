package com.wildforge.worldgen;

import com.google.gson.*;
import com.wildforge.world.BlockType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads the 24-biome design weights; uses seeded spatial patches rather than per-block speckles. */
public final class BiomeDistribution {
    private static final String PATH = "data/wildforge/worldgen/biome_distribution.json";
    private final Map<String, Palette> surface = new HashMap<>();
    private final SeededNoise noise;
    private record Palette(Map<String,Integer> top, Map<String,Integer> under,
                           Map<String,Integer> rock, int minSoil, int maxSoil) {}
    public BiomeDistribution(long seed) {
        noise = new SeededNoise(seed);
        try (InputStream stream = BiomeDistribution.class.getClassLoader().getResourceAsStream(PATH)) {
            if (stream == null) throw new IllegalStateException("Missing " + PATH);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("surface_biomes")) {
                JsonObject b=e.getAsJsonObject();JsonArray depth=b.getAsJsonArray("soil");
                surface.put(b.get("id").getAsString(),new Palette(weights(b.getAsJsonObject("top")),
                        weights(b.getAsJsonObject("under")),weights(b.getAsJsonObject("rock")),
                        depth.get(0).getAsInt(),depth.get(1).getAsInt()));
            }
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Invalid biome distribution",e); }
    }
    private static Map<String,Integer> weights(JsonObject json) {
        LinkedHashMap<String,Integer> map=new LinkedHashMap<>();
        for (var e:json.entrySet()) map.put(e.getKey(),e.getValue().getAsInt());
        return java.util.Collections.unmodifiableMap(map);
    }
    private static long salt(String s) { long h=0xcbf29ce484222325L; for(int i=0;i<s.length();i++) h=(h^s.charAt(i))*0x100000001b3L;return h; }
    /** Weighted spatial competition. Each material has an independent coherent field,
     * so palette entries cannot dominate merely because of insertion order.
     * Wide, warped patches are stable across chunk boundaries. */
    private BlockType pick(Map<String,Integer> palette,int x,int z,String layer,BlockType fallback) {
        if (palette.isEmpty()) return fallback;
        long key = salt(layer);
        double warpX = noise.fbm2((x + 517.0)/110.0,(z-211.0)/110.0,3,key^0x42ac19L)*15.0;
        double warpZ = noise.fbm2((x - 193.0)/110.0,(z+431.0)/110.0,3,key^0x35bd81L)*15.0;
        double nx=(x+warpX)/35.0, nz=(z+warpZ)/35.0;
        double best=-Double.MAX_VALUE;
        BlockType selected=fallback;
        for (var entry:palette.entrySet()) {
            if(entry.getValue()<=0) continue;
            long materialKey=salt(entry.getKey())^key;
            double patch=noise.fbm2(nx,nz,3,materialKey^0x1234abcL);
            double detail=noise.fbm2(nx*2.1,nz*2.1,2,materialKey^0x7654321L);
            double score=Math.log(entry.getValue())+2.8*patch+0.45*detail;
            if(score>best) {best=score;selected=block(entry.getKey(),fallback);}
        }
        return selected;
    }
    /** Surface-exposure rules sit on top of biome palettes rather than
     * treating snow, scree and grass as interchangeable paint. */
    public BlockType exposedTop(Biome biome,int x,int z,int surfaceY,int seaLevel,
                                int slope,BlockType fallback) {
        BlockType chosen=top(biome,x,z,fallback);
        int altitude=surfaceY-seaLevel;
        double exposure=noise.fbm2(x/62.0,z/62.0,3,0x319a22L);
        boolean alpine=biome==Biome.FROSTSPIRE_HIGHLANDS;
        if (alpine) {
            // At high, sheltered elevations snow takes over; on cliffs rock remains visible.
            if (slope<=3 && altitude>70 && exposure>-0.22) return BlockType.PACKED_SNOW;
            if (slope>=6) return exposure>0 ? BlockType.SLATE : BlockType.SHALE;
            if (slope>=4 && exposure>-.25) return BlockType.SCREE;
        } else if (biome==Biome.FROSTMOOR_TUNDRA) {
            if(slope<=2 && exposure>0.15) return BlockType.SNOW_CRUST;
        } else if (biome==Biome.GREENHEART_FOREST || biome==Biome.SUNMEADOW_PLAINS ||
                   biome==Biome.EMBERLEAF_WOODLAND || biome==Biome.NEEDLEWOOD_TAIGA) {
            // Steep surfaces show a little host rock; gentle land keeps fertile soil.
            if(slope>=7) return exposure>0.25 ? BlockType.STONE : BlockType.GRAVEL;
        }
        return chosen;
    }
    private static BlockType block(String id,BlockType fallback) {
        if(id.equals("grass_top"))return BlockType.GRASS;
        if(id.equals("dry_grass_top"))return fallback;
        if(id.equals("forest_loam"))return BlockType.RICH_HUMUS;
        try{return BlockType.valueOf(id.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){return fallback;}
    }
    public BlockType top(Biome biome,int x,int z,BlockType fallback) {
        Palette p=surface.get(biome.name().toLowerCase(Locale.ROOT));
        return p==null?fallback:pick(p.top,x,z,"top",fallback);
    }
    public BlockType under(Biome biome,int x,int z,BlockType fallback) {
        Palette p=surface.get(biome.name().toLowerCase(Locale.ROOT));
        return p==null?fallback:pick(p.under,x,z,"under",fallback);
    }
    public BlockType rock(Biome biome,int x,int z,BlockType fallback) {
        Palette p=surface.get(biome.name().toLowerCase(Locale.ROOT));
        return p==null?fallback:pick(p.rock,x,z,"rock",fallback);
    }
    public int soilDepth(Biome biome,int x,int z) {
        Palette p=surface.get(biome.name().toLowerCase(Locale.ROOT));
        if(p==null)return 4;
        return p.minSoil+(int)(noise.unit(x/16,1,z/16,0x7788L)*(p.maxSoil-p.minSoil+1));
    }
}
