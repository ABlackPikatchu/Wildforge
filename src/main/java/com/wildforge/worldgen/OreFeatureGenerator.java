package com.wildforge.worldgen;

import com.google.gson.*;
import com.wildforge.world.BlockType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Data-driven replacement for hardcoded ore IDs, height bands and vein sizes.
 * World-coordinate deposits remain deterministic across generation order and chunk seams.
 */
public final class OreFeatureGenerator {
    public static final String RESOURCE="data/wildforge/worldgen/ores.json";
    private static final int SIDE=16,BAND=24;
    public record Rule(BlockType block,int minRelative,int maxRelative,int peakRelative,
                       String shape,double chance,int minSize,int maxSize,boolean discardExposed){
        public Rule {
            if(block==BlockType.AIR||minRelative>maxRelative||!List.of("triangle","high","deep","uniform").contains(shape)
                    ||chance<0||chance>1||minSize<1||maxSize<minSize||maxSize>64)
                throw new IllegalArgumentException("Invalid ore profile: "+block);
        }
        /** Triangular bias for common ores; deeper increases diamonds; higher favors coal/mountain iron. */
        public double weight(int relativeY){
            if(relativeY<minRelative||relativeY>maxRelative)return 0;
            double x=(relativeY-minRelative)/(double)Math.max(1,maxRelative-minRelative);
            double peak=(peakRelative-minRelative)/(double)Math.max(1,maxRelative-minRelative);
            return switch(shape){
                case "high" -> .20+.80*x;
                case "deep" -> .15+.85*(1-x);
                case "triangle" -> .12+.88*(x<=peak ? x/Math.max(.00001,peak) : (1-x)/Math.max(.00001,1-peak));
                default -> 1.0;
            };
        }
    }
    private final SeededNoise noise;
    private final List<Rule> rules;
    private final long seed;
    public OreFeatureGenerator(long seed,List<Rule> rules){this.seed=seed;noise=new SeededNoise(seed);this.rules=List.copyOf(rules);}
    public static OreFeatureGenerator loadBuiltIn(long seed)throws IOException {
        try(InputStream stream=OreFeatureGenerator.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("Missing "+RESOURCE);
            JsonObject root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            ArrayList<Rule> rules=new ArrayList<>();
            for(JsonElement el:root.getAsJsonArray("ores")){
                JsonObject j=el.getAsJsonObject();
                rules.add(new Rule(BlockType.valueOf(j.get("block").getAsString().toUpperCase(java.util.Locale.ROOT)),
                        j.get("min_relative_sea").getAsInt(),j.get("max_relative_sea").getAsInt(),
                        j.get("peak_relative_sea").getAsInt(),j.get("shape").getAsString(),
                        j.get("chance").getAsDouble(),j.get("min_size").getAsInt(),
                        j.get("max_size").getAsInt(),j.get("discard_exposed").getAsBoolean()));
            }
            if(rules.isEmpty())throw new IOException("No ore rules");
            return new OreFeatureGenerator(seed,rules);
        }catch(RuntimeException e){throw new IOException("Invalid ore feature configuration",e);}
    }
    public List<Rule> rules(){return rules;}
    public void generate(byte[] blocks,int chunkX,int chunkZ,int height,WorldGenerator world) {
        int sea=world.settings().seaLevel();
        for(int ax=chunkX-1;ax<=chunkX+1;ax++)for(int az=chunkZ-1;az<=chunkZ+1;az++){
            for(int ay=0;ay<=(height-1)/BAND;ay++)for(int r=0;r<rules.size();r++){
                Rule rule=rules.get(r);
                long salt=0x4f524553L+r*0x54abL;
                int ox=ax*SIDE+(int)(noise.unit(ax,ay,az,salt+1)*SIDE);
                int oy=ay*BAND+(int)(noise.unit(ax,ay,az,salt+2)*BAND);
                int oz=az*SIDE+(int)(noise.unit(ax,ay,az,salt+3)*SIDE);
                if(oy<=4||oy>=height-2)continue;
                double probability=rule.chance()*rule.weight(oy-sea);
                if(noise.unit(ax,ay,az,salt)>=probability)continue;
                int length=rule.minSize()+(int)(noise.unit(ax,ay,az,salt+4)*(rule.maxSize()-rule.minSize()+1));
                int x=ox,y=oy,z=oz;
                for(int i=0;i<length;i++){
                    if(x>=chunkX*SIDE&&x<(chunkX+1)*SIDE&&z>=chunkZ*SIDE&&z<(chunkZ+1)*SIDE&&y>=5&&y<height-1){
                        int index=((x-chunkX*SIDE)*SIDE+z-chunkZ*SIDE)*height+y;
                        BlockType host=BlockType.fromId(blocks[index]);
                        if(hostRock(host)&&(!rule.discardExposed()||!exposed(blocks,x-chunkX*SIDE,y,z-chunkZ*SIDE,height)))
                            blocks[index]=rule.block().getId();
                    }
                    int dir=(int)(noise.unit(ox+i,oy+i,oz-i,salt+5)*6);
                    switch(dir){case 0->x++;case 1->x--;case 2->y++;case 3->y--;case 4->z++;default->z--;}
                }
            }
        }
    }
    private static boolean hostRock(BlockType b){
        return switch(b){
            case STONE,SLATE,GRANITE,ANDESITE,DIORITE,GABBRO,SHALE,LIMESTONE,
                    BASALT,SANDSTONE,SILTSTONE,SCORIA -> true;
            default -> false;
        };
    }
    private static boolean exposed(byte[] b,int x,int y,int z,int height){
        // Near a chunk edge use interior faces; never assume an unloaded neighbor is air.
        int base=(x*SIDE+z)*height+y;
        if(b[base-1]==BlockType.AIR.getId()||b[base+1]==BlockType.AIR.getId())return true;
        if(x>0&&b[base-SIDE*height]==BlockType.AIR.getId())return true;
        if(x<SIDE-1&&b[base+SIDE*height]==BlockType.AIR.getId())return true;
        if(z>0&&b[base-height]==BlockType.AIR.getId())return true;
        return z<SIDE-1&&b[base+height]==BlockType.AIR.getId();
    }
}
