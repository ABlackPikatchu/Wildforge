package com.wildforge.world.light;

import com.google.gson.*;
import com.wildforge.world.BlockType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Generated block-emission values. Sky exposure is calculated by LightEngine. */
public final class LightEmissionCatalog {
    private static final byte[] LEVELS=load();
    private LightEmissionCatalog() {}
    public static int emission(byte blockId){return Byte.toUnsignedInt(LEVELS[blockId&255]);}
    private static byte[] load(){
        byte[] result=new byte[256];
        result[BlockType.LAVA.getId()&255]=15;
        result[BlockType.FLOWING_LAVA.getId()&255]=15;
        result[BlockType.MAGMA_CRUST.getId()&255]=6;
        result[BlockType.SPORECAP_FUNGUS.getId()&255]=10;
        result[BlockType.CRYSTAL_CLUSTER.getId()&255]=8;
        try(InputStream in=LightEmissionCatalog.class.getClassLoader().getResourceAsStream("data/wildforge/lighting/emission.json")){
            if(in==null)return result; // standalone headless tools without generated resources
            JsonObject root=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-light-emission-v1".equals(root.get("schema").getAsString()))
                throw new IllegalStateException("Unknown emission schema");
            for(var entry:root.getAsJsonObject("blocks").entrySet()){
                int value=entry.getValue().getAsInt();
                if(value<0||value>15)throw new IllegalStateException("Invalid light level: "+entry.getKey());
                result[BlockType.valueOf(entry.getKey().toUpperCase(java.util.Locale.ROOT)).getId()&255]=(byte)value;
            }
            return result;
        }catch(Exception ex){throw new IllegalStateException("Invalid generated emission catalog",ex);}
    }
}
